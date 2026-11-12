package com.che.ls05

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Telefon açılınca veya uygulama güncellenince servisi yeniden başlatır (kullanıcı durdurmadıysa). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (Prefs.serviceEnabled(context) && Prefs.address(context) != null) {
            WatchService.start(context)
        }
    }
}
