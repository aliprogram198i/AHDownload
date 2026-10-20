package com.ahdownload.feature.welcome

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ahdownload.core.common.UiTraceLogger
import com.ahdownload.core.common.interaction
import com.ahdownload.core.common.snapshot
import com.ahdownload.core.designsystem.AHGradientPrimaryButton
import com.ahdownload.core.designsystem.rememberUiTraceContext
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

private const val AUTO_CONTINUE_DELAY_MS = 2_800L

@Composable
fun WelcomeRoute(
    onContinue: () -> Unit,
    uiTraceLogger: UiTraceLogger,
) {
    WelcomeScreen(
        onContinue = onContinue,
        uiTraceLogger = uiTraceLogger,
    )
}

@Composable
private fun WelcomeScreen(
    onContinue: () -> Unit,
    uiTraceLogger: UiTraceLogger,
) {
    val uiContext = rememberUiTraceContext()
    val currentOnContinue = rememberUpdatedState(onContinue)
    val continuationClaimed = remember { AtomicBoolean(false) }
    val canvasPrimary = MaterialTheme.colorScheme.primary
    val canvasAccent = MaterialTheme.colorScheme.secondary

    val continueOnce: (String) -> Unit = remember(uiTraceLogger, uiContext) {
        { source ->
            if (continuationClaimed.compareAndSet(false, true)) {
                uiTraceLogger.interaction("WELCOME", "start_button", source)
                currentOnContinue.value()
            }
        }
    }

    LaunchedEffect(uiTraceLogger, uiContext) {
        uiTraceLogger.snapshot(
            screen = "WELCOME",
            component = "WelcomeScreen",
            components = "brand_mark,headline,feature_marks,start_button,footer_text",
            stateSummary = "first_run_welcome",
            context = uiContext,
        )
    }

    LaunchedEffect(continueOnce) {
        delay(AUTO_CONTINUE_DELAY_MS)
        continueOnce("auto_continue")
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.54f),
                    ),
                ),
            ),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .alpha(0.22f),
        ) {
            val path = Path().apply {
                moveTo(0f, size.height * 0.23f)
                cubicTo(
                    size.width * 0.24f,
                    size.height * 0.08f,
                    size.width * 0.72f,
                    size.height * 0.34f,
                    size.width,
                    size.height * 0.16f,
                )
            }
            drawPath(
                path = path,
                color = canvasPrimary,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.0f),
            )
            drawCircle(
                color = canvasPrimary.copy(alpha = 0.11f),
                radius = size.width * 0.23f,
                center = androidx.compose.ui.geometry.Offset(size.width * 0.98f, size.height * 0.17f),
            )
            drawCircle(
                color = canvasAccent.copy(alpha = 0.08f),
                radius = size.width * 0.19f,
                center = androidx.compose.ui.geometry.Offset(size.width * 0.03f, size.height * 0.82f),
            )
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val fontScale = LocalDensity.current.fontScale
            val compactLayout = maxHeight < 740.dp || fontScale > 1.15f
            val scrollState = rememberScrollState()
            val columnModifier = if (compactLayout) {
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
            } else {
                Modifier.fillMaxSize()
            }

            Column(
                modifier = columnModifier
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(
                        horizontal = 22.dp,
                        vertical = if (compactLayout) 16.dp else 24.dp,
                    ),
                verticalArrangement = if (compactLayout) {
                    Arrangement.spacedBy(18.dp)
                } else {
                    Arrangement.SpaceBetween
                },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "مرحبًا بك",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.88f),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = "فيديو وصوت بسهولة",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }

                if (!compactLayout) Spacer(Modifier.height(2.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val logoSize = if (compactLayout) 112.dp else 132.dp
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(tween(500)) + scaleIn(
                            initialScale = 0.90f,
                            animationSpec = tween(500, easing = FastOutSlowInEasing),
                        ),
                    ) {
                        Box(
                            modifier = Modifier.size(logoSize + 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(logoSize + 14.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.82f),
                                        shape = RoundedCornerShape(34.dp),
                                    ),
                            )
                            Image(
                                painter = painterResource(
                                    id = com.ahdownload.core.designsystem.R.drawable.ahdownload_brand_mark,
                                ),
                                contentDescription = "شعار AHDownload",
                                modifier = Modifier.size(logoSize),
                            )
                        }
                    }

                    Spacer(Modifier.height(if (compactLayout) 14.dp else 19.dp))

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
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = "حلّل الرابط، اختر جودة الفيديو أو استخرج الصوت، ثم تابع التنزيل من مكان واحد.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(Modifier.height(if (compactLayout) 16.dp else 22.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FeatureMark(Icons.Rounded.PlayCircle, "فيديو")
                        FeatureMark(Icons.Rounded.Headphones, "صوت")
                        FeatureMark(Icons.Rounded.FolderOpen, "التنزيلات")
                    }
                }

                if (!compactLayout) Spacer(Modifier.height(2.dp))

                Column(modifier = Modifier.fillMaxWidth()) {
                    AHGradientPrimaryButton(
                        trackingScreen = "WELCOME",
                        trackingId = "WELCOME.gradient_primary.01",
                        text = "ابدأ الآن",
                        enabled = true,
                        onClick = { continueOnce("continue") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 54.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "سيتم الانتقال تلقائيًا بعد لحظات",
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
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
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.84f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
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
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
