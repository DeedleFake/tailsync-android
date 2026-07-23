package dev.deedles.tailsync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.deedles.tailsync.AuthBrowser
import dev.deedles.tailsync.FormState
import dev.deedles.tailsync.MainViewModel
import dev.deedles.tailsync.StatusSummary
import dev.deedles.tailsync.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TailsyncScreen(
    viewModel: MainViewModel,
    onPickDirectory: () -> Unit,
    onGrantAllFilesAccess: () -> Unit,
    /** Explicit user action — always open (not once-only). */
    onOpenAuthUrl: (String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Auto-open once per distinct URL when login is required (service may also open).
    LaunchedEffect(state.authUrl, state.needsLogin) {
        val url = state.authUrl
        if (state.needsLogin && !url.isNullOrBlank()) {
            AuthBrowser.openOnce(context, url)
        } else if (!state.needsLogin) {
            // Runtime is UI-free; clear once-open tracking when login ends.
            AuthBrowser.clearAutoOpenTracking()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tailsync") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!state.hasAllFilesAccess) {
                AllFilesAccessCard(onGrant = onGrantAllFilesAccess)
            }
            ServiceCard(state = state, onToggle = viewModel::setServiceEnabled)
            if (!state.formEnabled) {
                Text(
                    when {
                        state.phase == "starting" ||
                            (state.switchChecked && !state.serviceRunning) ->
                            "Configuration is locked while the service is starting." 
                        state.phase == "stopping" ->
                            "Configuration is locked while the service is stopping."
                        else ->
                            "Configuration is locked while the service is running. " +
                                "Stop the service to edit settings (restart required for changes)."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            StatusCard(state = state)
            AuthCard(
                form = state.form,
                enabled = state.formEnabled,
                hasStoredAuthKey = state.hasStoredAuthKey,
                needsLogin = state.needsLogin,
                authUrl = state.authUrl,
                onChange = viewModel::updateForm,
                onOpenAuthUrl = onOpenAuthUrl,
            )
            DirectoryCard(
                form = state.form,
                pathHint = state.pathHint,
                enabled = state.formEnabled,
                canPickDirectory = state.canPickDirectory,
                hasAllFilesAccess = state.hasAllFilesAccess,
                onChange = viewModel::updateForm,
                onPickDirectory = onPickDirectory,
                onGrantAllFilesAccess = onGrantAllFilesAccess,
            )
            ConfigCard(
                form = state.form,
                enabled = state.formEnabled,
                onChange = viewModel::updateForm,
            )
            Button(
                onClick = viewModel::saveSettings,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.formEnabled,
            ) {
                Text(if (state.formEnabled) "Save settings" else "Stop service to edit & save")
            }
            state.saveMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.primary)
            }
            LogsCard(lines = state.logLines)
            state.engineVersion?.let {
                Text(
                    "Engine: $it",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AllFilesAccessCard(onGrant: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "All files access required",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Text(
                "Tailsync needs all-files access so it can sync any folder you choose " +
                    "using real filesystem paths (required by the sync engine). " +
                    "Without this permission, folder picks cannot be used as a sync root. " +
                    "App-private storage is not a sync destination.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Button(
                onClick = onGrant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Grant all files access")
            }
        }
    }
}

@Composable
private fun ServiceCard(
    state: UiState,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Sync service", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = when {
                        state.needsLogin -> "Waiting for Tailscale sign-in…"
                        state.phase == "starting" -> "Starting…"
                        state.phase == "stopping" -> "Stopping…"
                        state.nodeRunning -> "Node running (${state.phase})"
                        state.serviceRunning -> "Service active (${state.phase})"
                        state.switchChecked && !state.serviceRunning -> "Starting…"
                        !state.switchChecked && state.serviceRunning -> "Stopping…"
                        else -> "Stopped"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.switchChecked,
                onCheckedChange = onToggle,
                enabled = state.switchEnabled,
            )
        }
    }
}

@Composable
private fun StatusCard(state: UiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Status", style = MaterialTheme.typography.titleMedium)
            state.lastError?.let {
                Text(
                    "Error: $it",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            // One-line status only; CTA + explanation live in AuthCard.
            if (state.needsLogin) {
                Text(
                    if (state.authUrl.isNullOrBlank()) {
                        "Login: waiting for URL…"
                    } else {
                        "Login: needs browser sign-in"
                    },
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            val summary = state.statusSummary
            if (summary != null) {
                StatusRows(summary)
            } else {
                Text(
                    "Start the service to see live status from StatusJSON / events.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusRows(summary: StatusSummary) {
    val rows = buildList {
        add("Phase" to summary.phase)
        add("Running" to summary.running.toString())
        if (summary.needsLogin) {
            add("Login" to "needs browser sign-in")
        }
        add("Dir" to summary.dir)
        add("State dir" to summary.stateDir.ifBlank { "—" })
        add("Hostname" to summary.hostname.ifBlank { "—" })
        add("Port" to summary.port.toString())
        add("Service filter" to summary.service.ifBlank { "—" })
        add("Peers" to summary.peers.ifBlank { "(discovery)" })
        add("Scan ms" to summary.scanIntervalMs.toString())
        add("Sync ms" to summary.syncIntervalMs.toString())
        add("Block size" to summary.blockSize.toString())
        add("Version" to summary.version.ifBlank { "—" })
    }
    rows.forEach { (label, value) ->
        Row(Modifier.fillMaxWidth()) {
            Text(
                label,
                modifier = Modifier.width(110.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun AuthCard(
    form: FormState,
    enabled: Boolean,
    hasStoredAuthKey: Boolean,
    needsLogin: Boolean,
    authUrl: String?,
    onChange: ((FormState) -> FormState) -> Unit,
    onOpenAuthUrl: (String) -> Unit,
) {
    var showKey by remember { mutableStateOf(false) }
    // Expand advanced if a key is already present so users see stored-key state.
    var showAuthKeySection by remember {
        mutableStateOf(hasStoredAuthKey || form.authKey.isNotBlank())
    }
    LaunchedEffect(hasStoredAuthKey, form.authKey) {
        if (hasStoredAuthKey || form.authKey.isNotBlank()) {
            showAuthKeySection = true
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Tailscale authentication", style = MaterialTheme.typography.titleMedium)
            Text(
                "On first run, start the sync service and sign in with Tailscale in the browser. " +
                    "Existing tsnet state under the state directory reconnects without a prompt. " +
                    "An auth key is optional (advanced) for unattended registration.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (needsLogin) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Sign-in required",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        Text(
                            "Complete Tailscale login in the browser. " +
                                "The sync service waits until you finish.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        val url = authUrl
                        Button(
                            onClick = { if (!url.isNullOrBlank()) onOpenAuthUrl(url) },
                            enabled = !url.isNullOrBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (url.isNullOrBlank()) {
                                    "Waiting for login URL…"
                                } else {
                                    "Sign in with Tailscale"
                                },
                            )
                        }
                    }
                }
            } else if (form.authKey.isBlank() && !hasStoredAuthKey) {
                Text(
                    "No auth key set — browser login will be used on first registration.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = form.hostname,
                onValueChange = { value -> onChange { it.copy(hostname = value) } },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                label = { Text("Hostname (tsnet)") },
                singleLine = true,
                placeholder = { Text("tailsync-phone") },
            )
            OutlinedTextField(
                value = form.stateDir,
                onValueChange = { value -> onChange { it.copy(stateDir = value) } },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                label = { Text("State directory (absolute)") },
                singleLine = true,
                supportingText = {
                    Text("tsnet state + indexes only; stays under app-private storage by default")
                },
            )

            TextButton(
                onClick = { showAuthKeySection = !showAuthKeySection },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = if (showAuthKeySection) {
                        Icons.Default.ExpandLess
                    } else {
                        Icons.Default.ExpandMore
                    },
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (showAuthKeySection) {
                        "Hide auth key option"
                    } else {
                        "Use auth key instead (advanced)"
                    },
                )
            }

            if (showAuthKeySection) {
                Text(
                    "Optional. When set, used for tsnet registration instead of browser login. " +
                        "Stored with EncryptedSharedPreferences and never logged. Leave blank " +
                        "for interactive browser sign-in.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = form.authKey,
                    onValueChange = { value -> onChange { it.copy(authKey = value) } },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                    label = { Text("Auth key (optional)") },
                    singleLine = true,
                    visualTransformation = if (showKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }, enabled = enabled) {
                            Icon(
                                imageVector = if (showKey) {
                                    Icons.Default.VisibilityOff
                                } else {
                                    Icons.Default.Visibility
                                },
                                contentDescription = if (showKey) {
                                    "Hide auth key"
                                } else {
                                    "Show auth key"
                                },
                            )
                        }
                    },
                    supportingText = {
                        when {
                            form.authKey.isNotBlank() -> Text("Key will be saved securely")
                            hasStoredAuthKey -> Text("A key is stored securely on this device")
                            else -> Text("Optional — browser login is the default")
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun DirectoryCard(
    form: FormState,
    pathHint: String?,
    enabled: Boolean,
    canPickDirectory: Boolean,
    hasAllFilesAccess: Boolean,
    onChange: ((FormState) -> FormState) -> Unit,
    onPickDirectory: () -> Unit,
    onGrantAllFilesAccess: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Sync directory", style = MaterialTheme.typography.titleMedium)
            Text(
                "Choose any folder to sync. The engine needs an absolute, writable path. " +
                    "With all-files access, picking a folder on primary storage (or a " +
                    "supported secondary volume) resolves to a real path. " +
                    "State and tsnet data stay app-private — not this folder.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = form.syncDir,
                onValueChange = { value -> onChange { it.copy(syncDir = value) } },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                label = { Text("Sync dir (absolute path)") },
                singleLine = true,
                placeholder = { Text("Pick a folder…") },
                supportingText = {
                    if (form.syncDir.isBlank()) {
                        Text("Required — no default sync folder")
                    }
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onPickDirectory,
                    enabled = canPickDirectory,
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Pick folder")
                }
                if (!hasAllFilesAccess) {
                    OutlinedButton(
                        onClick = onGrantAllFilesAccess,
                        enabled = enabled,
                    ) {
                        Text("Grant access")
                    }
                }
            }
            form.treeUri?.let {
                Text(
                    "SAF tree: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            pathHint?.let {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ConfigCard(
    form: FormState,
    enabled: Boolean,
    onChange: ((FormState) -> FormState) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Node configuration", style = MaterialTheme.typography.titleMedium)
            Text(
                "This app always uses tsnet (an embedded Tailscale node). " +
                    "Desktop host/plain modes are not available on Android.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = form.port,
                onValueChange = { value ->
                    onChange {
                        it.copy(port = value.filter(Char::isDigit).take(5))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                label = { Text("Port (0 = default 5960)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { Text("Valid range 0–65535") },
            )
            OutlinedTextField(
                value = form.serviceName,
                onValueChange = { value -> onChange { it.copy(serviceName = value) } },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                label = { Text("Service name filter") },
                singleLine = true,
                supportingText = { Text("Hostname/DNS substring filter for discovery") },
            )
            OutlinedTextField(
                value = form.peers,
                onValueChange = { value -> onChange { it.copy(peers = value) } },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                label = { Text("Peers (comma-separated host:port)") },
                singleLine = false,
                minLines = 1,
                maxLines = 3,
                supportingText = { Text("Leave empty to use discovery") },
            )
            OutlinedTextField(
                value = form.scanIntervalMs,
                onValueChange = { value ->
                    onChange { it.copy(scanIntervalMs = value.filter(Char::isDigit)) }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                label = { Text("Scan interval ms (0 = 30000)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                value = form.syncIntervalMs,
                onValueChange = { value ->
                    onChange { it.copy(syncIntervalMs = value.filter(Char::isDigit)) }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                label = { Text("Sync interval ms (0 = 45000)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                value = form.blockSize,
                onValueChange = { value ->
                    onChange { it.copy(blockSize = value.filter(Char::isDigit)) }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                label = { Text("Block size (0 = default)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
    }
}

@Composable
private fun LogsCard(lines: List<String>) {
    // Show last N lines without a nested scrollable (parent Column scrolls).
    val shown = remember(lines) { lines.takeLast(LOG_PREVIEW_LINES).asReversed() }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Recent events", style = MaterialTheme.typography.titleMedium)
            if (shown.isEmpty()) {
                Text(
                    "No events yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                shown.forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                }
            }
        }
    }
}

private const val LOG_PREVIEW_LINES = 20
