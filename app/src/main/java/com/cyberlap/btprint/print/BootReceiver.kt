package com.cyberlap.btprint.print

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cyberlap.btprint.AppLog
import com.cyberlap.btprint.Prefs

/** Restores the always-ready service after a reboot when the user enabled it. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON" &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        AppLog.init(context)
        if (Prefs(context).alwaysReady) {
            AppLog.i("Boot", "starting always-ready service after ${intent.action}")
            JobRunnerService.startReady(context)
        }
    }
}
