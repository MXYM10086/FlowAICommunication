package com.flowai.communication.system
import com.flowai.communication.data.model.ContextCapsule
interface OverlayContextProvider { fun capture(): ContextCapsule? }
/** Intentionally disabled in phase one; never requests a permission. */
class StubOverlayContextProvider : OverlayContextProvider { override fun capture(): ContextCapsule? = null }
