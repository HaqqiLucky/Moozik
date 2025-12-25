package com.liam.moozik

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class NotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        // Kirim sinyal ke MainActivity
        context?.sendBroadcast(Intent("MUSIC_ACTION").apply {
            putExtra("action_name", intent?.action)
        })
    }
}