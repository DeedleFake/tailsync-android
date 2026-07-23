package dev.deedles.tailsync

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-scoped runtime state shared between [TailsyncService] and the UI.
 * Events from Go arrive on a background thread; the service posts updates here.
 */
object TailsyncRuntime {
    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    private val _nodeRunning = MutableStateFlow(false)
    val nodeRunning: StateFlow<Boolean> = _nodeRunning.asStateFlow()

    private val _phase = MutableStateFlow("idle")
    val phase: StateFlow<String> = _phase.asStateFlow()

    private val _statusJson = MutableStateFlow<String?>(null)
    val statusJson: StateFlow<String?> = _statusJson.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _engineVersion = MutableStateFlow<String?>(null)
    val engineVersion: StateFlow<String?> = _engineVersion.asStateFlow()

    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    /** True while the engine reports interactive Tailscale login is needed. */
    private val _needsLogin = MutableStateFlow(false)
    val needsLogin: StateFlow<Boolean> = _needsLogin.asStateFlow()

    /** Browser login URL from an auth event or StatusJSON.auth_url. */
    private val _authUrl = MutableStateFlow<String?>(null)
    val authUrl: StateFlow<String?> = _authUrl.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 128)
    val events: SharedFlow<String> = _events.asSharedFlow()

    fun setServiceRunning(running: Boolean) {
        _serviceRunning.value = running
        if (!running) {
            _nodeRunning.value = false
            if (_phase.value != "stopping") {
                _phase.value = "idle"
            }
            clearAuthLogin()
        }
    }

    fun setNodeRunning(running: Boolean) {
        _nodeRunning.value = running
    }

    fun setPhase(phase: String) {
        _phase.value = phase
    }

    fun setStatusJson(json: String?) {
        _statusJson.value = json
    }

    fun setLastError(message: String?) {
        _lastError.value = message?.let { LogRedactor.redact(it) }
    }

    fun setEngineVersion(version: String?) {
        _engineVersion.value = version
    }

    /**
     * Records that interactive login is required and stores [url] when non-blank.
     * Blank url keeps any previously known auth URL while marking needsLogin.
     */
    fun setAuthLogin(url: String?) {
        val trimmed = url?.trim()?.takeIf { it.isNotEmpty() }
        _needsLogin.value = true
        if (trimmed != null) {
            _authUrl.value = trimmed
        }
    }

    /**
     * Clears interactive-login UI state. Does not touch browser auto-open
     * tracking — callers that open URLs ([TailsyncService], UI) clear that
     * via [AuthBrowser.clearAutoOpenTracking] so this object stays Android-free.
     */
    fun clearAuthLogin() {
        _needsLogin.value = false
        _authUrl.value = null
    }

    /**
     * Applies StatusJSON needs_login / auth_url.
     * Clears login UI only when [running] (serving after successful Start), so a
     * poll that omits needs_login during start does not wipe an auth event URL.
     */
    fun applyAuthStatus(status: AuthStatusFields, running: Boolean = false) {
        if (status.needsLogin) {
            setAuthLogin(status.authUrl)
        } else if (running) {
            clearAuthLogin()
        }
    }

    fun clearLogs() {
        _logLines.value = emptyList()
    }

    fun appendLog(line: String) {
        val trimmed = LogRedactor.redact(line.trim())
        if (trimmed.isEmpty()) return
        _logLines.update { (it + trimmed).takeLast(MAX_LOG_LINES) }
    }

    fun emitEvent(eventJson: String) {
        _events.tryEmit(eventJson)
    }

    fun markIdle() {
        _nodeRunning.value = false
        _phase.value = "idle"
        _statusJson.value = null
        clearAuthLogin()
    }

    private const val MAX_LOG_LINES = 100
}
