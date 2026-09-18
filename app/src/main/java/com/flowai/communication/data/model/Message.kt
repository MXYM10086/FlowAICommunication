package com.flowai.communication.data.model
data class Message(val id: String, val speaker: Speaker, val text: String, val order: Int)
enum class Speaker { ME, OTHER, OTHER_2, UNKNOWN }
