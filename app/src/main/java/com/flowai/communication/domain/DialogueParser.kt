package com.flowai.communication.domain
import com.flowai.communication.data.model.*

interface DialogueParser { fun parse(rawText: String): List<Message> }

/** Each nonblank line is a message. Only an explicit short prefix is a speaker. */
class PlainTextDialogueParser : DialogueParser {
    override fun parse(rawText: String): List<Message> {
        val others = linkedMapOf<String, Speaker>()
        return rawText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { line ->
            val prefix = Regex("^([^：:]{1,20})[：:](.*)$").matchEntire(line)
            val label = prefix?.groupValues?.get(1)?.trim()
            val recognized = label != null && !label.any { it.isDigit() } &&
                !label.contains(" ") && label.lowercase() !in listOf("http", "https")
            val text = if (recognized) prefix!!.groupValues[2].trim() else line
            if (text.isBlank()) null else {
                val speaker = when {
                    !recognized -> Speaker.UNKNOWN
                    label in listOf("我", "自己", "ME", "me") -> Speaker.ME
                    else -> others.getOrPut(label!!) {
                        when (others.size) { 0 -> Speaker.OTHER; 1 -> Speaker.OTHER_2; else -> Speaker.UNKNOWN }
                    }
                }
                speaker to text
            }
        }.mapIndexed { i, (speaker, text) -> Message("message-$i", speaker, text, i) }.toList()
    }
}
