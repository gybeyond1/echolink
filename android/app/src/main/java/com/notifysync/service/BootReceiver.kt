package com.notifysync.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.notifysync.data.AuthManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            if (AuthManager.isLoggedIn) {
                SyncService.start(context)
            }
        }
    }
}
