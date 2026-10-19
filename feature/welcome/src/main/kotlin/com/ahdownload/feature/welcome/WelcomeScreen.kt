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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ahdownload.core.common.UiTraceLogger
import com.ahdownload.core.common.interaction
import com.ahdownload.core.common.snapshot
import com.ahdownload.core.designsystem.AHGradientPrimaryButton
import com.ahdownload.core.designsystem.AHStatusPill
import com.ahdownload.core.designsystem.rememberUiTraceContext

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
    var didContinue by remember { mutableStateOf(false) }
    val canvasPrimary = MaterialTheme.colorScheme.primary
    val canvasAccent = MaterialTheme.colorScheme.secondary

    LaunchedEffect(ready) {
        uiTraceLogger.snapshot(
            screen = "WELCOME",
            component = "WelcomeScreen",
            components = "status_pill,brand_mark,headline,feature_marks,start_button,footer_text",
            stateSummary = "ready=" + ready,
            context = uiContext,
        )
    }

    LaunchedEffect(ready) {
        if (ready) {
            delay(3_200)
            if (!didContinue) {
                didContinue = true
                uiTraceLogger.interaction("WELCOME", "start_button", "auto_continue")
                onContinue()
            }
        }
    }

    val orbScale by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "orbScale",
    )
    val orbAlpha by transition.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "orbAlpha",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.50f),
                    ),
                ),
            ),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .alpha(0.34f),
        ) {
            val path = Path().apply {
                moveTo(0f, size.height * 0.24f)
                cubicTo(
                    size.width * 0.26f,
                    size.height * 0.08f,
                    size.width * 0.69f,
                    size.height * 0.34f,
                    size.width,
                    size.height * 0.17f,
                )
            }
            drawPath(
                path = path,
                color = canvasPrimary,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.2f),
            )
            drawCircle(
                color = canvasPrimary.copy(alpha = 0.13f),
                radius = size.width * 0.24f,
                center = androidx.compose.ui.geometry.Offset(size.width * 0.98f, size.height * 0.18f),
            )
            drawCircle(
                color = canvasAccent.copy(alpha = 0.09f),
                radius = size.width * 0.20f,
                center = androidx.compose.ui.geometry.Offset(size.width * 0.02f, size.height * 0.80f),
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 72.dp, end = 8.dp)
                .size(176.dp)
                .scale(orbScale)
                .alpha(orbAlpha)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp, vertical = 24.dp),
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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "MODERN INDIGO",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.1.sp,
                    )
                }
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
                        modifier = Modifier.size(148.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(146.dp)
                                .scale(orbScale)
                                .alpha(orbAlpha + 0.08f)
                                .background(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(36.dp),
                                ),
                        )
                        Image(
                            painter = painterResource(
                                id = com.ahdownload.core.designsystem.R.drawable.ahdownload_brand_mark,
                            ),
                            contentDescription = "شعار AHDownload",
                            modifier = Modifier.size(124.dp),
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 5.dp, end = 4.dp)
                                .size(32.dp)
                                .background(MaterialTheme.colorScheme.surface, CircleShape)
                                .border(
                                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(19.dp))

                Text(
                    text = "AHDownload",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.6).sp,
                    color = MaterialTheme.colorScheme.onBackground,
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = "نزّل ما تحب، واحتفظ به بسهولة",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(7.dp))

                Text(
                    text = "الفيديو والصوت في تجربة واحدة، مع خيارات واضحة وخطوات بسيطة.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(21.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FeatureMark(Icons.Rounded.AutoAwesome, "ذكي")
                    FeatureMark(Icons.Rounded.CloudDownload, "مرن")
                    FeatureMark(Icons.Rounded.Security, "منظّم")
                }
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                AHGradientPrimaryButton(
                    trackingScreen = "WELCOME",
                    trackingId = "WELCOME.gradient_primary.01",
                    text = "ابدأ الآن",
                    enabled = ready,
                    onClick = {
                        if (!didContinue) {
                            didContinue = true
                            uiTraceLogger.interaction("WELCOME", "start_button", "continue")
                            onContinue()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(11.dp))
                Text(
                    text = "تصميم هادئ، وخيارات تنزيل واضحة دون تشتيت.",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
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
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.84f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
