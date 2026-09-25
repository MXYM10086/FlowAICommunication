package com.flowai.communication.ui

/**
 * The screens of the app, in the order a session usually passes through them.
 *
 * [OCR_PREVIEW] is legacy: capture analyses the screenshot image directly now, so nothing routes
 * through the recognised-text editing pass any more — the page stays only so an in-flight session
 * from an older build cannot land on a missing screen.
 *
 * [CHAT] hangs off [ANALYSIS]: follow-up questions about the analysis on screen, answered by the
 * configured model with the analysis and the original payload as grounding.
 *
 * [HELP] is the home page's overflow. The home page is laid out to fit one screen with no
 * scrolling, so everything on it that is explanation rather than action — and the entry points
 * that are occasional rather than one of the two main routes — lives here instead.
 */
enum class Page { HOME, INPUT, OCR_PREVIEW, ANALYSIS, ACTION, CHAT, SETTINGS, SKINS, HELP }
