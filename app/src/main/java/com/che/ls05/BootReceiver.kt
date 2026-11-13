package com.che.ls05

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Telefon açılınca veya uygulama güncellenince servisi yeniden başlatır (kullanıcı durdurmadıysa).
 * Açılış, güncelleme, saat ya da saat dilimi değişimi ve alarm izni değişiminde not zamanlamasını yeniler.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        when (action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (Prefs.serviceEnabled(context) && Prefs.address(context) != null) {
                    WatchService.start(context)
                }
                Reminders.reschedule(context)
            }
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            -> {
                Reminders.reschedule(context)
                WatchHub.settingsChanged()
            }
        }
    }
}
