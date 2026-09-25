package com.flowai.communication.ui.ocr

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The editing pass between a capture and its analysis.
 *
 * A chat screenshot rarely recognises perfectly, and a single wrong character can change who said
 * what, so the recognised text is offered for correction before it is analysed. The draft lives in
 * the ViewModel only while this page is up (see `FlowViewModel.ocrDraft`): it survives rotation but
 * is dropped the moment the page is left, and never written to disk.
 *
 * Styled after InputScreen: a heading over one growing field, and the actions in a raised bottom
 * bar so they stay reachable with the keyboard open. The field itself scrolls — it owns the middle
 * of the screen, while the heading and the actions stay put.
 */
@Composable fun OcrPreviewScreen(
    draft: String,
    onEdit: (String) -> Unit,
    onSubmit: () -> Unit,
    onRecapture: () -> Unit,
) {
    Column(Modifier.fillMaxSize().imePadding()) {
        // One line only: every pixel of header is a pixel the field loses, and the field has to
        // keep 60%+ of the screen to stay comfortable for a whole conversation. headlineMedium
        // wrapped this sentence onto two lines and dropped the field under the 60% mark.
        Text(
            "请确认识别结果，可直接编辑修正",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp)
        )
        // weight, not a fixed fraction: the field takes everything the heading and the action bar
        // leave over — well above half the screen — and scrolls a long recognition inside itself.
        OutlinedTextField(
            value = draft,
            onValueChange = onEdit,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            label = { Text("识别结果") },
            supportingText = { Text("${draft.length} / 20,000") },
        )
        Surface(tonalElevation = 2.dp) {
            // One row, not two stacked buttons: a stacked pair cost ~9% of the screen height,
            // which is exactly what the field needs to stay over 60%.
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRecapture, modifier = Modifier.weight(1f)) {
                        Text("重新截取")
                    }
                    Button(
                        onClick = onSubmit,
                        enabled = draft.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) { Text("确认分析") }
                }
                if (draft.isBlank()) {
                    Text(
                        "识别结果为空：重新截取，或直接在上面补全文字",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
