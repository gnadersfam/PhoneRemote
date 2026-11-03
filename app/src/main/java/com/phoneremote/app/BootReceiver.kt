package com.phoneremote.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the background listener again after the phone restarts or the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val a: String = intent.action ?: return
        if (a != Intent.ACTION_BOOT_COMPLETED && a != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val token: String = context.getSharedPreferences("remote", Context.MODE_PRIVATE).getString("token", "") ?: ""
        if (token.isEmpty()) return
        try {
            context.startForegroundService(Intent(context, RingService::class.java))
        } catch (e: Exception) {
        }
    }
}
