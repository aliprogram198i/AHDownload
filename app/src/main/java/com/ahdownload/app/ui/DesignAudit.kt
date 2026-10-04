package com.ahdownload.app.ui

import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale

// Production verification branch: no runtime behavior change.
object DesignAudit {
    private val lock = Any()
    private val screenVisits = linkedMapOf<String, Int>()
    private val componentCounts = linkedMapOf<String, Int>()
    private var currentScreen = "unknown"
    private var lastUpdated = 0L

    private val screenContracts = linkedMapOf(
        "home" to ScreenContract("الرئيسية", "Home", "header;url_input;primary_action;analysis_result"),
        "downloads" to ScreenContract("التنزيلات", "Downloads", "summary;filters;download_cards;actions"),
        "studio" to ScreenContract("Smart Studio", "Studio", "source_picker;metadata;processing_actions;result"),
        "settings" to ScreenContract("الإعدادات", "Settings", "header;download_preferences;accounts;diagnostics;privacy;about"),
        "accounts" to ScreenContract("الحسابات", "Accounts", "platform_cards;session_status;login_action"),
        "diagnostics" to ScreenContract("سجل التطبيق", "Diagnostics", "audit_snapshot;runtime_log;refresh;copy;share;clear")
    )

    private data class ScreenContract(
        val displayName: String,
        val technicalName: String,
        val expectedElements: String
    )

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

    fun recordInteraction(screen: String, component: String, action: String) {
        if (screen.isBlank() || component.isBlank() || action.isBlank()) return
        recordComponent("interaction:" + component + ":" + action)
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
            appendLine("schema=2")
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
            appendLine("brand_gradient=#4C6FFF -> #8B5CF6 -> #20C7B7")
            appendLine("brand_gradient_soft=#18203A -> #211A35 -> #122C2B")
            appendLine("light.primary=#315BFF")
            appendLine("light.secondary=#0B9F9A")
            appendLine("light.tertiary=#7B4DFF")
            appendLine("light.background=#F7F9FC")
            appendLine("light.surface_variant=#E9EDF4")
            appendLine("dark.primary=#6E8BFF")
            appendLine("dark.secondary=#32D6C5")
            appendLine("dark.tertiary=#AA83FF")
            appendLine("dark.background=#080A10")
            appendLine("dark.surface=#10131B")
            appendLine("dark.surface_variant=#161A24")
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
            appendLine("[SCREEN_CONTRACTS]")
            screenContracts.forEach { (_, c) ->
                appendLine(c.technicalName + " display_name=" + c.displayName + " expected=" + c.expectedElements)
            }
            appendLine()
            appendLine("[RUNTIME_SCREENS]")
            if (screenData.isEmpty()) appendLine("none_observed=true")
            screenData.forEach { (name, visits) ->
                val contract = screenContracts[name]
                appendLine(
                    name + " visits=" + visits +
                        " known=" + (contract != null) +
                        (contract?.let { " expected=" + it.expectedElements } ?: "")
                )
            }
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
            appendLine("[LIMITATIONS]")
            appendLine("compose_tree_introspection=false")
            appendLine("pixel_geometry_capture=false")
            appendLine("screen_contracts=declared_design_targets")
            appendLine("runtime_components=explicitly_observed_registrations")
            appendLine()
            appendLine("[IMPLEMENTATION_NOTE]")
            appendLine("This audit reports declared design contracts plus runtime observations; it does not invent an unobserved Compose tree or pixel geometry.")
        }
    }
}
