package com.example.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agent.AgentState
import com.example.ui.theme.JarvisBlue
import com.example.ui.theme.JarvisCyan
import com.example.ui.theme.JarvisDeepBackground
import com.example.ui.theme.JarvisErrorRed
import com.example.ui.theme.JarvisExecutingPurple
import com.example.ui.theme.JarvisListeningCyan
import com.example.ui.theme.JarvisOnlineGreen
import com.example.ui.theme.JarvisSpeakingBlue
import com.example.ui.theme.JarvisThinkingAmber
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

    val stateColor by animateColorAsState(
        targetValue = when (agentState) {
            AgentState.OFFLINE -> TextMuted
            AgentState.LISTENING_FOR_WAKE_WORD -> JarvisOnlineGreen
            AgentState.LISTENING -> JarvisListeningCyan
            AgentState.THINKING -> JarvisThinkingAmber
            AgentState.EXECUTING -> JarvisExecutingPurple
            AgentState.SPEAKING -> JarvisSpeakingBlue
            AgentState.ERROR -> JarvisErrorRed
        },
        label = "StateColorAnimation"
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = JarvisDeepBackground,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "J.A.R.V.I.S.",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 4.sp,
                            fontFamily = FontFamily.Monospace,
                            color = JarvisCyan
                        )
                    )
                },
                actions = {
                    IconButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier
                            .testTag("settings_button")
                            .size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Open Settings",
                            tint = JarvisCyan
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = JarvisDeepBackground
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Status Pill
            StatusIndicatorCard(
                agentState = agentState,
                stateColor = stateColor,
                hasApiKey = settings.nvidiaApiKey.isNotBlank()
            )

            // Center Arc Reactor Visualizer
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                JarvisArcReactorCore(
                    agentState = agentState,
                    stateColor = stateColor,
                    isActive = isRunning
                )
            }

            // Bottom Controls Area
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Key Missing Warning
                if (settings.nvidiaApiKey.isBlank()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = JarvisThinkingAmber.copy(alpha = 0.15f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "⚠ NVIDIA API Key required. Configure in Settings.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = JarvisThinkingAmber,
                                fontWeight = FontWeight.SemiBold
                            ),
                            modifier = Modifier.padding(12.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // Primary Start / Stop Button
                if (!isRunning) {
                    Button(
                        onClick = {
                            if (!hasMicrophonePermission) {
                                onRequestMicrophonePermission()
                            } else {
                                viewModel.toggleAssistant(true)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .testTag("start_jarvis_button"),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = JarvisCyan,
                            contentColor = Color.Black
                        )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PowerSettingsNew,
                                contentDescription = "Start JARVIS",
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "START JARVIS",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 2.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            )
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            viewModel.toggleAssistant(false)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .testTag("stop_jarvis_button"),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = JarvisErrorRed,
                            contentColor = Color.White
                        )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "Stop JARVIS",
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "STOP JARVIS",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 2.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Quick Tap-to-Talk action
                    OutlinedButton(
                        onClick = {
                            viewModel.triggerManualListen()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("manual_listen_button"),
                        shape = RoundedCornerShape(12.dp),
                        border = ButtonDefaults.outlinedButtonBorder.copy(
                            brush = Brush.horizontalGradient(listOf(JarvisCyan, JarvisBlue))
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Speak to JARVIS",
                            tint = JarvisCyan,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Tap to Speak Now",
                            color = JarvisCyan,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (isRunning) "Running in background • Waiting for \"Hey Jarvis\"" else "Press Start to initialize background voice assistant",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = TextSecondary,
                        fontFamily = FontFamily.Monospace
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp)
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, stateColor.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            .testTag("status_card"),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF0C1322)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(stateColor)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "STATUS",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = TextSecondary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    )
                    Text(
                        text = agentState.label,
                        style = MaterialTheme.typography.titleSmall.copy(
                            color = stateColor,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                    )
                }
            }

            Text(
                text = if (hasApiKey) "NVIDIA NIM ACTIVE" else "API KEY NEEDED",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = if (hasApiKey) JarvisCyan.copy(alpha = 0.8f) else JarvisThinkingAmber,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
            )
        }
    }
}

@Composable
fun JarvisArcReactorCore(
    agentState: AgentState,
    stateColor: Color,
    isActive: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ArcReactorPulse")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = if (isActive) 1.05f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (agentState) {
                    AgentState.LISTENING -> 800
                    AgentState.THINKING -> 500
                    AgentState.SPEAKING -> 600
                    else -> 2000
                },
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "CoreScale"
    )

    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isActive) 8000 else 24000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "CoreRotation"
    )

    val counterRotationAngle by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isActive) 6000 else 18000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "CounterRotation"
    )

    Box(
        modifier = Modifier
            .size(280.dp)
            .scale(pulseScale),
        contentAlignment = Alignment.Center
    ) {
        // Outer Arc Canvas
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .rotate(rotationAngle)
        ) {
            val center = Offset(size.width / 2, size.height / 2)
            val outerRadius = size.minDimension / 2 - 12.dp.toPx()
            val innerRadius = outerRadius - 16.dp.toPx()

            // Outer Glowing Ring
            drawCircle(
                color = stateColor.copy(alpha = 0.25f),
                radius = outerRadius,
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )

            // Segmented Arcs
            val segmentAngle = 360f / 12f
            for (i in 0 until 12) {
                val start = i * segmentAngle + 4f
                val sweep = segmentAngle - 8f
                drawArc(
                    color = stateColor.copy(alpha = if (i % 2 == 0) 0.8f else 0.4f),
                    startAngle = start,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(center.x - outerRadius, center.y - outerRadius),
                    size = androidx.compose.ui.geometry.Size(outerRadius * 2, outerRadius * 2),
                    style = Stroke(width = 4.dp.toPx())
                )
            }
        }

        // Inner Counter Rotating Ring
        Canvas(
            modifier = Modifier
                .size(200.dp)
                .rotate(counterRotationAngle)
        ) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = size.minDimension / 2 - 8.dp.toPx()

            drawCircle(
                color = stateColor.copy(alpha = 0.35f),
                radius = radius,
                center = center,
                style = Stroke(
                    width = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 15f), 0f)
                )
            )

            // 6 Inner Core Nodes
            for (i in 0 until 6) {
                val angle = Math.toRadians((i * 60.0))
                val nodeX = (center.x + (radius - 12.dp.toPx()) * Math.cos(angle)).toFloat()
                val nodeY = (center.y + (radius - 12.dp.toPx()) * Math.sin(angle)).toFloat()
                drawCircle(
                    color = stateColor,
                    radius = 4.dp.toPx(),
                    center = Offset(nodeX, nodeY)
                )
            }
        }

        // Center Glowing Core
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            stateColor.copy(alpha = 0.9f),
                            stateColor.copy(alpha = 0.4f),
                            Color.Transparent
                        )
                    )
                )
                .border(2.dp, stateColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isActive) "AI" else "OFF",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = TextPrimary,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp
                )
            )
        }
    }
}
