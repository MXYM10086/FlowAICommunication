package com.flowai.communication.ui.skins

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.flowai.communication.system.FloatingAssistantService
import com.flowai.communication.system.pet.PetAccessory
import com.flowai.communication.system.pet.PetSkin
import com.flowai.communication.system.pet.PetSkins
import com.flowai.communication.system.pet.PrefsPetSkinStore
import com.flowai.communication.ui.components.InfoCard

/**
 * Picks the desk pet's skin.
 *
 * The overlay can only cycle skins with a long press; this screen is the full wardrobe, with a
 * sketch of each pet so the choice is not made blind. A selection is saved immediately and, when
 * the floating pet is running, applied to it on the spot.
 */
@Composable
fun PetSkinScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { PrefsPetSkinStore(context.applicationContext) }
    var selectedId by remember { mutableStateOf(store.load()) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("桌宠皮肤", style = MaterialTheme.typography.headlineSmall)
        InfoCard(
            "关于桌宠",
            listOf(
                "悬浮入口是一只小桌宠：会自己眨眼、蹦跳、摇摆、转圈、打盹。",
                "点一下有粒子特效，长按可以直接换成下一款皮肤。",
                "这里的选择会立刻应用到正在运行的桌宠。"
            )
        )

        PetSkins.ALL.forEach { skin ->
            val isSelected = skin.id == selectedId
            Card(
                modifier = Modifier.fillMaxWidth().clickable {
                    store.save(skin.id)
                    selectedId = skin.id
                    // Only reaches a running pet; otherwise the stored choice is applied on start.
                    FloatingAssistantService.applySkin(skin.id)
                },
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surface
                )
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PetBadge(skin)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(skin.name, style = MaterialTheme.typography.titleMedium)
                        Text(skin.description, style = MaterialTheme.typography.bodySmall)
                    }
                    if (isSelected) {
                        Text(
                            "使用中",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        TextButton(onClick = onBack) { Text("返回") }
    }
}

/**
 * A simplified, static sketch of the pet for the picker.
 *
 * Deliberately not the overlay's [com.flowai.communication.system.pet.PetView]: this preview only
 * needs to convey palette and silhouette, and a plain Canvas keeps it cheap inside a scrolling
 * list.
 */
@Composable
private fun PetBadge(skin: PetSkin, size: Dp = 56.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2f
        val cy = h / 2f + h * 0.06f
        val bodyColor = Color(skin.bodyColor)
        val earColor = Color(skin.earColor)
        val eyeColor = Color(skin.eyeColor)
        val cheekColor = Color(skin.cheekColor)
        val auraColor = Color(skin.auraColor)

        when (skin.accessory) {
            PetAccessory.ROUND_EARS -> {
                drawCircle(earColor, radius = w * 0.11f, center = Offset(cx - w * 0.19f, cy - h * 0.21f))
                drawCircle(earColor, radius = w * 0.11f, center = Offset(cx + w * 0.19f, cy - h * 0.21f))
            }
            PetAccessory.POINTED_EARS -> {
                for (side in intArrayOf(-1, 1)) {
                    val path = Path().apply {
                        moveTo(cx + side * w * 0.27f, cy - h * 0.12f)
                        lineTo(cx + side * w * 0.15f, cy - h * 0.34f)
                        lineTo(cx + side * w * 0.03f, cy - h * 0.14f)
                        close()
                    }
                    drawPath(path, earColor)
                }
            }
            PetAccessory.LONG_EARS -> {
                for (side in intArrayOf(-1, 1)) {
                    rotate(degrees = side * -12f, pivot = Offset(cx + side * w * 0.10f, cy - h * 0.26f)) {
                        drawOval(
                            color = earColor,
                            topLeft = Offset(cx + side * w * 0.10f - w * 0.06f, cy - h * 0.26f - h * 0.13f),
                            size = Size(w * 0.12f, h * 0.26f)
                        )
                    }
                }
            }
            PetAccessory.ANTENNA -> {
                drawLine(
                    color = auraColor,
                    start = Offset(cx, cy - h * 0.24f),
                    end = Offset(cx + w * 0.03f, cy - h * 0.36f),
                    strokeWidth = w * 0.02f,
                    cap = StrokeCap.Round
                )
                drawCircle(auraColor, radius = w * 0.035f, center = Offset(cx + w * 0.03f, cy - h * 0.37f))
            }
            PetAccessory.AHOGE -> {
                for (i in -1..1) {
                    drawLine(
                        color = earColor,
                        start = Offset(cx + i * w * 0.05f, cy - h * 0.26f),
                        end = Offset(cx + i * w * 0.06f, cy - h * 0.37f),
                        strokeWidth = w * 0.022f,
                        cap = StrokeCap.Round
                    )
                }
            }
        }

        // Body.
        drawOval(
            color = bodyColor,
            topLeft = Offset(cx - w * 0.33f, cy - h * 0.29f),
            size = Size(w * 0.66f, h * 0.58f)
        )

        // Eyes.
        drawCircle(eyeColor, radius = w * 0.045f, center = Offset(cx - w * 0.115f, cy - h * 0.03f))
        drawCircle(eyeColor, radius = w * 0.045f, center = Offset(cx + w * 0.115f, cy - h * 0.03f))

        // Cheeks.
        val cheek = cheekColor.copy(alpha = 0.55f)
        drawOval(cheek, topLeft = Offset(cx - w * 0.25f, cy + h * 0.04f), size = Size(w * 0.12f, h * 0.07f))
        drawOval(cheek, topLeft = Offset(cx + w * 0.13f, cy + h * 0.04f), size = Size(w * 0.12f, h * 0.07f))
    }
}
