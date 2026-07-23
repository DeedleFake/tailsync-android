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

    /** Last notification body text applied (skip redundant notify + rebuilds). */
    @Volatile
    private var lastNotificationText: String? = null

    /** Auth URL last embedded in the notification action (null = no action). */
    @Volatile
    private var lastNotificationAuthUrl: String? = null

    override fun onCreate() {
        super.onCreate()
        serviceActive.set(true)
        createNotificationChannel()
        TailsyncRuntime.setServiceRunning(true)
        TailsyncRuntime.setEngineVersion(runCatching { Mobile.version() }.getOrNull())
        // Keep Go's interface snapshot current for tsnet (Android blocks netlink).
        AndroidNetworkBridge.startMonitoring(this)
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
            ACTION_OPEN_AUTH -> {
                val url = intent.getStringExtra(EXTRA_AUTH_URL)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: TailsyncRuntime.authUrl.value
                if (!url.isNullOrBlank()) {
                    // Explicit notification action — always open (not once-only).
                    lifecycleScope.launch(Dispatchers.Main) {
                        AuthBrowser.open(applicationContext, url)
                    }
                }
                // Do not re-enter startNode; service may already be starting.
                return stickyIfRunning()
            }
            else -> {
                startInForeground(getString(R.string.notification_text_starting))
                startNode()
            }
        }
        // Until the node is fully up, do not START_STICKY — a native abort
        // (e.g. tsnet panic) would otherwise restart the service in a loop.
        return stickyIfRunning()
    }

    /** Sticky only after a successful Start so crash-during-start does not loop. */
    private fun stickyIfRunning(): Int =
        if (TailsyncRuntime.nodeRunning.value) START_STICKY else START_NOT_STICKY

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

        AndroidNetworkBridge.stopMonitoring(this)
        serviceActive.set(false)
        TailsyncRuntime.setServiceRunning(false)
        TailsyncRuntime.markIdle()
        AuthBrowser.clearAutoOpenTracking()
        lastNotificationText = null
        lastNotificationAuthUrl = null
        super.onDestroy()
    }

    private fun startInForeground(contentText: String) {
        lastNotificationText = contentText
        lastNotificationAuthUrl = if (TailsyncRuntime.needsLogin.value) {
            TailsyncRuntime.authUrl.value?.trim()?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
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

        // Ensure HOME/TS_LOGS_DIR even if Application path was skipped in tests.
        TsnetAndroidEnv.apply(applicationContext)

        if (!stillOwner(gen)) return

        if (!StorageAccess.hasAllFilesAccess()) {
            if (stillOwner(gen)) {
                failAndStop(
                    "All files access is required to sync a folder. " +
                        "Grant it in system settings, then start again.",
                )
            }
            return
        }

        val user = settings.load()
        val syncPath = user.syncDir.trim()
        val statePath = user.stateDir.trim()
        // Validate without creating (isAbsoluteWritable never mkdirs).
        if (syncPath.isBlank() || !PathUtils.isAbsoluteWritable(syncPath)) {
            if (stillOwner(gen)) {
                failAndStop(
                    if (syncPath.isBlank()) {
                        "No sync folder selected — pick an absolute writable path first"
                    } else {
                        "Sync directory is not writable: $syncPath"
                    },
                )
            }
            return
        }
        if (statePath.isBlank() || !PathUtils.isAbsoluteWritable(statePath)) {
            if (stillOwner(gen)) {
                failAndStop(
                    if (statePath.isBlank()) {
                        "State directory must be an absolute writable path"
                    } else {
                        "State directory is not writable: $statePath"
                    },
                )
            }
            return
        }
        // Create only after validation commits.
        val syncDirFile = PathUtils.ensureDir(syncPath)
        val stateDirFile = PathUtils.ensureDir(statePath)
        if (!syncDirFile.isDirectory || !syncDirFile.canWrite()) {
            if (stillOwner(gen)) {
                failAndStop("Sync directory is not writable: ${syncDirFile.absolutePath}")
            }
            return
        }
        if (!stateDirFile.isDirectory || !stateDirFile.canWrite()) {
            if (stillOwner(gen)) {
                failAndStop("State directory is not writable: ${stateDirFile.absolutePath}")
            }
            return
        }

        if (!stillOwner(gen)) return

        // Required on Android API 30+: feed interfaces from Java so tsnet does
        // not call Go net.Interfaces() (netlink → permission denied).
        try {
            val snap = AndroidNetworkBridge.collectAndPublish(this, notify = false)
            if (snap.interfaceCount == 0) {
                if (stillOwner(gen)) {
                    failAndStop(
                        "No network interfaces available to share with tsnet. " +
                            "Check connectivity and INTERNET permission.",
                    )
                }
                return
            }
            TailsyncRuntime.appendLog(
                "Network snapshot: ${snap.interfaceCount} interface(s), " +
                    "default=${snap.defaultInterface.ifBlank { "none" }}",
            )
        } catch (e: Exception) {
            if (stillOwner(gen)) {
                failAndStop("Failed to publish network interfaces: ${safeErrorMessage(e)}")
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
            // Android always uses embedded tsnet (host LocalAPI / plain TCP
            // are desktop or test-only and are not exposed in the UI).
            netMode = "tsnet"
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

        // Poll during Start as well: StatusJSON may expose needs_login / auth_url
        // while interactive browser login is in progress (Start still blocked).
        startStatusPolling(created, gen)

        try {
            // Blocks until listening or failure; may be aborted by concurrent stop().
            // When AuthKey is empty and there is no tsnet state, an "auth" event
            // is emitted with a login URL; Start remains blocked until login ends.
            created.start()
        } catch (e: Exception) {
            disposeNode(created, "start-failed")
            ownedNode.compareAndSet(created, null)
            val msg = safeErrorMessage(e)
            // Mobile returns "start aborted" when Stop wins the race mid-start.
            // That is a normal cancel path, not a sticky user-facing failure.
            if (isStartAborted(msg)) {
                TailsyncRuntime.appendLog("Start aborted")
                if (stillOwner(gen)) {
                    // Ownership retained but Start aborted: tear service down
                    // without branding it as an error.
                    cancelStartQuietly()
                }
                return
            }
            if (stillOwner(gen)) {
                failAndStop("Start failed: $msg")
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
        clearAuthLoginState()
        TailsyncRuntime.appendLog("Node started")
        refreshStatus(created, gen)
        updateNotification(getString(R.string.notification_text_running))
        // Polling already running from pre-start; ensure it continues for this gen.
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
        // Auth event: parse with pure helper, post open to main (never block Go).
        AuthSignals.parseAuthEvent(eventJson)?.let { auth ->
            val status = AuthSignals.parseAuthStatus(TailsyncRuntime.statusJson.value)
            val url = AuthSignals.resolveAuthUrl(auth.url, status) ?: auth.url
            TailsyncRuntime.setAuthLogin(url)
            TailsyncRuntime.appendLog("Tailscale browser login required")
            updateNotification(getString(R.string.notification_text_login))
            scheduleAuthOpenOnce(url)
            return
        }
        try {
            val obj = JSONObject(eventJson)
            when (obj.optString("type")) {
                "status" -> {
                    // Status events are {type, running, msg} only — no phase.
                    // Phase is authoritative from StatusJSON polling; do not
                    // infer starting/stopping/idle from the running flag alone
                    // (IsRunning semantics differ from StatusJSON.running).
                    val running = obj.optBoolean("running", false)
                    TailsyncRuntime.setNodeRunning(running)
                    val msg = obj.optString("msg", "")
                    if (msg.isNotBlank()) {
                        TailsyncRuntime.appendLog(msg)
                    }
                    if (running) {
                        clearAuthLoginState()
                        updateNotification(getString(R.string.notification_text_running))
                    }
                }
                "error" -> {
                    val msg = obj.optString("msg", "unknown error")
                    val phase = obj.optString("phase", "")
                    val redacted = LogRedactor.redact(msg)
                    TailsyncRuntime.appendLog(
                        "error${if (phase.isNotBlank()) " ($phase)" else ""}: $redacted",
                    )
                    // "start aborted" is concurrent Stop during Start — not a
                    // sticky failure to show in the error banner.
                    if (!isStartAborted(redacted)) {
                        TailsyncRuntime.setLastError(redacted)
                        updateNotification(getString(R.string.notification_text_error))
                    }
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
            // Prefer StatusJSON.phase (idle|starting|running|stopping).
            // Do not fall back to isRunning → "running": IsRunning is true for
            // starting/stopping as well, which would mislabel the UI.
            val phase = obj.optString("phase", "")
            if (phase.isNotBlank()) {
                TailsyncRuntime.setPhase(phase)
            }
            // StatusJSON.running is true only while serving after a successful Start.
            val running = obj.optBoolean("running", false)
            TailsyncRuntime.setNodeRunning(running)

            val auth = AuthSignals.parseAuthStatus(json)
            TailsyncRuntime.applyAuthStatus(auth, running = running)
            if (auth.needsLogin) {
                updateNotification(getString(R.string.notification_text_login))
                // Status URL if present; else URL already held from an auth event.
                val url = AuthSignals.resolveAuthUrl(eventUrl = null, status = auth)
                    ?: TailsyncRuntime.authUrl.value
                if (!url.isNullOrBlank()) {
                    scheduleAuthOpenOnce(url)
                }
            } else if (running) {
                updateNotification(getString(R.string.notification_text_running))
            }
        } catch (_: Exception) {
            // Status is best-effort for UI.
        }
    }

    /**
     * Posts a once-per-URL open on Main only when [AuthBrowser] would actually
     * launch (avoids scheduling a Main coroutine every status poll).
     */
    private fun scheduleAuthOpenOnce(url: String) {
        if (!AuthBrowser.shouldOpenOnce(url)) return
        lifecycleScope.launch(Dispatchers.Main) {
            AuthBrowser.openOnce(applicationContext, url)
        }
    }

    /** Clears login UI state and browser once-open tracking together. */
    private fun clearAuthLoginState() {
        TailsyncRuntime.clearAuthLogin()
        AuthBrowser.clearAutoOpenTracking()
    }

    /**
     * Tear down after a cancelled/aborted start without treating it as a
     * user-visible error (no sticky lastError, keep notification neutral).
     */
    private suspend fun cancelStartQuietly() {
        restartRequested = false
        // User already cleared wanted on toggle-off; if still true, leave it —
        // only failAndStop clears wanted on real failures.
        TailsyncRuntime.setPhase("idle")
        TailsyncRuntime.setNodeRunning(false)
        withContext(Dispatchers.Main) {
            stopSelfGracefully()
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
        clearAuthLoginState()
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

    /** Mobile engine message when Stop aborts an in-flight Start. */
    private fun isStartAborted(message: String): Boolean {
        val m = message.lowercase()
        return m.contains("start aborted") || m == "start aborted"
    }

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
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.service_stop_action), stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        // When interactive login is required and a URL is known, offer a direct
        // "Sign in" action (explicit open; not once-only).
        val authUrl = if (TailsyncRuntime.needsLogin.value) {
            TailsyncRuntime.authUrl.value?.trim()?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
        if (authUrl != null) {
            val openAuth = PendingIntent.getService(
                this,
                2,
                Intent(this, TailsyncService::class.java)
                    .setAction(ACTION_OPEN_AUTH)
                    .putExtra(EXTRA_AUTH_URL, authUrl),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, getString(R.string.notification_sign_in_action), openAuth)
        }
        return builder.build()
    }

    private fun updateNotification(contentText: String) {
        val authUrl = if (TailsyncRuntime.needsLogin.value) {
            TailsyncRuntime.authUrl.value?.trim()?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
        // Skip rebuild when neither body text nor sign-in action URL changed.
        if (contentText == lastNotificationText && authUrl == lastNotificationAuthUrl) {
            return
        }
        lastNotificationText = contentText
        lastNotificationAuthUrl = authUrl
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
        const val ACTION_OPEN_AUTH = "dev.deedles.tailsync.action.OPEN_AUTH"
        const val EXTRA_AUTH_URL = "dev.deedles.tailsync.extra.AUTH_URL"

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
