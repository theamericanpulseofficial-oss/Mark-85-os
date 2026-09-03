package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import com.example.ui.theme.MarkCrimson
import com.example.ui.theme.MarkGold
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

    // 1 Unified API Key & 1 Unified Model
    var apiKey by remember(currentSettings) { mutableStateOf(currentSettings.nvidiaApiKey) }
    var showApiKey by remember { mutableStateOf(false) }
    var selectedModel by remember(currentSettings) { mutableStateOf(currentSettings.nvidiaModel) }

    // LiveKit credentials (Hardcoded default ready)
    var livekitUrl by remember(currentSettings) { mutableStateOf(currentSettings.livekitUrl) }
    var livekitApiKey by remember(currentSettings) { mutableStateOf(currentSettings.livekitApiKey) }
    var livekitSecret by remember(currentSettings) { mutableStateOf(currentSettings.livekitSecret) }
    var showLivekitSecret by remember { mutableStateOf(false) }

    // Advanced & Persona
    var temperature by remember(currentSettings) { mutableFloatStateOf(currentSettings.temperature) }
    var maxTokens by remember(currentSettings) { mutableIntStateOf(currentSettings.maxTokens) }
    var endpointUrl by remember(currentSettings) { mutableStateOf(currentSettings.nvidiaEndpoint) }
    var systemPrompt by remember(currentSettings) { mutableStateOf(currentSettings.systemPrompt) }
    var timeoutSeconds by remember(currentSettings) { mutableIntStateOf(currentSettings.timeoutSeconds) }
    var debugLogging by remember(currentSettings) { mutableStateOf(currentSettings.debugLogging) }

    // Voice & Wake Word
    var wakeWordEnabled by remember(currentSettings) { mutableStateOf(currentSettings.wakeWordEnabled) }
    var wakeWordSensitivity by remember(currentSettings) { mutableFloatStateOf(currentSettings.wakeWordSensitivity) }
    var ttsSpeed by remember(currentSettings) { mutableFloatStateOf(currentSettings.ttsSpeed) }
    var ttsPitch by remember(currentSettings) { mutableFloatStateOf(currentSettings.ttsPitch) }

    var showClearDialog by remember { mutableStateOf(false) }

    fun commitChanges() {
        val updated = currentSettings.copy(
            nvidiaApiKey = apiKey.trim(),
            nvidiaModel = selectedModel.trim(),
            voiceModel = selectedModel.trim(),
            livekitUrl = livekitUrl.trim(),
            livekitApiKey = livekitApiKey.trim(),
            livekitSecret = livekitSecret.trim(),
            temperature = temperature,
            maxTokens = maxTokens,
            systemPrompt = systemPrompt.trim(),
            nvidiaEndpoint = endpointUrl.trim(),
            timeoutSeconds = timeoutSeconds,
            wakeWordEnabled = wakeWordEnabled,
            wakeWordSensitivity = wakeWordSensitivity,
            ttsSpeed = ttsSpeed,
            ttsPitch = ttsPitch,
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
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. SINGLE UNIFIED AI MODEL & API KEY SECTION
            SettingsSectionHeader(title = "AI NEURAL ENGINE (1 API & 1 MODEL)", icon = Icons.Default.Psychology)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = JarvisSurfaceDark),
                shape = RoundedCornerShape(16.dp),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = androidx.compose.ui.graphics.SolidColor(JarvisCyan.copy(alpha = 0.3f))
                )
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        text = "Unified AI Engine: Works directly with NVIDIA NIM, OpenRouter, OpenAI, Groq, or DeepSeek keys.",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = Color(0xFFB9CACB),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    )

                    // 1 API Key Input Field
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = {
                            apiKey = it
                            commitChanges()
                        },
                        label = { Text("AI API Key (NVIDIA / OpenAI / OpenRouter / Groq)") },
                        placeholder = { Text("nvapi-... or sk-...") },
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

                    // 1 Model Input Field - Direct plain text field (No Dropdown)
                    OutlinedTextField(
                        value = selectedModel,
                        onValueChange = {
                            selectedModel = it
                            commitChanges()
                        },
                        label = { Text("AI Model Name") },
                        placeholder = { Text("e.g. meta/llama-3.3-70b-instruct") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("agent_model_input"),
                        singleLine = true,
                        colors = outlinedTextFieldColors()
                    )

                    // Test AI Connection Button
                    Button(
                        onClick = {
                            commitChanges()
                            viewModel.testNvidiaConnection()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("test_api_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = JarvisCyan.copy(alpha = 0.22f),
                            contentColor = JarvisCyan
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.NetworkCheck, contentDescription = "Test AI Model", modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Test Model Connection", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                    }

                    // Connection test state feedback
                    when (val state = testState) {
                        is ConnectionTestState.Testing -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = JarvisCyan,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Connecting & testing model...", color = TextSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                        is ConnectionTestState.Success -> {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = JarvisOnlineGreen.copy(alpha = 0.12f)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = JarvisOnlineGreen, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = state.message,
                                        color = JarvisOnlineGreen,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                                    )
                                }
                            }
                        }
                        is ConnectionTestState.Error -> {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = JarvisErrorRed.copy(alpha = 0.12f)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Info, contentDescription = null, tint = JarvisErrorRed, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = state.message,
                                        color = JarvisErrorRed,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                    )
                                }
                            }
                        }
                        ConnectionTestState.Idle -> {}
                    }
                }
            }

            // 2. LIVEKIT STREAMING STATUS (HARDCODED & READY)
            SettingsSectionHeader(title = "LIVEKIT CLOUD STREAMING", icon = Icons.Default.CloudDone)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = JarvisSurfaceDark),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "LiveKit Real-Time WebRTC",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp
                            )
                            Text(
                                text = "High-speed voice audio pipeline",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(JarvisOnlineGreen.copy(alpha = 0.18f))
                                .border(1.dp, JarvisOnlineGreen.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("READY ✓", color = JarvisOnlineGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                    }

                    Text(
                        text = "Server: wss://jarvis-dnr09c6u.livekit.cloud\nPre-configured for bidirectional real-time audio.",
                        color = Color(0xFF8FA3AD),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // 4. WAKE WORD & VOICE TTS
            SettingsSectionHeader(title = "VOICE & SPEECH (TTS)", icon = Icons.Default.GraphicEq)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = JarvisSurfaceDark),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Speech Rate (Speed)", color = TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Text(String.format("%.1fx", ttsSpeed), color = JarvisCyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
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
                            Text("Voice Pitch", color = TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Text(String.format("%.1fx", ttsPitch), color = JarvisCyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
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

            // 5. ANDROID PERMISSIONS
            SettingsSectionHeader(title = "SYSTEM PERMISSIONS", icon = Icons.Default.Security)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = JarvisSurfaceDark),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PermissionStatusRow(
                        name = "Microphone (RECORD_AUDIO)",
                        desc = "Required for voice commands & wake-word",
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

            OutlinedButton(
                onClick = { showClearDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisErrorRed),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(imageVector = Icons.Default.DeleteSweep, contentDescription = "Clear Keys")
                Spacer(modifier = Modifier.width(8.dp))
                Text("Reset & Clear Stored Keys")
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
                    "This will reset your API key and restore defaults in secure storage.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearAllStoredKeys()
                        apiKey = ""
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
                Text("Granted ✓", color = JarvisOnlineGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
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
