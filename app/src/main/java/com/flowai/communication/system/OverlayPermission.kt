package com.flowai.communication.system

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Whether this app may draw over other apps, and how to ask for it.
 *
 * SYSTEM_ALERT_WINDOW is a special permission: it cannot be requested with the normal runtime
 * dialog. The user has to grant it on a dedicated Settings screen, so the flow is
 * "check -> send the user there -> check again on resume".
 */
object OverlayPermission {

    /** Safe to call on any API level; false when the app may not draw overlays. */
    fun isGranted(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * Opens the system screen where the user can allow overlays for this app specifically.
     * Callers must handle the case where no activity handles the intent (some ROMs).
     */
    fun requestIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** True when the settings screen could be opened; false if the device has no such screen. */
    fun request(context: Context): Boolean = runCatching {
        context.startActivity(requestIntent(context))
    }.isSuccess
}
