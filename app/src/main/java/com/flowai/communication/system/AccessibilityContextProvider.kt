package com.flowai.communication.system
import com.flowai.communication.data.model.ContextCapsule
interface AccessibilityContextProvider { fun capture(): ContextCapsule? }
/** Intentionally disabled in phase one; never requests a permission. */
class StubAccessibilityContextProvider : AccessibilityContextProvider { override fun capture(): ContextCapsule? = null }
