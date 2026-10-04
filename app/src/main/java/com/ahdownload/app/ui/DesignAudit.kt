package com.ahdownload.app.ui

import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale

object DesignAudit {
    private val lock = Any()
    private val screenVisits = linkedMapOf<String, Int>()
    private val componentCounts = linkedMapOf<String, Int>()
    private var currentScreen = "unknown"
    private var lastUpdated = 0L

    fun recordScreen(screen: String) {
        synchronized(lock) {
            currentScreen = screen
            screenVisits[screen] = (screenVisits[screen] ?: 0) + 1
            lastUpdated = System.currentTimeMillis()
        }
    }

    fun recordComponent(type: String) {
        synchronized(lock) {
            componentCounts[type] = (componentCounts[type] ?: 0) + 1
            lastUpdated = System.currentTimeMillis()
        }
    }

    fun snapshot(context: Context): String {
        val now = System.currentTimeMillis()
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ssXXX", Locale.US).format(Date(now))
        val screenData: Map<String, Int>
        val componentData: Map<String, Int>
        val current: String
        val updated: Long
        synchronized(lock) {
            screenData = LinkedHashMap(screenVisits)
            componentData = LinkedHashMap(componentCounts)
            current = currentScreen
            updated = lastUpdated
        }
        val dm = context.resources.displayMetrics
        return buildString {
            appendLine("AHDownload UI DESIGN AUDIT")
            appendLine("schema=1")
            appendLine("generated_at=" + date)
            appendLine("app_package=" + context.packageName)
            appendLine("android_sdk=" + Build.VERSION.SDK_INT)
            appendLine("device_density=" + "%.2f".format(Locale.US, dm.density))
            appendLine("viewport_px=" + dm.widthPixels + "x" + dm.heightPixels)
            appendLine("design_system=AHDownloadTheme")
            appendLine("interaction_system=unified-gradient-primary-actions")
            appendLine("current_screen=" + current)
            appendLine("last_runtime_update=" + updated)
            appendLine()
            appendLine("[PALETTE]")
            appendLine("brand_gradient=#315BFF -> #7B4DFF -> #16B8B1")
            appendLine("brand_gradient_soft=#E7ECFF -> #F0E9FF -> #E2F8F6")
            appendLine("light.primary=#315BFF")
            appendLine("light.secondary=#0B9F9A")
            appendLine("light.tertiary=#7B4DFF")
            appendLine("light.background=#F7F9FC")
            appendLine("light.surface_variant=#E9EDF4")
            appendLine("dark.primary=#B9C7FF")
            appendLine("dark.secondary=#7BE0DB")
            appendLine("dark.tertiary=#D0BFFF")
            appendLine("dark.background=#0D1015")
            appendLine("dark.surface_variant=#20252D")
            appendLine()
            appendLine("[SHAPE_TOKENS]")
            appendLine("extra_small=8dp")
            appendLine("small=12dp")
            appendLine("medium=16dp")
            appendLine("large=22dp")
            appendLine("extra_large=28dp")
            appendLine("primary_button=16dp")
            appendLine("card=20-28dp")
            appendLine()
            appendLine("[TYPOGRAPHY]")
            appendLine("display/headline=bold")
            appendLine("title=semi_bold")
            appendLine("label=semi_bold")
            appendLine()
            appendLine("[RUNTIME_SCREENS]")
            if (screenData.isEmpty()) appendLine("none_observed=true")
            screenData.forEach { (name, visits) -> appendLine(name + " visits=" + visits) }
            appendLine()
            appendLine("[RUNTIME_COMPONENTS]")
            if (componentData.isEmpty()) appendLine("none_observed=true")
            componentData.forEach { (type, count) -> appendLine(type + " observed=" + count) }
            appendLine()
            appendLine("[PRIVACY]")
            appendLine("user_input=excluded")
            appendLine("urls=excluded")
            appendLine("cookies=excluded")
            appendLine("tokens=excluded")
            appendLine("passwords=excluded")
            appendLine("account_credentials=excluded")
            appendLine()
            appendLine("[IMPLEMENTATION_NOTE]")
            appendLine("Runtime section contains observed registrations, not a fabricated Compose-tree dump.")
        }
    }
}
