package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.agent.AgentState
import com.example.ui.theme.JarvisErrorRed
import com.example.ui.theme.JarvisExecutingPurple
import com.example.ui.theme.JarvisListeningCyan
import com.example.ui.theme.JarvisOnlineGreen
import com.example.ui.theme.JarvisSpeakingBlue
import com.example.ui.theme.JarvisThinkingAmber
import com.example.ui.theme.MarkBlue
import com.example.ui.theme.MarkCrimson
import com.example.ui.theme.MarkCyan
import com.example.ui.theme.MarkCyanGlow
import com.example.ui.theme.MarkGold
import com.example.ui.theme.MarkGoldAccent
import com.example.ui.theme.MarkSurfaceBorder
import com.example.ui.theme.MarkSurfaceDark
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: JarvisViewModel,
    onNavigateToSettings: () -> Unit,
    onRequestMicrophonePermission: () -> Unit,
    hasMicrophonePermission: Boolean
) {
    val isRunning by viewModel.isServiceRunning.collectAsState()
    val agentState by viewModel.agentState.collectAsState()
    val settings by viewModel.settings.collectAsState()

    var showDiagnosticDialog by remember { mutableStateOf(false) }

    val stateColor by animateColorAsState(
        targetValue = when (agentState) {
            AgentState.OFFLINE -> Color(0xFF00DBE9).copy(alpha = 0.6f)
            AgentState.LISTENING_FOR_WAKE_WORD -> Color(0xFF00DBE9)
            AgentState.LISTENING -> MarkCyan
            AgentState.THINKING -> MarkGold
            AgentState.EXECUTING -> JarvisExecutingPurple
            AgentState.SPEAKING -> JarvisSpeakingBlue
            AgentState.ERROR -> JarvisErrorRed
        },
        label = "StateColorAnimation"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 1. Tactical HUD Grid Background
        TacticalHUDGrid()

        // 2. Main Scaffold and Content
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            topBar = {
                // Tactical Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Left: Grid View Icon
                    IconButton(
                        onClick = { showDiagnosticDialog = true },
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("grid_view_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.GridView,
                            contentDescription = "System Grid",
                            tint = Color(0xFF00DBE9),
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // Center: MARK_85_OS Title
                    Text(
                        text = "MARK_85_OS",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFFDBFCFF),
                            fontSize = 16.sp
                        )
                    )

                    // Right: Circular Tactical Settings Button
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF00DBE9).copy(alpha = 0.08f))
                            .border(1.dp, Color(0xFF00DBE9).copy(alpha = 0.35f), CircleShape)
                            .clickable(onClick = onNavigateToSettings)
                            .testTag("settings_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_tech_settings),
                            contentDescription = "Tactical Settings",
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                // 3. Central Iron Man Mark 85 Artwork with Glowing Crimson HUD Frame
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    IronManMark85Centerpiece(
                        agentState = agentState,
                        isRunning = isRunning,
                        stateColor = stateColor
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 4. Bottom Action Area with Tactical Scanline Button
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // API Key Missing Warning Banner if blank
                    if (settings.nvidiaApiKey.isBlank()) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MarkGold.copy(alpha = 0.12f)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            border = CardDefaults.outlinedCardBorder().copy(
                                brush = Brush.horizontalGradient(listOf(MarkGold, MarkCrimson))
                            )
                        ) {
                            Text(
                                text = "⚠ NVIDIA API KEY NEEDED • TAP TOP-RIGHT TO CONFIGURE",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MarkGold,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                modifier = Modifier
                                    .padding(8.dp)
                                    .fillMaxWidth(),
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    // Tactical START AGENT / STOP AGENT Button
                    TacticalScanlineButton(
                        isRunning = isRunning,
                        agentState = agentState,
                        onClick = {
                            if (!isRunning) {
                                if (!hasMicrophonePermission) {
                                    onRequestMicrophonePermission()
                                } else {
                                    viewModel.startService()
                                }
                            } else {
                                viewModel.stopService()
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Tactical Subtitle Status
                    Text(
                        text = when {
                            !isRunning -> "INITIATING PROTOCOL"
                            agentState == AgentState.LISTENING_FOR_WAKE_WORD -> "STANDBY PROTOCOL ACTIVE • SAY \"HEY JARVIS\""
                            agentState == AgentState.LISTENING -> "LISTENING TO VOICE STREAM..."
                            agentState == AgentState.THINKING -> "NEURAL REASONING ACTIVE..."
                            agentState == AgentState.EXECUTING -> "EXECUTING DEVICE ACTION..."
                            agentState == AgentState.SPEAKING -> "TRANSMITTING VOCAL RESPONSE..."
                            else -> "SYSTEM ACTIVE"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = Color(0xFFB9CACB).copy(alpha = 0.6f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            letterSpacing = 2.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // Diagnostic / System Info Dialog
        if (showDiagnosticDialog) {
            AlertDialog(
                onDismissRequest = { showDiagnosticDialog = false },
                title = {
                    Text(
                        text = "MARK 85 OS DIAGNOSTICS",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00DBE9)
                        )
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "• AI Neural Core: ${settings.nvidiaModel}",
                            style = MaterialTheme.typography.bodySmall.copy(color = TextPrimary, fontFamily = FontFamily.Monospace)
                        )
                        Text(
                            text = "• Wake Word: Local on-device (\"Hey Jarvis\")",
                            style = MaterialTheme.typography.bodySmall.copy(color = TextPrimary, fontFamily = FontFamily.Monospace)
                        )
                        Text(
                            text = "• Armor Protocol: Nanotech MK-85",
                            style = MaterialTheme.typography.bodySmall.copy(color = TextPrimary, fontFamily = FontFamily.Monospace)
                        )
                        Text(
                            text = "• Service Status: ${if (isRunning) "ACTIVE (FOREGROUND)" else "STANDBY"}",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = if (isRunning) JarvisOnlineGreen else TextMuted,
                                fontFamily = FontFamily.Monospace
                            )
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showDiagnosticDialog = false }) {
                        Text("CLOSE", color = Color(0xFF00DBE9), fontFamily = FontFamily.Monospace)
                    }
                },
                containerColor = MarkSurfaceDark,
                shape = RoundedCornerShape(16.dp)
            )
        }
    }
}

@Composable
fun TacticalHUDGrid() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val gridSpacing = 40.dp.toPx()
        val gridColor = Color(0xFF00DBE9).copy(alpha = 0.05f)

        // Vertical Grid Lines
        var x = 0f
        while (x < size.width) {
            drawLine(
                color = gridColor,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 1f
            )
            x += gridSpacing
        }

        // Horizontal Grid Lines
        var y = 0f
        while (y < size.height) {
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1f
            )
            y += gridSpacing
        }
    }
}

@Composable
fun IronManMark85Centerpiece(
    agentState: AgentState,
    isRunning: Boolean,
    stateColor: Color
) {
    val infiniteTransition = rememberInfiniteTransition(label = "IronManAnimations")

    // Smooth power-on ignition animation when user starts the agent (smooth gradual power-on)
    val ignition by animateFloatAsState(
        targetValue = if (isRunning) 1f else 0f,
        animationSpec = tween(
            durationMillis = 1200,
            easing = FastOutSlowInEasing
        ),
        label = "IgnitionProgress"
    )

    // Living subtle breathing pulse for eyes & arc reactor when active
    val arcPulse by infiniteTransition.animateFloat(
        initialValue = 0.90f,
        targetValue = 1.10f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isRunning) 1400 else 2800,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ArcPulse"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(),
        contentAlignment = Alignment.Center
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            val imageAspect = 9f / 16f
            val containerAspect = maxWidth / maxHeight

            val (dispWidth, dispHeight) = if (containerAspect > imageAspect) {
                Pair(maxHeight * imageAspect, maxHeight)
            } else {
                Pair(maxWidth, maxWidth / imageAspect)
            }

            Box(
                modifier = Modifier.size(dispWidth, dispHeight),
                contentAlignment = Alignment.Center
            ) {
                // 1. Tactical Red-Line Iron Man Illustration
                Image(
                    painter = painterResource(id = R.drawable.iron_man_mark85_hud),
                    contentDescription = "Iron Man Mark 85",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds
                )

                // 2. Dynamic Power-On Illumination & Power-Off Mask Layer
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height

                    val leftEyeCenter = Offset(w * 0.442f, h * 0.432f)
                    val rightEyeCenter = Offset(w * 0.558f, h * 0.432f)
                    val reactorCenter = Offset(w * 0.500f, h * 0.755f)
                    val leftShoulderNode = Offset(w * 0.305f, h * 0.655f)
                    val rightShoulderNode = Offset(w * 0.695f, h * 0.655f)

                    // A. Dark Cover Mask: when stopped, completely hides the white eyes and reactor core
                    val coverAlpha = (1f - ignition).coerceIn(0f, 1f)
                    if (coverAlpha > 0.005f) {
                        val maskColor = Color(0xFF000000).copy(alpha = coverAlpha)

                        // Hide left eye slit
                        drawOval(
                            color = maskColor,
                            topLeft = Offset(leftEyeCenter.x - w * 0.055f, leftEyeCenter.y - h * 0.016f),
                            size = Size(w * 0.11f, h * 0.032f)
                        )
                        // Hide right eye slit
                        drawOval(
                            color = maskColor,
                            topLeft = Offset(rightEyeCenter.x - w * 0.055f, rightEyeCenter.y - h * 0.016f),
                            size = Size(w * 0.11f, h * 0.032f)
                        )
                        // Hide Arc Reactor white core
                        drawCircle(
                            color = maskColor,
                            radius = w * 0.088f,
                            center = reactorCenter
                        )
                        // Hide shoulder nodes
                        drawCircle(
                            color = maskColor,
                            radius = w * 0.025f,
                            center = leftShoulderNode
                        )
                        drawCircle(
                            color = maskColor,
                            radius = w * 0.025f,
                            center = rightShoulderNode
                        )
                    }

                    // B. Dynamic Power-On Lighting & Illumination Layer
                    if (ignition > 0.005f) {
                        val activeIntensity = ignition * (if (isRunning) arcPulse else 1f)

                        // 1. Arc Reactor Soft Ambient Glow Halo
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    stateColor.copy(alpha = (0.55f * activeIntensity).coerceIn(0f, 1f)),
                                    stateColor.copy(alpha = (0.22f * activeIntensity).coerceIn(0f, 1f)),
                                    Color.Transparent
                                ),
                                center = reactorCenter,
                                radius = w * 0.28f
                            ),
                            center = reactorCenter,
                            radius = w * 0.28f
                        )

                        // 2. Arc Reactor Glowing Outer Rim
                        drawCircle(
                            color = stateColor.copy(alpha = (0.85f * activeIntensity).coerceIn(0f, 1f)),
                            radius = w * 0.082f,
                            center = reactorCenter,
                            style = Stroke(width = 2.dp.toPx())
                        )

                        // 3. Arc Reactor Intense Plasma Core
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = (0.98f * ignition).coerceIn(0f, 1f)),
                                    Color(0xFFE8FFFF).copy(alpha = (0.92f * ignition).coerceIn(0f, 1f)),
                                    stateColor.copy(alpha = (0.55f * ignition).coerceIn(0f, 1f)),
                                    Color.Transparent
                                ),
                                center = reactorCenter,
                                radius = w * 0.065f
                            ),
                            center = reactorCenter,
                            radius = w * 0.065f
                        )

                        // 4. Eye Flares & Illumination
                        val eyeRadius = w * 0.065f
                        // Left Eye
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = (0.96f * ignition).coerceIn(0f, 1f)),
                                    stateColor.copy(alpha = (0.75f * ignition).coerceIn(0f, 1f)),
                                    Color.Transparent
                                ),
                                center = leftEyeCenter,
                                radius = eyeRadius
                            ),
                            center = leftEyeCenter,
                            radius = eyeRadius
                        )
                        // Right Eye
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = (0.96f * ignition).coerceIn(0f, 1f)),
                                    stateColor.copy(alpha = (0.75f * ignition).coerceIn(0f, 1f)),
                                    Color.Transparent
                                ),
                                center = rightEyeCenter,
                                radius = eyeRadius
                            ),
                            center = rightEyeCenter,
                            radius = eyeRadius
                        )

                        // 5. Shoulder Beacons
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = (0.9f * ignition).coerceIn(0f, 1f)),
                                    stateColor.copy(alpha = (0.6f * ignition).coerceIn(0f, 1f)),
                                    Color.Transparent
                                ),
                                center = leftShoulderNode,
                                radius = w * 0.03f
                            ),
                            center = leftShoulderNode,
                            radius = w * 0.03f
                        )
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = (0.9f * ignition).coerceIn(0f, 1f)),
                                    stateColor.copy(alpha = (0.6f * ignition).coerceIn(0f, 1f)),
                                    Color.Transparent
                                ),
                                center = rightShoulderNode,
                                radius = w * 0.03f
                            ),
                            center = rightShoulderNode,
                            radius = w * 0.03f
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TacticalScanlineButton(
    isRunning: Boolean,
    agentState: AgentState,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ButtonPulse")

    val dotPulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "DotPulse"
    )

    val borderGlow by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "BorderGlow"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth(0.85f)
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xFF0A0E14).copy(alpha = 0.85f))
            .border(
                width = 1.dp,
                color = if (isRunning) Color(0xFFFF5252).copy(alpha = borderGlow) else Color(0xFF00DBE9).copy(alpha = borderGlow),
                shape = RoundedCornerShape(28.dp)
            )
            .clickable(onClick = onClick)
            .testTag(if (isRunning) "stop_jarvis_button" else "start_jarvis_button"),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        ) {
            // Glowing Indicator Dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (isRunning) Color(0xFFFF5252).copy(alpha = dotPulseAlpha)
                        else Color(0xFFDBFCFF).copy(alpha = dotPulseAlpha)
                    )
            )

            Spacer(modifier = Modifier.width(14.dp))

            // Action Text
            Text(
                text = if (isRunning) "STOP AGENT" else "START AGENT",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 3.sp,
                    fontSize = 14.sp,
                    color = if (isRunning) Color(0xFFFF5252) else Color(0xFFDBFCFF)
                )
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Rocket or Stop Icon
            Icon(
                imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.RocketLaunch,
                contentDescription = if (isRunning) "Stop" else "Launch",
                tint = if (isRunning) Color(0xFFFF5252) else Color(0xFFDBFCFF),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// Backward compatibility helpers
@Composable
fun Mark85TelemetryHUD(
    agentState: AgentState,
    stateColor: Color,
    hasApiKey: Boolean,
    isRunning: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, MarkSurfaceBorder, RoundedCornerShape(14.dp))
            .testTag("status_card"),
        colors = CardDefaults.cardColors(containerColor = MarkSurfaceDark)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(stateColor)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "SYS PROTOCOL",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = TextSecondary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            letterSpacing = 1.sp
                        )
                    )
                    Text(
                        text = agentState.name.replace("_", " "),
                        style = MaterialTheme.typography.titleSmall.copy(
                            color = stateColor,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = if (isRunning) "CORE: 100% (ACTIVE)" else "CORE: STANDBY",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = if (isRunning) MarkCyan else TextMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
                Text(
                    text = if (hasApiKey) "NVIDIA NIM READY" else "KEY UNCONFIGURED",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = if (hasApiKey) MarkGoldAccent else JarvisThinkingAmber,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp
                    )
                )
            }
        }
    }
}

@Composable
fun StatusIndicatorCard(
    agentState: AgentState,
    stateColor: Color,
    hasApiKey: Boolean
) {
    Mark85TelemetryHUD(
        agentState = agentState,
        stateColor = stateColor,
        hasApiKey = hasApiKey,
        isRunning = agentState != AgentState.OFFLINE
    )
}

@Composable
fun Mark85ArcReactorCore(
    agentState: AgentState,
    stateColor: Color,
    isActive: Boolean
) {
    IronManMark85Centerpiece(
        agentState = agentState,
        isRunning = isActive,
        stateColor = stateColor
    )
}

@Composable
fun JarvisArcReactorCore(
    agentState: AgentState,
    stateColor: Color,
    isActive: Boolean
) {
    IronManMark85Centerpiece(
        agentState = agentState,
        isRunning = isActive,
        stateColor = stateColor
    )
}
