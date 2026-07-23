package dev.deedles.tailsync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import mobile.Config
import mobile.EventListener
import mobile.Mobile
import mobile.Node
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Foreground service that owns the gomobile [Node] lifecycle.
 *
 * [Node.start] and [Node.stop] always run off the main thread. A monotonic
 * generation + [AtomicReference] ensure stop always owns the node that start
 * created, even when JNI [Node.start] ignores coroutine cancellation.
 *
 * Rapid off→on never installs a waiter into [startJob]: stop only joins
 * genuine [runStart] jobs, and a deferred restart runs after stop finishes.
 */
class TailsyncService : LifecycleService() {

    private val settings: SettingsStore by lazy {
        TailsyncApplication.settingsOf(application)
    }

    private val ownedNode = AtomicReference<Node?>(null)
    private val runGeneration = AtomicLong(0)

    /** Only jobs that execute [runStart] — never stop-waiters. */
    private var startJob: Job? = null
    private var statusJob: Job? = null
    private var stopJob: Job? = null

    /**
     * Set when [startNode] is requested while [stopJob] is still running.
     * After teardown, start again instead of [stopSelf] so off→on does not
     * mutual-join a waiter with [stopOwnedNodes].
     */
    @Volatile
    private var restartRequested: Boolean = false

    override fun onCreate() {
        super.onCreate()
        serviceActive.set(true)
        createNotificationChannel()
        TailsyncRuntime.setServiceRunning(true)
        TailsyncRuntime.setEngineVersion(runCatching { Mobile.version() }.getOrNull())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                // Explicit stop cancels any deferred restart.
                restartRequested = false
                stopSelfGracefully()
                return START_NOT_STICKY
            }
            else -> {
                startInForeground(getString(R.string.notification_text_starting))
                startNode()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        // Never call blocking Node.stop() on the main thread (ANR risk; ~30s).
        runGeneration.incrementAndGet()
        statusJob?.cancel()
        statusJob = null
        restartRequested = false
        val job = startJob
        // Only clear if still the job we captured (avoid wiping a concurrent assign).
        if (startJob === job) {
            startJob = null
        }
        val node = ownedNode.getAndSet(null)

        cleanupScope.launch {
            disposeNode(node, "onDestroy")
            withTimeoutOrNull(STOP_JOIN_TIMEOUT_MS) { job?.join() }
            disposeNode(ownedNode.getAndSet(null), "onDestroy-late")
        }

        serviceActive.set(false)
        TailsyncRuntime.setServiceRunning(false)
        TailsyncRuntime.markIdle()
        super.onDestroy()
    }

    private fun startInForeground(contentText: String) {
        val notification = buildNotification(contentText)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun startNode() {
        if (startJob?.isActive == true) return
        if (stopJob?.isActive == true) {
            // Do not join stop from a waiter stored in startJob (mutual join).
            // Defer start until stopOwnedNodes finishes; then restart in-process.
            restartRequested = true
            TailsyncRuntime.appendLog("Start deferred until stop completes")
            TailsyncRuntime.setPhase("stopping")
            return
        }
        beginStartLocked()
    }

    private fun beginStartLocked() {
        if (startJob?.isActive == true) return
        if (stopJob?.isActive == true) {
            restartRequested = true
            return
        }
        val gen = runGeneration.incrementAndGet()
        val job = lifecycleScope.launch(Dispatchers.IO) {
            try {
                runStart(gen)
            } finally {
                // Clear only if we are still the active start job.
                if (startJob === coroutineContext[Job]) {
                    startJob = null
                }
            }
        }
        startJob = job
    }

    private suspend fun runStart(gen: Long) {
        TailsyncRuntime.setLastError(null)
        TailsyncRuntime.setPhase("starting")
        TailsyncRuntime.clearLogs()
        TailsyncRuntime.appendLog("Starting node…")

        if (!stillOwner(gen)) return

        val user = settings.load()
        val syncDirFile = PathUtils.ensureDir(user.syncDir)
        val stateDirFile = PathUtils.ensureDir(user.stateDir)

        if (!PathUtils.isAbsoluteWritable(syncDirFile.absolutePath)) {
            if (stillOwner(gen)) {
                failAndStop("Sync directory is not writable: ${syncDirFile.absolutePath}")
            }
            return
        }
        if (!PathUtils.isAbsoluteWritable(stateDirFile.absolutePath)) {
            if (stillOwner(gen)) {
                failAndStop("State directory is not writable: ${stateDirFile.absolutePath}")
            }
            return
        }

        if (!stillOwner(gen)) return

        // Never log Config (generated toString includes AuthKey).
        val cfg = Config().apply {
            dir = syncDirFile.absolutePath
            stateDir = stateDirFile.absolutePath
            hostname = user.hostname.trim()
            authKey = user.authKey
            port = user.port.toLong()
            peers = user.peers.trim()
            serviceName = user.serviceName.trim()
            scanIntervalMs = user.scanIntervalMs
            syncIntervalMs = user.syncIntervalMs
            blockSize = user.blockSize.toLong()
            netMode = user.netMode.ifBlank { "tsnet" }
        }

        val created = try {
            Mobile.newNode(cfg)
        } catch (e: Exception) {
            if (stillOwner(gen)) {
                failAndStop("Invalid config: ${safeErrorMessage(e)}")
            }
            return
        }

        if (!claimNode(created, gen)) {
            disposeNode(created, "claim-failed")
            return
        }

        created.setListener(EventListener { eventJson ->
            // Called from a Go background thread — keep this fast.
            if (ownedNode.get() === created && stillOwner(gen)) {
                handleNativeEvent(eventJson)
            }
        })

        try {
            // Blocks until listening or failure; may be aborted by concurrent stop().
            created.start()
        } catch (e: Exception) {
            disposeNode(created, "start-failed")
            ownedNode.compareAndSet(created, null)
            if (stillOwner(gen)) {
                failAndStop("Start failed: ${safeErrorMessage(e)}")
            }
            return
        }

        // Post-start: cooperative cancel does not abort JNI; check ownership.
        if (!stillOwner(gen) || ownedNode.get() !== created) {
            disposeNode(created, "aborted-after-start")
            ownedNode.compareAndSet(created, null)
            return
        }

        TailsyncRuntime.setNodeRunning(true)
        TailsyncRuntime.setPhase("running")
        TailsyncRuntime.appendLog("Node started")
        refreshStatus(created, gen)
        updateNotification(getString(R.string.notification_text_running))
        startStatusPolling(created, gen)
    }

    private fun claimNode(created: Node, gen: Long): Boolean {
        if (!stillOwner(gen)) return false
        val previous = ownedNode.getAndSet(created)
        if (previous != null && previous !== created) {
            disposeNode(previous, "replaced")
        }
        if (!stillOwner(gen)) {
            ownedNode.compareAndSet(created, null)
            return false
        }
        return true
    }

    private fun stillOwner(gen: Long): Boolean =
        runGeneration.get() == gen && serviceActive.get()

    private fun handleNativeEvent(eventJson: String) {
        TailsyncRuntime.emitEvent(eventJson)
        try {
            val obj = JSONObject(eventJson)
            when (obj.optString("type")) {
                "status" -> {
                    val running = obj.optBoolean("running", false)
                    TailsyncRuntime.setNodeRunning(running)
                    TailsyncRuntime.setPhase(if (running) "running" else "idle")
                    val msg = obj.optString("msg", "")
                    if (msg.isNotBlank()) {
                        TailsyncRuntime.appendLog(msg)
                    }
                    if (running) {
                        updateNotification(getString(R.string.notification_text_running))
                    }
                }
                "error" -> {
                    val msg = obj.optString("msg", "unknown error")
                    val phase = obj.optString("phase", "")
                    TailsyncRuntime.setLastError(msg)
                    TailsyncRuntime.appendLog(
                        "error${if (phase.isNotBlank()) " ($phase)" else ""}: $msg",
                    )
                    updateNotification(getString(R.string.notification_text_error))
                }
                "log" -> {
                    val level = obj.optString("level", "INFO")
                    val msg = obj.optString("msg", "")
                    if (msg.isNotBlank()) {
                        TailsyncRuntime.appendLog("$level: $msg")
                    }
                }
            }
        } catch (_: Exception) {
            Log.w(TAG, "Failed to parse event JSON")
        }
    }

    private fun startStatusPolling(active: Node, gen: Long) {
        statusJob?.cancel()
        statusJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive && stillOwner(gen) && ownedNode.get() === active) {
                refreshStatus(active, gen)
                delay(STATUS_POLL_MS)
            }
        }
    }

    private fun refreshStatus(active: Node, gen: Long) {
        if (!stillOwner(gen) || ownedNode.get() !== active) return
        try {
            val json = active.statusJSON()
            if (!stillOwner(gen) || ownedNode.get() !== active) return
            TailsyncRuntime.setStatusJson(json)
            val obj = JSONObject(json)
            TailsyncRuntime.setPhase(
                obj.optString("phase", if (active.isRunning) "running" else "idle"),
            )
            TailsyncRuntime.setNodeRunning(obj.optBoolean("running", false))
        } catch (_: Exception) {
            // Status is best-effort for UI.
        }
    }

    private suspend fun failAndStop(message: String) {
        val safe = LogRedactor.redact(message)
        Log.e(TAG, safe)
        // Terminal start failure: clear persisted user intent so UI/cold-start
        // does not re-enter a stuck "wanted but not running" state.
        settings.setServiceWanted(false)
        restartRequested = false
        TailsyncRuntime.setLastError(safe)
        TailsyncRuntime.appendLog(safe)
        TailsyncRuntime.setPhase("idle")
        TailsyncRuntime.setNodeRunning(false)
        updateNotification(getString(R.string.notification_text_error))
        withContext(Dispatchers.Main) {
            stopSelfGracefully()
        }
    }

    private fun stopSelfGracefully() {
        if (stopJob?.isActive == true) return
        updateNotification(getString(R.string.notification_text_stopping))
        TailsyncRuntime.setPhase("stopping")
        stopJob = lifecycleScope.launch(Dispatchers.IO) {
            val shouldRestart: Boolean
            try {
                stopOwnedNodes()
                shouldRestart = restartRequested && serviceActive.get()
                restartRequested = false
            } finally {
                // Release stop lock before beginStartLocked so it does not
                // re-enter the "defer until stop completes" path.
                stopJob = null
            }
            if (shouldRestart && serviceActive.get()) {
                // Off→on during stop: start again on this instance (no waiter join).
                startInForeground(getString(R.string.notification_text_starting))
                beginStartLocked()
            } else if (serviceActive.get()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /**
     * Invalidates the current generation, stops any owned node, and joins the
     * [runStart] job only (never a stop-waiter).
     */
    private suspend fun stopOwnedNodes() {
        runGeneration.incrementAndGet()
        statusJob?.cancel()
        statusJob = null

        val job = startJob
        val node = ownedNode.getAndSet(null)
        // Concurrent stop() aborts an in-flight start() on the Go side.
        disposeNode(node, "stop")

        withTimeoutOrNull(STOP_JOIN_TIMEOUT_MS) { job?.join() }
        // Do not wipe a startJob assigned after we captured [job].
        if (startJob === job) {
            startJob = null
        }

        val late = ownedNode.getAndSet(null)
        disposeNode(late, "stop-late")

        TailsyncRuntime.setNodeRunning(false)
        TailsyncRuntime.setPhase("idle")
        TailsyncRuntime.setStatusJson(null)
        TailsyncRuntime.appendLog("Node stopped")
    }

    private fun disposeNode(node: Node?, reason: String) {
        if (node == null) return
        try {
            node.setListener(null)
        } catch (_: Exception) {
            // ignore
        }
        try {
            node.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Stop failed ($reason): ${safeErrorMessage(e)}")
        }
    }

    private fun safeErrorMessage(e: Exception): String =
        LogRedactor.redact(e.message ?: e.javaClass.simpleName)

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_desc)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TailsyncService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.service_stop_action), stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    companion object {
        private const val TAG = "TailsyncService"
        private const val CHANNEL_ID = "tailsync_sync"
        private const val NOTIFICATION_ID = 1
        private const val STATUS_POLL_MS = 3_000L
        private const val STOP_JOIN_TIMEOUT_MS = 35_000L

        const val ACTION_STOP = "dev.deedles.tailsync.action.STOP"

        /** Process-scoped flag: true between [onCreate] and [onDestroy]. */
        private val serviceActive = AtomicBoolean(false)

        /**
         * Outlives a single service instance so [Node.stop] can finish after
         * [onDestroy] without blocking the main thread.
         */
        private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun isActiveInProcess(): Boolean = serviceActive.get()

        fun start(context: Context) {
            val intent = Intent(context, TailsyncService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            // Do not create the service solely to tear it down (UI flicker).
            if (!serviceActive.get() && !TailsyncRuntime.serviceRunning.value) {
                return
            }
            val intent = Intent(context, TailsyncService::class.java).setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (_: Exception) {
                context.stopService(Intent(context, TailsyncService::class.java))
            }
        }
    }
}
