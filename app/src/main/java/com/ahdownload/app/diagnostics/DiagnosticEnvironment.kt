package com.ahdownload.app.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.ahdownload.app.BuildConfig
import java.util.Locale
import java.util.TimeZone

/**
 * Non-sensitive runtime metadata attached automatically to every diagnostic event.
 * No account identifiers, URLs, cookies, tokens, or device serials are collected.
 */
object DiagnosticEnvironment {
    fun snapshot(context: Context): Map<String, String> {
        val appContext = context.applicationContext
        val packageInfo = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        }.getOrNull()
        val memory = runCatching {
            val manager = appContext.getSystemService(ActivityManager::class.java)
            ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        }.getOrNull()

        return buildMap {
            put("app_package", appContext.packageName)
            put("app_version_name", BuildConfig.VERSION_NAME)
            put("app_version_code", BuildConfig.VERSION_CODE.toString())
            put("app_build_type", BuildConfig.BUILD_TYPE)
            put("app_target_sdk", appContext.applicationInfo.targetSdkVersion.toString())
            put("app_first_install_ms", packageInfo?.firstInstallTime?.toString() ?: "unknown")
            put("app_last_update_ms", packageInfo?.lastUpdateTime?.toString() ?: "unknown")
            put("android_sdk", Build.VERSION.SDK_INT.toString())
            put("android_release", Build.VERSION.RELEASE ?: "unknown")
            put("device_manufacturer", Build.MANUFACTURER.ifBlank { "unknown" })
            put("device_model", Build.MODEL.ifBlank { "unknown" })
            put("device_brand", Build.BRAND.ifBlank { "unknown" })
            put("device_product", Build.PRODUCT.ifBlank { "unknown" })
            put("locale", Locale.getDefault().toLanguageTag())
            put("timezone", TimeZone.getDefault().id)
            put("is_24_hour_format", android.text.format.DateFormat.is24HourFormat(appContext).toString())
            put("process_id", android.os.Process.myPid().toString())
            put("available_memory_bytes", memory?.availMem?.toString() ?: "unknown")
            put("low_memory", memory?.lowMemory?.toString() ?: "unknown")
            put("app_uptime_ms", android.os.SystemClock.elapsedRealtime().toString())
        }
    }
}
