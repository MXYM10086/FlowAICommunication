package com.flowai.communication.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.flowai.communication.data.repository.DemoConversations
import com.flowai.communication.system.FloatingAssistantService
import com.flowai.communication.system.OverlayPermission
import com.flowai.communication.ui.components.EngineNote
import kotlin.math.min

/**
 * The home page: the app's two independent routes, stated side by side at equal size.
 *
 * There are exactly two ways to use FlowAI and neither depends on the other, so the page presents
 * them as two large parallel cards rather than stacking them in a list the user has to scroll
 * through to discover the second one:
 *
 *   A. text route   — paste or share a chat, read the state, pick a reply;
 *   B. screen route — float the pet over the chat, frame the screen, get the result in place.
 *
 * The cards share every pixel left below the header (`weight(1f)` in a `fillMaxSize` column), so
 * the page fits one screen at any height and never scrolls. Anything that is explanation rather
 * than action lives on the help page ([com.flowai.communication.ui.help.HelpScreen]); only the
 * transient session notices and the analysis-privacy strip stay, because both are claims the user
 * must be able to see without navigating.
 */
@Composable fun HomeScreen(
    open: (String?) -> Unit,
    clearedNotice: String?,
    captureNotice: String? = null,
    onRequestCapture: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenSkins: () -> Unit = {},
    onOpenHelp: () -> Unit = {}
) {
    val context = LocalContext.current
    // Recomputed on resume so returning from the system permission screen shows the new state.
    var granted by remember { mutableStateOf(OverlayPermission.isGranted(context)) }
    var running by remember { mutableStateOf(FloatingAssistantService.isRunning) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = OverlayPermission.isGranted(context)
                running = FloatingAssistantService.isRunning
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            // Order matters: filling first would pin the width to the parent, and the 720dp cap
            // would then be silently coerced away (coerceIn against a fixed incoming constraint),
            // leaving tablets stretched edge to edge. Cap first, fill to that cap, let the Box
            // centre the result.
            Modifier.fillMaxHeight().widthIn(max = 720.dp).fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("让沟通有下一步", style = MaterialTheme.typography.headlineMedium)
            Text(
                "两条路各自独立，选一条开始就行。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            clearedNotice?.let { Notice(it) }
            captureNotice?.let { Notice(it) }

            // The two routes take all remaining height, so neither can ever be pushed off screen.
            Row(
                Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                RouteCard(
                    eyebrow = "路径 A",
                    title = "复制文本",
                    summary = "粘贴或分享一段聊天，先看懂沟通状态，再决定怎么回。",
                    accent = MaterialTheme.colorScheme.primary,
                    glyph = Glyph.TEXT,
                    modifier = Modifier.weight(1f)
                ) {
                    Button(onClick = { open(null) }, modifier = Modifier.fillMaxWidth()) {
                        Text("粘贴聊天")
                    }
                    OutlinedButton(
                        onClick = { open(DemoConversations.A) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Demo A") }
                }
                RouteCard(
                    eyebrow = "路径 B",
                    title = "桌宠截图",
                    summary = "让桌宠浮在聊天上方，框选屏幕内容，分析直接出来。",
                    accent = MaterialTheme.colorScheme.secondary,
                    glyph = Glyph.SCREEN,
                    modifier = Modifier.weight(1f)
                ) {
                    // One primary button whose meaning follows the permission/service state, so the
                    // card never shows an action the user cannot take yet.
                    when {
                        !granted -> Button(
                            onClick = { OverlayPermission.request(context) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("去授权") }

                        running -> Button(
                            onClick = {
                                FloatingAssistantService.stop(context)
                                running = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("关闭桌宠") }

                        else -> Button(
                            onClick = { running = FloatingAssistantService.start(context) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("开启桌宠") }
                    }
                    OutlinedButton(
                        onClick = onRequestCapture,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("截屏分析") }
                }
            }

            EngineNote()

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = onOpenSkins) { Text("桌宠皮肤") }
                TextButton(onClick = onOpenHelp) { Text("使用说明") }
                TextButton(onClick = onOpenSettings) { Text("引擎设置") }
            }
        }
    }
}

/** Which picture a route card draws. */
private enum class Glyph { TEXT, SCREEN }

/**
 * One of the two routes, as a tall card that shares the page with its sibling.
 *
 * Because the cards absorb every spare pixel, a phone with a tall screen leaves a lot of room
 * between the summary and the buttons. The glyph is what fills it: at tablet width the card can be
 * ~570dp tall against ~280dp of text and buttons, and that much blank space reads as an unfinished
 * layout. It is drawn rather than shipped — the app carries no image assets (the desk pet and the
 * skin previews are Canvas too) — so the picture costs nothing in APK size.
 *
 * All the slack goes to one `weight(1f)` box holding the glyph. A `Column` measures its fixed
 * children first and hands the weighted one only the remainder, so the labels and the action stack
 * always get their full intrinsic height and the decorative glyph is the thing that yields — down
 * to nothing on a short screen. An earlier version gave the slack to two `weight(1f)` spacers
 * instead; when the content was taller than the card those spacers collapsed to zero and the action
 * stack was pushed past the `Card`'s clip, which is how the buttons vanished on shorter screens
 * while the labels stayed visible. The summary is capped at two lines for the same reason: text
 * that cannot grow can never take the buttons' space.
 */
@Composable private fun RouteCard(
    eyebrow: String,
    title: String,
    summary: String,
    accent: Color,
    glyph: Glyph,
    modifier: Modifier = Modifier,
    actions: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxHeight(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        // The card gets whatever height is left after the header, the notices and the footer row,
        // and that varies a lot — a tall phone leaves ~600dp, a short or font-scaled screen far
        // less. Measuring it here is what makes the layout independent of any guess about the
        // device. Below the threshold the summary drops to one line and the spacing tightens: at
        // that size the labels and the buttons alone nearly fill the card, and the second line of
        // explanation is the cheapest thing to give up — a button never is.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxHeight < 260.dp
            Column(
                Modifier.fillMaxSize().padding(if (compact) 10.dp else 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)
            ) {
                Text(
                    eyebrow,
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                    fontWeight = FontWeight.SemiBold
                )
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (compact) 1 else 2,
                    overflow = TextOverflow.Ellipsis
                )
                Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    RouteGlyph(
                        glyph,
                        accent,
                        Modifier.sizeIn(maxWidth = 104.dp, maxHeight = 104.dp).fillMaxSize()
                    )
                }
                actions()
            }
        }
    }
}

/**
 * The route's picture: a chat bubble for the text route, the desk pet's head for the screen route.
 *
 * Everything is a fraction of the square's side, so the glyph stays correct at any size, and both
 * are single-tone outlines in the card's accent colour — no second colour is needed, which keeps
 * them legible on the white card without the kernel knowing its background.
 */
@Composable private fun RouteGlyph(kind: Glyph, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        // Draw inside the largest centred square rather than the raw box: this glyph sits in the
        // slot that absorbs whatever height the card has left, so on a short screen it can be
        // handed a slot wider than it is tall, and using the box's own width and height there
        // would squash it.
        val side = min(size.width, size.height)
        translate((size.width - side) / 2f, (size.height - side) / 2f) {
            drawRouteGlyph(kind, tint, side)
        }
    }
}

/** [RouteGlyph]'s actual drawing, in a square of side `s`. */
private fun DrawScope.drawRouteGlyph(kind: Glyph, tint: Color, s: Float) {
    when (kind) {
        // A rounded speech bubble with a tail and three lines of "text".
        Glyph.TEXT -> {
            val stroke = s * 0.055f
            drawRoundRect(
                color = tint,
                topLeft = Offset(s * 0.06f, s * 0.12f),
                size = Size(s * 0.88f, s * 0.62f),
                cornerRadius = CornerRadius(s * 0.20f),
                style = Stroke(width = stroke)
            )
            drawPath(
                Path().apply {
                    moveTo(s * 0.26f, s * 0.73f)
                    lineTo(s * 0.21f, s * 0.94f)
                    lineTo(s * 0.44f, s * 0.73f)
                    close()
                },
                color = tint
            )
            // Ragged right edge on the last line, so it reads as prose and not as a table.
            drawLine(tint, Offset(s * 0.22f, s * 0.30f), Offset(s * 0.78f, s * 0.30f), stroke, StrokeCap.Round)
            drawLine(tint, Offset(s * 0.22f, s * 0.44f), Offset(s * 0.78f, s * 0.44f), stroke, StrokeCap.Round)
            drawLine(tint, Offset(s * 0.22f, s * 0.58f), Offset(s * 0.58f, s * 0.58f), stroke, StrokeCap.Round)
        }
        // The desk pet's head: two ears behind a rounded face, eyes and a smile in front.
        Glyph.SCREEN -> {
            val stroke = s * 0.055f
            drawCircle(tint, s * 0.11f, Offset(s * 0.27f, s * 0.21f))
            drawCircle(tint, s * 0.11f, Offset(s * 0.73f, s * 0.21f))
            drawRoundRect(
                color = tint,
                topLeft = Offset(s * 0.11f, s * 0.22f),
                size = Size(s * 0.78f, s * 0.64f),
                cornerRadius = CornerRadius(s * 0.27f),
                style = Stroke(width = stroke)
            )
            drawCircle(tint, s * 0.055f, Offset(s * 0.34f, s * 0.50f))
            drawCircle(tint, s * 0.055f, Offset(s * 0.66f, s * 0.50f))
            drawArc(
                color = tint,
                startAngle = 25f,
                sweepAngle = 130f,
                useCenter = false,
                topLeft = Offset(s * 0.38f, s * 0.57f),
                size = Size(s * 0.24f, s * 0.17f),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
    }
}

/**
 * A transient session message ("本次内容已清除", a capture failure reason).
 *
 * Deliberately a thin strip rather than the full [com.flowai.communication.ui.components.InfoCard]
 * the older list-style page used: it appears and disappears, and the home page has no room to
 * spend a title row on it.
 */
@Composable private fun Notice(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(14.dp)
    ) {
        Text(
            text,
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            // A capture failure reason can be long. Left unbounded it would wrap to several lines
            // and eat the route cards' height, which is the one thing this page cannot spare.
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
