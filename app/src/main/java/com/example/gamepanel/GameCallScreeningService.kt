package com.example.gamepanel

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService

/**
 * Menolak panggilan masuk selama game aktif, HANYA jika:
 *  • pengguna mengaktifkan "Tolak panggilan", dan
 *  • aplikasi memegang peran Call Screening (izin resmi Android 10+).
 */
class GameCallScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        val b = CallScreeningService.CallResponse.Builder()
        val incoming = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            callDetails.callDirection == Call.Details.DIRECTION_INCOMING
        if (incoming && PanelSettings.tool(this, "call_block") && GamePanelController.gameActive) {
            b.setDisallowCall(true)
                .setRejectCall(true)
                .setSkipNotification(true)
        }
        respondToCall(callDetails, b.build())
    }
}
