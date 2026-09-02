package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.settings.JarvisSettings
import com.example.ui.theme.JarvisCyan
import com.example.ui.theme.JarvisDeepBackground
import com.example.ui.theme.JarvisErrorRed
import com.example.ui.theme.JarvisOnlineGreen
import com.example.ui.theme.JarvisSurfaceDark
import com.example.ui.theme.JarvisThinkingAmber
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: JarvisViewModel,
    onNavigateBack: () -> Unit,
    onRequestPermission: (String) -> Unit
) {
    val context = LocalContext.current
    val currentSettings by viewModel.settings.collectAsState()
    val testState by viewModel.testState.collectAsState()

    var apiKey by remember(currentSettings) { mutableStateOf(currentSettings.nvidiaApiKey) }
    var showApiKey by remember { mutableStateOf(false) }
    var agentModel by remember(currentSettings) { mutableStateOf(currentSettings.nvidiaModel) }
    var isModelDropdownExpanded by remember { mutableStateOf(false) }

    var voiceModel by remember(currentSettings) { mutableStateOf(currentSettings.voiceModel) }
    var isVoiceDropdownExpanded by remember { mutableStateOf(false) }

    var picovoiceKey by remember(currentSettings) { mutableStateOf(currentSettings.picovoiceAccessKey) }
    var showPicovoiceKey by remember { mutableStateOf(false) }
    var wakeWordEnabled by remember(currentSettings) { mutableStateOf(currentSettings.wakeWordEnabled) }
    var wakeWordSensitivity by remember(currentSettings) { mutableFloatStateOf(currentSettings.wakeWordSensitivity) }

    var ttsSpeed by remember(currentSettings) { mutableFloatStateOf(currentSettings.ttsSpeed) }
    var ttsPitch by remember(currentSettings) { mutableFloatStateOf(currentSettings.ttsPitch) }
    var ttsVolume by remember(currentSettings) { mutableFloatStateOf(currentSettings.ttsVolume) }

    var endpointUrl by remember(currentSettings) { mutableStateOf(currentSettings.nvidiaEndpoint) }
    var timeoutSeconds by remember(currentSettings) { mutableIntStateOf(currentSettings.timeoutSeconds) }
    var debugLogging by remember(currentSettings) { mutableStateOf(currentSettings.debugLogging) }

    var showClearDialog by remember { mutableStateOf(false) }

    fun commitChanges() {
        val updated = currentSettings.copy(
            nvidiaApiKey = apiKey.trim(),
            nvidiaModel = agentModel.trim(),
            voiceModel = voiceModel.trim(),
            picovoiceAccessKey = picovoiceKey.trim(),
            wakeWordEnabled = wakeWordEnabled,
            wakeWordSensitivity = wakeWordSensitivity,
            ttsSpeed = ttsSpeed,
            ttsPitch = ttsPitch,
            ttsVolume = ttsVolume,
            nvidiaEndpoint = endpointUrl.trim(),
            timeoutSeconds = timeoutSeconds,
            debugLogging = debugLogging
        )
        viewModel.saveSettings(updated)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = JarvisDeepBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "MARK 85 OS CONFIG",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                            fontFamily = FontFamily.Monospace,
                            color = JarvisCyan
                        )
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            commitChanges()
                            onNavigateBack()
                        },
                        modifier = Modifier
                            .testTag("settings_back_button")
                            .size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Home",
                            tint = JarvisCyan
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = JarvisDeepBackground
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 1. AI PROVIDER SECTION
            SettingsSectionHeader(title = "AI PROVIDER — NVIDIA", icon = Icons.Default.Psychology)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = JarvisSurfaceDark),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        text = "Provider: NVIDIA NIM / NVIDIA API",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = JarvisCyan,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    )

                    // Secure NVIDIA API Key Input
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = {
                            apiKey = it
                            commitChanges()
                        },
                        label = { Text("NVIDIA API Key") },
                        placeholder = { Text("nvapi-...") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("nvidia_api_key_input"),
                        visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showApiKey = !showApiKey }) {
                                Icon(
                                    imageVector = if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Toggle API Key Visibility",
                                    tint = TextSecondary
                                )
                            }
                        },
                        colors = outlinedTextFieldColors()
                    )

                    // Agent Model Selector
                    ExposedDropdownMenuBox(
                        expanded = isModelDropdownExpanded,
                        onExpandedChange = { isModelDropdownExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = agentModel,
                            onValueChange = {
                                agentModel = it
                                commitChanges()
                            },
                            label = { Text("Agent / Reasoning Model") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryEditable, true)
                                .testTag("agent_model_input"),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isModelDropdownExpanded) },
                            colors = outlinedTextFieldColors()
                        )
                        ExposedDropdownMenu(
                            expanded = isModelDropdownExpanded,
                            onDismissRequest = { isModelDropdownExpanded = false }
                        ) {
                            JarvisSettings.PRESET_AGENT_MODELS.forEach { modelName ->
                                DropdownMenuItem(
                                    text = { Text(modelName) },
                                    onClick = {
                                        agentModel = modelName
                                        isModelDropdownExpanded = false
                                        commitChanges()
                                    }
                                )
                            }
                        }
                    }

                    // Voice Model Selector
                    ExposedDropdownMenuBox(
                        expanded = isVoiceDropdownExpanded,
                        onExpandedChange = { isVoiceDropdownExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = voiceModel,
                            onValueChange = {
                                voiceModel = it
                                commitChanges()
                            },
                            label = { Text("Voice Model") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryEditable, true)
                                .testTag("voice_model_input"),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isVoiceDropdownExpanded) },
                            colors = outlinedTextFieldColors()
                        )
                        ExposedDropdownMenu(
                            expanded = isVoiceDropdownExpanded,
                            onDismissRequest = { isVoiceDropdownExpanded = false }
                        ) {
                            JarvisSettings.PRESET_VOICE_MODELS.forEach { vmName ->
                                DropdownMenuItem(
                                    text = { Text(vmName) },
                                    onClick = {
                                        voiceModel = vmName
                                        isVoiceDropdownExpanded = false
                                        commitChanges()
                                    }
                                )
                            }
                        }
                    }

                    // Test API Button
                    Button(
                        onClick = {
                            commitChanges()
                            viewModel.testNvidiaConnection()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("test_api_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = JarvisCyan.copy(alpha = 0.2f),
                            contentColor = JarvisCyan
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.NetworkCheck, contentDescription = "Test API")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Test NVIDIA API Connection", fontWeight = FontWeight.Bold)
                        }
                    }

                    // Connection test state
                    when (val state = testState) {
                        is ConnectionTestState.Testing -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = JarvisCyan,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Connecting to NVIDIA NIM...", color = TextSecondary, fontSize = 12.sp)
                            }
                        }
                        is ConnectionTestState.Success -> {
                            Text(
                                text = "✓ ${state.message}",
                                color = JarvisOnlineGreen,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                        is ConnectionTestState.Error -> {
                            Text(
                                text = "✕ ${state.message}",
                                color = JarvisErrorRed,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                        ConnectionTestState.Idle -> {}
                    }
                }
            }

            // 2. WAKE WORD SECTION
            SettingsSectionHeader(title = "WAKE WORD (HEY JARVIS)", icon = Icons.Default.Hearing)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = JarvisSurfaceDark),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Enable 'Hey Jarvis' Wake Word",
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Runs locally on device in background",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                        Switch(
                            checked = wakeWordEnabled,
                            onCheckedChange = {
                                wakeWordEnabled = it
                                commitChanges()
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = JarvisCyan, checkedTrackColor = JarvisCyan.copy(alpha = 0.5f))
                        )
                    }

                    OutlinedTextField(
                        value = picovoiceKey,
                        onValueChange = {
                            picovoiceKey = it
                            commitChanges()
                        },
                        label = { Text("Picovoice Porcupine AccessKey (Optional)") },
                        placeholder = { Text("Enter AccessKey for Porcupine wake word") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (showPicovoiceKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPicovoiceKey = !showPicovoiceKey }) {
                                Icon(
                                    imageVector = if (showPicovoiceKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Toggle Key Visibility",
                                    tint = TextSecondary
                                )
                            }
                        },
                        colors = outlinedTextFieldColors()
                    )

                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Wake Word Sensitivity", color = TextPrimary, fontSize = 13.sp)
                            Text("${(wakeWordSensitivity * 100).toInt()}%", color = JarvisCyan, fontSize = 13.sp)
                        }
                        Slider(
                            value = wakeWordSensitivity,
                            onValueChange = {
                                wakeWordSensitivity = it
                                commitChanges()
                            },
                            valueRange = 0.1f..1.0f,
                            colors = SliderDefaults.colors(
                                thumbColor = JarvisCyan,
                                activeTrackColor = JarvisCyan
                            )
                        )
                    }
                }
            }

            // 3. VOICE & SPEECH SECTION
            SettingsSectionHeader(title = "VOICE & SPEECH (TTS)", icon = Icons.Default.GraphicEq)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = JarvisSurfaceDark),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Speech Rate (Speed)", color = TextPrimary, fontSize = 13.sp)
                            Text(String.format("%.1fx", ttsSpeed), color = JarvisCyan, fontSize = 13.sp)
                        }
                        Slider(
                            value = ttsSpeed,
                            onValueChange = {
                                ttsSpeed = it
                                commitChanges()
                            },
                            valueRange = 0.5f..2.0f,
                            colors = SliderDefaults.colors(thumbColor = JarvisCyan, activeTrackColor = JarvisCyan)
                        )
                    }

                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Voice Pitch", color = TextPrimary, fontSize = 13.sp)
                            Text(String.format("%.1fx", ttsPitch), color = JarvisCyan, fontSize = 13.sp)
                        }
                        Slider(
                            value = ttsPitch,
                            onValueChange = {
                                ttsPitch = it
                                commitChanges()
                            },
                            valueRange = 0.5f..2.0f,
                            colors = SliderDefaults.colors(thumbColor = JarvisCyan, activeTrackColor = JarvisCyan)
                        )
                    }
                }
            }

            // 4. PERMISSIONS SECTION
            SettingsSectionHeader(title = "ANDROID PERMISSIONS", icon = Icons.Default.Security)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = JarvisSurfaceDark),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PermissionStatusRow(
                        name = "Microphone (RECORD_AUDIO)",
                        desc = "Required for wake-word and voice commands",
                        permission = Manifest.permission.RECORD_AUDIO,
                        onRequest = { onRequestPermission(Manifest.permission.RECORD_AUDIO) }
                    )
                    PermissionStatusRow(
                        name = "Notifications (POST_NOTIFICATIONS)",
                        desc = "Required for background service status",
                        permission = Manifest.permission.POST_NOTIFICATIONS,
                        onRequest = { onRequestPermission(Manifest.permission.POST_NOTIFICATIONS) }
                    )
                    PermissionStatusRow(
                        name = "Phone Calls (CALL_PHONE)",
                        desc = "Allows direct calling when requested",
                        permission = Manifest.permission.CALL_PHONE,
                        onRequest = { onRequestPermission(Manifest.permission.CALL_PHONE) }
                    )
                    PermissionStatusRow(
                        name = "Contacts (READ_CONTACTS)",
                        desc = "Enables looking up contact phone numbers",
                        permission = Manifest.permission.READ_CONTACTS,
                        onRequest = { onRequestPermission(Manifest.permission.READ_CONTACTS) }
                    )
                }
            }

            // 5. ADVANCED SECTION
            SettingsSectionHeader(title = "ADVANCED SETTINGS", icon = Icons.Default.Tune)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = JarvisSurfaceDark),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    OutlinedTextField(
                        value = endpointUrl,
                        onValueChange = {
                            endpointUrl = it
                            commitChanges()
                        },
                        label = { Text("NVIDIA API Endpoint") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = outlinedTextFieldColors()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Debug Logging", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                            Text("Never logs keys or sensitive data", color = TextSecondary, fontSize = 12.sp)
                        }
                        Switch(
                            checked = debugLogging,
                            onCheckedChange = {
                                debugLogging = it
                                commitChanges()
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = JarvisCyan, checkedTrackColor = JarvisCyan.copy(alpha = 0.5f))
                        )
                    }

                    OutlinedButton(
                        onClick = { showClearDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisErrorRed),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(imageVector = Icons.Default.DeleteSweep, contentDescription = "Clear Keys")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Clear Stored API Keys")
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear All Stored Keys?", color = TextPrimary) },
            text = {
                Text(
                    "This will delete your locally stored NVIDIA and Picovoice API keys from encrypted storage.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearAllStoredKeys()
                        apiKey = ""
                        picovoiceKey = ""
                        showClearDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = JarvisErrorRed)
                ) {
                    Text("Clear Keys")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = JarvisSurfaceDark
        )
    }
}

@Composable
fun SettingsSectionHeader(title: String, icon: ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = JarvisCyan,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.5.sp,
                color = JarvisCyan
            )
        )
    }
}

@Composable
fun PermissionStatusRow(
    name: String,
    desc: String,
    permission: String,
    onRequest: () -> Unit
) {
    val context = LocalContext.current
    val isGranted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = name, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(text = desc, color = TextSecondary, fontSize = 11.sp)
        }

        if (isGranted) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Granted",
                    tint = JarvisOnlineGreen,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Granted", color = JarvisOnlineGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            OutlinedButton(
                onClick = onRequest,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisCyan)
            ) {
                Text("Grant", fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun outlinedTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = JarvisCyan,
    unfocusedBorderColor = TextMuted,
    focusedLabelColor = JarvisCyan,
    unfocusedLabelColor = TextSecondary,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    cursorColor = JarvisCyan
)
