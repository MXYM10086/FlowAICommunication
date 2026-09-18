package com.flowai.communication.system
import com.flowai.communication.data.model.ContextCapsule
interface ScreenCaptureProvider { fun capture(): ContextCapsule? }
/** Intentionally disabled in phase one; never requests a permission. */
class StubScreenCaptureProvider : ScreenCaptureProvider { override fun capture(): ContextCapsule? = null }
