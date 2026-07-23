package dev.deedles.tailsync

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainViewModel(
    private val settingsRepo: SettingsStore,
    private val serviceGateway: ServiceGateway,
    private val isPathWritable: (String) -> Boolean = PathUtils::isAbsoluteWritable,
    private val ensureDir: (String) -> Unit = { PathUtils.ensureDir(it) },
    private val hasAllFilesAccess: () -> Boolean = StorageAccess::hasAllFilesAccess,
    private val treeUriToPath: (Uri) -> String? = PathUtils::treeUriToAbsolutePath,
    private val pendingStartTimeoutMs: Long = PENDING_START_TIMEOUT_MS,
) : ViewModel() {

    private val _form = MutableStateFlow(settingsRepo.load().toFormState())
    val form: StateFlow<FormState> = _form

    private val _saveMessage = MutableStateFlow<String?>(null)
    val saveMessage: StateFlow<String?> = _saveMessage

    private val _hasAllFilesAccess = MutableStateFlow(hasAllFilesAccess())

    private val _pathHintKind = MutableStateFlow(
        if (_hasAllFilesAccess.value) PathHintKind.DefaultGranted else PathHintKind.DefaultDenied,
    )
    private val _pathHint = MutableStateFlow<String?>(
        pathHintText(_pathHintKind.value),
    )
    val pathHint: StateFlow<String?> = _pathHint

    /** User intent for the service switch while start/stop is in flight. */
    private val _pendingEnabled = MutableStateFlow<Boolean?>(null)

    /** Exposed for unit tests. */
    internal val pendingEnabled: StateFlow<Boolean?> = _pendingEnabled

    /** Exposed for unit tests. */
    internal val pathHintKind: StateFlow<PathHintKind> = _pathHintKind

    private var pendingTimeoutJob: Job? = null

    private val _hasStoredAuthKey = MutableStateFlow(settingsRepo.hasAuthKey())

    private data class RuntimeSlice(
        val serviceRunning: Boolean,
        val nodeRunning: Boolean,
        val phase: String,
        val statusJson: String?,
        val lastError: String?,
        val engineVersion: String?,
        val logLines: List<String>,
        val needsLogin: Boolean,
        val authUrl: String?,
    )

    init {
        if (settingsRepo.consumeAuthKeyResetNotice()) {
            _saveMessage.value =
                "Secure storage was reset — optional auth key was cleared. " +
                    "Use browser login or re-enter a key."
        }
        // After process death: if user wanted the service, request start (sets pending).
        // Do not set pending alone without a start attempt — that hard-locks the switch.
        // Application may have cleared service_wanted after an incomplete native crash.
        if (settingsRepo.isServiceWanted() && !TailsyncRuntime.serviceRunning.value) {
            requestStart(persistFormFirst = false)
        }
        // Clear / reconcile pending as runtime evolves.
        combine(
            TailsyncRuntime.serviceRunning,
            TailsyncRuntime.phase,
            TailsyncRuntime.lastError,
            _pendingEnabled,
        ) { running, phase, lastError, pending ->
            PendingReconcile(running, phase, lastError, pending)
        }.onEach { reconcilePending(it) }.launchIn(viewModelScope)
    }

    private data class PendingReconcile(
        val serviceRunning: Boolean,
        val phase: String,
        val lastError: String?,
        val pending: Boolean?,
    )

    private fun reconcilePending(r: PendingReconcile) {
        val pending = r.pending ?: return
        // Intent acknowledged: service matches desired on/off.
        if (pending == r.serviceRunning) {
            clearPending(cancelTimeout = true)
            return
        }
        // Pending start abandoned: idle, not running, and either errored or
        // serviceWanted was cleared by the service fail path.
        if (pending &&
            !r.serviceRunning &&
            r.phase == "idle" &&
            (r.lastError != null || !settingsRepo.isServiceWanted())
        ) {
            abandonPendingStart(message = null)
        }
    }

    private fun clearPending(cancelTimeout: Boolean) {
        if (cancelTimeout) {
            pendingTimeoutJob?.cancel()
            pendingTimeoutJob = null
        }
        _pendingEnabled.value = null
    }

    private fun abandonPendingStart(message: String?) {
        pendingTimeoutJob?.cancel()
        pendingTimeoutJob = null
        _pendingEnabled.value = null
        if (settingsRepo.isServiceWanted()) {
            settingsRepo.setServiceWanted(false)
        }
        if (message != null) {
            TailsyncRuntime.setLastError(message)
            _saveMessage.value = message
        }
    }

    private fun armPendingStartTimeout() {
        pendingTimeoutJob?.cancel()
        pendingTimeoutJob = viewModelScope.launch {
            delay(pendingStartTimeoutMs)
            if (_pendingEnabled.value == true && !TailsyncRuntime.serviceRunning.value) {
                abandonPendingStart(
                    message = "Start did not complete — toggle to retry.",
                )
                // Best-effort stop in case a partial service instance is alive.
                serviceGateway.stop()
            }
        }
    }

    private val runtimeSlice = combine(
        TailsyncRuntime.serviceRunning,
        TailsyncRuntime.nodeRunning,
        TailsyncRuntime.phase,
        TailsyncRuntime.statusJson,
        TailsyncRuntime.lastError,
    ) { serviceRunning, nodeRunning, phase, statusJson, lastError ->
        RuntimeSlice(
            serviceRunning = serviceRunning,
            nodeRunning = nodeRunning,
            phase = phase,
            statusJson = statusJson,
            lastError = lastError,
            engineVersion = TailsyncRuntime.engineVersion.value,
            logLines = TailsyncRuntime.logLines.value,
            needsLogin = TailsyncRuntime.needsLogin.value,
            authUrl = TailsyncRuntime.authUrl.value,
        )
    }

    private val runtimeFull = combine(
        runtimeSlice,
        TailsyncRuntime.engineVersion,
        TailsyncRuntime.logLines,
        TailsyncRuntime.needsLogin,
        TailsyncRuntime.authUrl,
    ) { slice, version, logs, needsLogin, authUrl ->
        slice.copy(
            engineVersion = version,
            logLines = logs,
            needsLogin = needsLogin,
            authUrl = authUrl,
        )
    }

    // Three-way combine avoids the awkward 5+1 nested pattern for all-files access.
    private val formSlice = combine(
        _form,
        _saveMessage,
        _pathHint,
        _pendingEnabled,
        _hasStoredAuthKey,
    ) { form, saveMessage, pathHint, pendingEnabled, hasStoredAuthKey ->
        FormSlice(form, saveMessage, pathHint, pendingEnabled, hasStoredAuthKey)
    }

    private data class FormSlice(
        val form: FormState,
        val saveMessage: String?,
        val pathHint: String?,
        val pendingEnabled: Boolean?,
        val hasStoredAuthKey: Boolean,
    )

    val uiState: StateFlow<UiState> = combine(
        runtimeFull,
        formSlice,
        _hasAllFilesAccess,
    ) { runtime, form, allFiles ->
        val pending = form.pendingEnabled
        val switchChecked = pending ?: runtime.serviceRunning
        val formEnabled = computeFormEnabled(
            serviceRunning = runtime.serviceRunning,
            phase = runtime.phase,
            pending = pending,
        )
        UiState(
            serviceRunning = runtime.serviceRunning,
            nodeRunning = runtime.nodeRunning,
            phase = runtime.phase,
            statusJson = runtime.statusJson,
            lastError = runtime.lastError,
            engineVersion = runtime.engineVersion,
            logLines = runtime.logLines,
            form = form.form,
            saveMessage = form.saveMessage,
            pathHint = form.pathHint,
            statusSummary = summarizeStatus(runtime.statusJson),
            switchChecked = switchChecked,
            switchEnabled = true,
            formEnabled = formEnabled,
            hasStoredAuthKey = form.hasStoredAuthKey,
            hasAllFilesAccess = allFiles,
            canPickDirectory = formEnabled && allFiles,
            needsLogin = runtime.needsLogin,
            authUrl = runtime.authUrl,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        initialUiState(),
    )

    private fun initialUiState(): UiState {
        val running = TailsyncRuntime.serviceRunning.value
        val phase = TailsyncRuntime.phase.value
        val pending = _pendingEnabled.value
        val allFiles = _hasAllFilesAccess.value
        val formEnabled = computeFormEnabled(running, phase, pending)
        return UiState(
            serviceRunning = running,
            nodeRunning = TailsyncRuntime.nodeRunning.value,
            phase = phase,
            statusJson = TailsyncRuntime.statusJson.value,
            lastError = TailsyncRuntime.lastError.value,
            engineVersion = TailsyncRuntime.engineVersion.value,
            logLines = TailsyncRuntime.logLines.value,
            form = _form.value,
            saveMessage = _saveMessage.value,
            pathHint = _pathHint.value,
            statusSummary = summarizeStatus(TailsyncRuntime.statusJson.value),
            switchChecked = pending ?: running,
            switchEnabled = true,
            formEnabled = formEnabled,
            hasStoredAuthKey = _hasStoredAuthKey.value,
            hasAllFilesAccess = allFiles,
            canPickDirectory = formEnabled && allFiles,
            needsLogin = TailsyncRuntime.needsLogin.value,
            authUrl = TailsyncRuntime.authUrl.value,
        )
    }

    private fun setPathHint(kind: PathHintKind, detail: String? = null) {
        _pathHintKind.value = kind
        _pathHint.value = pathHintText(kind, detail)
    }

    /**
     * Re-check all-files access (e.g. after returning from system settings).
     * If access is lost while the service is wanted/running, stop and clear wanted.
     */
    fun refreshStoragePermission() {
        val previouslyGranted = _hasAllFilesAccess.value
        val granted = hasAllFilesAccess()
        _hasAllFilesAccess.value = granted
        if (!granted) {
            setPathHint(PathHintKind.DefaultDenied)
            val active = previouslyGranted ||
                TailsyncRuntime.serviceRunning.value ||
                settingsRepo.isServiceWanted() ||
                _pendingEnabled.value == true
            if (active) {
                stopDueToMissingAllFilesAccess()
            }
        } else {
            // Replace only default/permission hints; keep Resolved / ResolveFailed.
            when (_pathHintKind.value) {
                PathHintKind.DefaultDenied,
                PathHintKind.DefaultGranted,
                PathHintKind.NeedAccessToPick,
                -> setPathHint(PathHintKind.DefaultGranted)
                PathHintKind.Resolved,
                PathHintKind.ResolveFailed,
                -> Unit
            }
        }
    }

    private fun stopDueToMissingAllFilesAccess() {
        val needStop = settingsRepo.isServiceWanted() ||
            TailsyncRuntime.serviceRunning.value ||
            _pendingEnabled.value == true
        if (!needStop) return
        val msg =
            "All files access was revoked — sync stopped. Grant access again to continue."
        pendingTimeoutJob?.cancel()
        pendingTimeoutJob = null
        settingsRepo.setServiceWanted(false)
        _pendingEnabled.value = false
        TailsyncRuntime.setLastError(msg)
        _saveMessage.value = msg
        serviceGateway.stop()
        if (!TailsyncRuntime.serviceRunning.value) {
            _pendingEnabled.value = null
        }
    }

    fun updateForm(transform: (FormState) -> FormState) {
        if (!uiState.value.formEnabled) return
        _form.update(transform)
        _saveMessage.value = null
    }

    fun saveSettings() {
        if (!uiState.value.formEnabled) {
            _saveMessage.value = "Stop the service before changing settings"
            return
        }
        persistForm(showMessage = true)
    }

    /**
     * Writes the current form to disk without checking [UiState.formEnabled].
     * Used on service enable so latest edits are visible to the service even
     * when pending locks the form.
     */
    internal fun persistForm(showMessage: Boolean): Boolean {
        val form = _form.value
        val port = SettingsValidation.clampPort(form.port)
        val validated = form.copy(
            port = if (port == 0) "" else port.toString(),
            scanIntervalMs = form.scanIntervalMs.filter { it.isDigit() },
            syncIntervalMs = form.syncIntervalMs.filter { it.isDigit() },
            blockSize = form.blockSize.filter { it.isDigit() },
        )
        _form.value = validated
        settingsRepo.save(validated.toUserSettings(settingsRepo))
        _hasStoredAuthKey.value = settingsRepo.hasAuthKey()
        if (settingsRepo.consumeAuthKeyResetNotice()) {
            _saveMessage.value =
                "Secure storage was reset — optional auth key was cleared. " +
                    "Use browser login or re-enter a key."
            return true
        }
        if (showMessage) {
            _saveMessage.value = "Settings saved"
        }
        return true
    }

    fun setServiceEnabled(enabled: Boolean) {
        if (enabled) {
            requestStart(persistFormFirst = true)
        } else {
            requestStop()
        }
    }

    private fun requestStart(persistFormFirst: Boolean) {
        if (persistFormFirst) {
            // Must persist BEFORE pending is set (pending locks formEnabled).
            persistForm(showMessage = false)
        }

        if (!hasAllFilesAccess()) {
            _hasAllFilesAccess.value = false
            setPathHint(PathHintKind.DefaultDenied)
            abandonPendingStart(
                message = "All files access is required to sync a folder. " +
                    "Grant it in system settings, then try again.",
            )
            return
        }
        _hasAllFilesAccess.value = true

        val sync = _form.value.syncDir.trim()
        val state = _form.value.stateDir.trim()
        // Validation only — does not create directories on shared storage.
        if (sync.isBlank() || !isPathWritable(sync)) {
            abandonPendingStart(
                message = "Pick a sync folder (absolute writable path required)",
            )
            return
        }
        if (state.isBlank() || !isPathWritable(state)) {
            abandonPendingStart(message = "State directory must be an absolute writable path")
            return
        }

        settingsRepo.setServiceWanted(true)
        _pendingEnabled.value = true
        TailsyncRuntime.setLastError(null)
        armPendingStartTimeout()
        serviceGateway.start()
    }

    private fun requestStop() {
        pendingTimeoutJob?.cancel()
        pendingTimeoutJob = null
        settingsRepo.setServiceWanted(false)
        _pendingEnabled.value = false
        serviceGateway.stop()
        // If the service was never up, clear pending immediately.
        if (!TailsyncRuntime.serviceRunning.value) {
            _pendingEnabled.value = null
        }
    }

    /**
     * Handles a SAF tree selection. Requires all-files access so the tree can
     * resolve to an absolute path. Never falls back to app-private storage.
     */
    fun onTreePicked(uri: Uri) {
        applyTreePick(uriString = uri.toString(), resolved = treeUriToPath(uri))
    }

    /**
     * Testable tree-pick entry that avoids Android [Uri] plumbing on the JVM.
     * Production [onTreePicked] delegates here after resolving the URI.
     */
    internal fun applyTreePick(uriString: String, resolved: String?) {
        if (!uiState.value.formEnabled) return
        if (!hasAllFilesAccess()) {
            _hasAllFilesAccess.value = false
            setPathHint(PathHintKind.NeedAccessToPick)
            _saveMessage.value = "Grant all files access first"
            return
        }
        if (resolved != null && isPathWritable(resolved)) {
            ensureDir(resolved)
            _form.update {
                it.copy(
                    syncDir = resolved,
                    treeUri = uriString,
                )
            }
            setPathHint(PathHintKind.Resolved, detail = resolved)
            // Persist immediately so process death does not lose the pick.
            persistForm(showMessage = false)
            _saveMessage.value = null
        } else {
            // Do not change syncDir / do not fall back to app-private.
            setPathHint(PathHintKind.ResolveFailed, detail = resolved)
            _saveMessage.value = "Folder could not be used as a sync path"
        }
    }

    fun clearSaveMessage() {
        _saveMessage.value = null
    }

    /** Simulates service-side failure acknowledgment for unit tests. */
    internal fun onServiceFailedForTest(message: String) {
        settingsRepo.setServiceWanted(false)
        TailsyncRuntime.setLastError(message)
        TailsyncRuntime.setPhase("idle")
        TailsyncRuntime.setServiceRunning(false)
        TailsyncRuntime.setNodeRunning(false)
        reconcilePending(
            PendingReconcile(
                serviceRunning = false,
                phase = "idle",
                lastError = message,
                pending = _pendingEnabled.value,
            ),
        )
    }

    /** Test hook: ViewModel.onCleared is protected. */
    internal fun onClearedForTest() {
        onCleared()
    }

    private fun summarizeStatus(json: String?): StatusSummary? {
        if (json.isNullOrBlank()) return null
        return try {
            val o = JSONObject(json)
            val auth = AuthSignals.parseAuthStatus(json)
            StatusSummary(
                running = o.optBoolean("running", false),
                phase = o.optString("phase", "unknown"),
                dir = o.optString("dir", ""),
                stateDir = o.optString("state_dir", ""),
                hostname = o.optString("hostname", ""),
                port = o.optInt("port", 0),
                service = o.optString("service", ""),
                peers = o.optString("peers", ""),
                scanIntervalMs = o.optLong("scan_interval_ms", 0L),
                syncIntervalMs = o.optLong("sync_interval_ms", 0L),
                blockSize = o.optInt("block_size", 0),
                version = o.optString("version", ""),
                needsLogin = auth.needsLogin,
                authUrl = auth.authUrl,
            )
        } catch (_: Exception) {
            null
        }
    }

    class Factory(private val app: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                val settings = TailsyncApplication.settingsOf(app)
                val gateway = object : ServiceGateway {
                    override fun start() = TailsyncService.start(app)
                    override fun stop() = TailsyncService.stop(app)
                }
                return MainViewModel(settings, gateway) as T
            }
            throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        }
    }

    companion object {
        /** How long a pending start may sit without serviceRunning before abandon. */
        const val PENDING_START_TIMEOUT_MS: Long = 60_000L

        fun computeFormEnabled(
            serviceRunning: Boolean,
            phase: String,
            pending: Boolean?,
        ): Boolean {
            if (serviceRunning) return false
            if (phase == "starting" || phase == "stopping") return false
            if (pending == true) return false
            return true
        }

        fun pathHintText(kind: PathHintKind, detail: String? = null): String = when (kind) {
            PathHintKind.DefaultGranted ->
                "Pick a folder to sync. Paths resolve via all-files access " +
                    "(primary storage and common secondary volumes). " +
                    "State/index stays under app-private storage."
            PathHintKind.DefaultDenied ->
                "All files access is required to sync arbitrary folders. " +
                    "Grant it in system settings, then pick a folder. " +
                    "App-private storage is not offered as a sync root."
            PathHintKind.NeedAccessToPick ->
                "All files access is required before picking a folder. " +
                    "Grant it, then pick again."
            PathHintKind.Resolved ->
                "Using resolved path: ${detail.orEmpty()}"
            PathHintKind.ResolveFailed ->
                "Could not resolve the picked folder to a writable filesystem path" +
                    (if (!detail.isNullOrBlank()) " ($detail)" else "") +
                    ". Prefer a folder on primary storage (e.g. Downloads) or " +
                    "another volume under /storage. All-files access must stay granted."
        }
    }
}

/** Provenance for the directory path hint (avoids fragile substring matching). */
enum class PathHintKind {
    DefaultDenied,
    DefaultGranted,
    NeedAccessToPick,
    Resolved,
    ResolveFailed,
}

data class FormState(
    val syncDir: String = "",
    val stateDir: String = "",
    val hostname: String = "",
    val authKey: String = "",
    val port: String = "",
    val peers: String = "",
    val serviceName: String = "",
    val scanIntervalMs: String = "",
    val syncIntervalMs: String = "",
    val blockSize: String = "",
    val treeUri: String? = null,
)

data class StatusSummary(
    val running: Boolean,
    val phase: String,
    val dir: String,
    val stateDir: String,
    val hostname: String,
    val port: Int,
    val service: String,
    val peers: String,
    val scanIntervalMs: Long,
    val syncIntervalMs: Long,
    val blockSize: Int,
    val version: String,
    val needsLogin: Boolean = false,
    val authUrl: String? = null,
)

data class UiState(
    val serviceRunning: Boolean,
    val nodeRunning: Boolean,
    val phase: String,
    val statusJson: String?,
    val lastError: String?,
    val engineVersion: String?,
    val logLines: List<String>,
    val form: FormState,
    val saveMessage: String?,
    val pathHint: String?,
    val statusSummary: StatusSummary?,
    val switchChecked: Boolean,
    val switchEnabled: Boolean,
    val formEnabled: Boolean,
    val hasStoredAuthKey: Boolean,
    val hasAllFilesAccess: Boolean,
    val canPickDirectory: Boolean,
    val needsLogin: Boolean = false,
    val authUrl: String? = null,
)

private fun UserSettings.toFormState(): FormState = FormState(
    syncDir = syncDir,
    stateDir = stateDir,
    hostname = hostname,
    authKey = authKey,
    port = if (port == 0) "" else port.toString(),
    peers = peers,
    serviceName = serviceName,
    scanIntervalMs = if (scanIntervalMs == 0L) "" else scanIntervalMs.toString(),
    syncIntervalMs = if (syncIntervalMs == 0L) "" else syncIntervalMs.toString(),
    blockSize = if (blockSize == 0) "" else blockSize.toString(),
    treeUri = treeUri,
)

private fun FormState.toUserSettings(repo: SettingsStore): UserSettings = UserSettings(
    // Never invent an app-private sync root; blank means user has not chosen yet.
    syncDir = syncDir.trim(),
    stateDir = stateDir.trim().ifBlank { repo.defaultStateDir().absolutePath },
    hostname = hostname.trim(),
    authKey = authKey.trim(),
    port = SettingsValidation.clampPort(port),
    peers = peers.trim(),
    serviceName = serviceName.trim(),
    scanIntervalMs = SettingsValidation.nonNegativeLong(scanIntervalMs),
    syncIntervalMs = SettingsValidation.nonNegativeLong(syncIntervalMs),
    blockSize = SettingsValidation.nonNegativeInt(blockSize),
    treeUri = treeUri,
)
