package com.flowai.communication.system
/** Text share is handled by MainActivity's SEND intent-filter; this boundary remains for image/OCR share. */
interface ShareReceiver { fun receive(text: String): Boolean }
class StubShareReceiver : ShareReceiver { override fun receive(text: String) = false }
