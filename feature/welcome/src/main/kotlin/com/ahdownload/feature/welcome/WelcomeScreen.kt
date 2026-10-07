package com.ahdownload.feature.welcome

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ahdownload.core.common.UiTraceLogger
import com.ahdownload.core.common.interaction
import com.ahdownload.core.common.snapshot
import com.ahdownload.core.designsystem.AHGradientPrimaryButton
import com.ahdownload.core.designsystem.rememberUiTraceContext
import com.ahdownload.core.designsystem.AHStatusPill

@Composable
fun WelcomeRoute(
    onContinue: () -> Unit,
    uiTraceLogger: UiTraceLogger,
    viewModel: WelcomeViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    WelcomeScreen(
        ready = uiState.ready,
        onContinue = onContinue,
        uiTraceLogger = uiTraceLogger,
    )
}

@Composable
private fun WelcomeScreen(
    ready: Boolean,
    onContinue: () -> Unit,
    uiTraceLogger: UiTraceLogger,
) {
    val transition = rememberInfiniteTransition(label = "welcomeMotion")

    val uiContext = rememberUiTraceContext()
    LaunchedEffect(ready) {
        uiTraceLogger.snapshot(
            screen = "WELCOME",
            component = "WelcomeScreen",
            components = "status_pill,security_icon,brand_mark,feature_marks,start_button,footer_text",
            stateSummary = "ready=" + ready,
            context = uiContext,
        )
    }
    val waveColor = MaterialTheme.colorScheme.secondary
    val orbScale by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "orbScale",
    )
    val orbAlpha by transition.animateFloat(
        initialValue = 0.18f,
        targetValue = 0.34f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "orbAlpha",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.background,
                    ),
                ),
            ),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .alpha(0.18f),
        ) {
            val path = Path().apply {
                moveTo(0f, size.height * 0.22f)
                cubicTo(
                    size.width * 0.28f,
                    size.height * 0.08f,
                    size.width * 0.66f,
                    size.height * 0.38f,
                    size.width,
                    size.height * 0.18f,
                )
            }
            drawPath(
                path = path,
                color = waveColor,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.4f),
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 80.dp, end = 18.dp)
                .size(180.dp)
                .scale(orbScale)
                .alpha(orbAlpha)
                .background(
                    color = MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AHStatusPill(
                    text = if (ready) "المحرك جاهز" else "جارٍ التجهيز",
                    success = ready,
                )
                Icon(
                    imageVector = Icons.Rounded.Security,
                    contentDescription = "الأمان",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn(tween(650)) + scaleIn(
                        initialScale = 0.82f,
                        animationSpec = tween(650, easing = FastOutSlowInEasing),
                    ),
                ) {
                    Box(
                        modifier = Modifier
                            .size(122.dp)
                            .background(
                                brush = Brush.linearGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.colorScheme.secondary,
                                    ),
                                ),
                                shape = MaterialTheme.shapes.large,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(58.dp),
                            tint = Color.White,
                        )
                    }
                }

                Spacer(Modifier.height(26.dp))

                Text(
                    text = "AHDownload",
                    style = MaterialTheme.typography.displayLarge,
                )

                Spacer(Modifier.height(10.dp))

                Text(
                    text = "تحميل ذكي، سريع، ومنظم لمحتواك المفضل.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(24.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FeatureMark(Icons.Rounded.AutoAwesome, "ذكي")
                    FeatureMark(Icons.Rounded.CloudDownload, "سريع")
                    FeatureMark(Icons.Rounded.Security, "آمن")
                }
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                AHGradientPrimaryButton(
                    text = "ابدأ الآن",
                    enabled = ready,
                    onClick = { uiTraceLogger.interaction("WELCOME", "start_button", "continue"); onContinue() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "بتجربة خفيفة وحركة ناعمة دون تشتيت.",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun FeatureMark(
    icon: ImageVector,
    label: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(17.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
