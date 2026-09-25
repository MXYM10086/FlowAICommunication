package com.flowai.communication.ui.action

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.flowai.communication.data.model.ActionObject
import com.flowai.communication.system.CalendarTimeParser
import com.flowai.communication.system.CalendarWriter

/**
 * An event card that can write itself into the system calendar.
 *
 * Everything the feature needs lives here: time parsing, the confirmation dialog, the runtime
 * permission request with its denial explanation, the write itself, and the copy-to-memo
 * fallback (Android has no general memo API). State is card-local, so the screen, the ViewModel
 * and every existing flow stay untouched.
 */
@Composable
fun EventCalendarCard(event: ActionObject.Event) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val writer = remember { CalendarWriter(context) }

    // Non-null while the confirmation dialog is open; the value is the parsed start instant.
    var pendingStart by remember { mutableStateOf<Long?>(null) }
    // Non-null while a write is paused waiting for the permission dialog result.
    var awaitingGrantStart by remember { mutableStateOf<Long?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var showDeniedDialog by remember { mutableStateOf(false) }

    fun say(text: String, isError: Boolean) {
        message = text
        messageIsError = isError
    }

    fun writeNow(start: Long) {
        when (val r = writer.writeEvent(event.title, start, location = event.location, description = memoText(event))) {
            is CalendarWriter.Result.Success ->
                say("已写入系统日历：${event.title}（${CalendarTimeParser.format(start)}）", isError = false)
            is CalendarWriter.Result.Failed -> say(r.message, isError = true)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val start = awaitingGrantStart
        awaitingGrantStart = null
        // Listing calendars needs READ_CALENDAR, inserting needs WRITE_CALENDAR: only write
        // when the user granted the whole pair, otherwise the provider throws mid-way.
        val granted = grants[Manifest.permission.WRITE_CALENDAR] == true &&
            grants[Manifest.permission.READ_CALENDAR] == true
        when {
            granted && start != null -> writeNow(start)
            start != null -> showDeniedDialog = true
        }
    }

    fun requestPermissionThenWrite(start: Long) {
        awaitingGrantStart = start
        permissionLauncher.launch(
            arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        )
    }

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("事件 · ${event.title}", style = MaterialTheme.typography.titleMedium)
            Text("时间：${event.time ?: "待确认"}", style = MaterialTheme.typography.bodyMedium)
            Text("地点：${event.location ?: "待确认"}", style = MaterialTheme.typography.bodyMedium)
            Text("相关人员：${event.participants.joinToString("、")}", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val parsed = CalendarTimeParser.parse(event.time)
                    if (parsed == null) {
                        say("无法从「${event.time ?: "待确认"}」解析出具体时间，未写入日历；可复制日程文本到备忘录保存", isError = true)
                    } else {
                        pendingStart = parsed
                    }
                }) { Text("写入系统日历") }
                OutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString(memoText(event)))
                    say("日程文本已复制，可打开备忘录粘贴保存", isError = false)
                }) { Text("复制到备忘录") }
            }
            message?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium,
                    color = if (messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        }
    }

    // Confirmation before anything touches the calendar: the user sees exactly what will be written.
    pendingStart?.let { start ->
        AlertDialog(
            onDismissRequest = { pendingStart = null },
            title = { Text("写入系统日历") },
            text = {
                Text("事件：${event.title}\n" +
                    "时间：${CalendarTimeParser.format(start)}\n" +
                    "地点：${event.location ?: "待确认"}\n" +
                    "时长：${CalendarWriter.DEFAULT_DURATION_MINUTES} 分钟")
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingStart = null
                    if (writer.hasPermission()) writeNow(start) else requestPermissionThenWrite(start)
                }) { Text("确认写入") }
            },
            dismissButton = { TextButton(onClick = { pendingStart = null }) { Text("取消") } }
        )
    }

    // Denial is explained, not swallowed — and the memo fallback is one tap away.
    if (showDeniedDialog) {
        AlertDialog(
            onDismissRequest = { showDeniedDialog = false },
            title = { Text("日历权限被拒绝") },
            text = { Text("FlowAI 需要「读写日历」权限才能写入日程。你可以再次授权，或把日程文本复制到备忘录保存。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeniedDialog = false
                    // Re-parse: cheap, and avoids keeping another piece of state across dialogs.
                    CalendarTimeParser.parse(event.time)?.let { requestPermissionThenWrite(it) }
                }) { Text("再次授权") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDeniedDialog = false
                    clipboard.setText(AnnotatedString(memoText(event)))
                    say("日程文本已复制，可打开备忘录粘贴保存", isError = false)
                }) { Text("复制到备忘录") }
            }
        )
    }
}

/** The memo-friendly rendering of an event; also used as the calendar event's description. */
private fun memoText(event: ActionObject.Event): String =
    "事件：${event.title}\n时间：${event.time ?: "待确认"}\n地点：${event.location ?: "待确认"}\n相关人员：${event.participants.joinToString("、")}"
