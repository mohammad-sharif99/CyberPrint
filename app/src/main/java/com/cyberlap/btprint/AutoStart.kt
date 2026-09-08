package com.cyberlap.btprint

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Opens the OEM "auto-start / auto-launch" management screen. On Transsion
 * (Tecno/Infinix/itel), Xiaomi, Huawei, Oppo, Vivo and others the system will
 * not spawn a third-party print service for the print dialog unless the app is
 * whitelisted there - nothing in app code can override it.
 */
object AutoStart {
    private val targets = listOf(
        // Transsion / HiOS / XOS (Tecno, Infinix, itel)
        "com.transsion.phonemaster" to "com.cyin.himgr.autostart.AutoStartActivity",
        "com.transsion.phonemanager" to "com.cyin.himgr.autostart.AutoStartActivity",
        // Xiaomi / MIUI
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        // Huawei / Honor
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        // Oppo / Realme
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        // Vivo / iQOO
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
        // Samsung
        "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
        // OnePlus
        "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
        // Asus
        "com.asus.mobilemanager" to "com.asus.mobilemanager.autostart.AutoStartActivity",
    )

    /** @return true when an OEM screen opened; false when it fell back to app details. */
    fun open(ctx: Context): Boolean {
        for ((pkg, cls) in targets) {
            val i = Intent().setComponent(ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (ctx.packageManager.resolveActivity(i, 0) != null) {
                try { ctx.startActivity(i); AppLog.i("AutoStart", "opened $pkg/$cls"); return true }
                catch (e: Exception) { AppLog.e("AutoStart", "$pkg/$cls failed: ${e.message}") }
            }
        }
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {}
        return false
    }
}
