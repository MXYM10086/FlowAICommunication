package com.flowai.communication.system

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.util.Log

/**
 * Asks for screen sharing once, the moment the floating window is switched on.
 *
 * MediaProjection consent cannot be skipped, but it can be moved: asking here — while the user is
 * consciously turning the assistant on — means the later capture needs no dialog at all. The
 * granted session then lives in [ScreenCaptureService] for as long as the floating window runs, so
 * framing a region and confirming it reads the conversation immediately, off the current screen.
 *
 * Asking at capture time instead was the old behaviour, and it cost accuracy as well as smoothness:
 * the dialog's own round trip left the capture sampling a screen that was mid-transition.
 *
 * A decline is remembered for the process lifetime's successor runs via prefs, so switching the
 * pet off and on again does not re-ask; capturing still offers consent through the normal chain.
 */
class ScreenShareGateActivity : ComponentActivity() {

    private val consent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) {
            Log.i(TAG, "screen share pre-authorisation declined")
            setDeclined(true)
            finish()
            return@registerForActivityResult
        }
        val started = ScreenCaptureService.start(applicationContext, result.resultCode, data)
        Log.i(TAG, "screen share session established: $started")
        setDeclined(false)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No UI of its own: the system consent dialog is the whole screen.
        if (savedInstanceState == null) {
            consent.launch(
                getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()
            )
        }
    }

    private fun setDeclined(declined: Boolean) {
        applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DECLINED, declined).apply()
    }

    companion object {
        private const val TAG = "FlowAI"
        private const val PREFS = "flowai_screen_share"
        private const val KEY_DECLINED = "pre_authorisation_declined"

        fun intent(context: Context): Intent =
            Intent(context, ScreenShareGateActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** True when the user turned the pre-authorisation down before; do not nag again. */
        fun wasDeclined(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_DECLINED, false)
    }
}
