package io.github.surioustype.localscribe.di

import android.content.Context

class AppPreferences(context: Context) {
    private val values = context.getSharedPreferences("ui_preferences", Context.MODE_PRIVATE)
    var libraryFilter: String
        get() =
            values
                .getString("library_filter", null)
                ?.takeIf { it in setOf("all", "ready", "new", "active") }
                ?: recordingsFilter
        set(value) = values.edit().putString("library_filter", value).apply()
    var librarySort: String
        get() =
            values
                .getString("library_sort", null)
                ?.takeIf { it in setOf("added", "recorded", "title", "long", "short") }
                ?: when (recordingsSort) {
                    "az", "za" -> "title"
                    "long" -> "long"
                    "short" -> "short"
                    else -> "added"
                }
        set(value) = values.edit().putString("library_sort", value).apply()
    var onboardingComplete: Boolean
        get() = values.getBoolean("onboarding_complete", false)
        set(value) = values.edit().putBoolean("onboarding_complete", value).apply()
    var automaticUpdates: Boolean
        get() = values.getBoolean("automatic_updates", true)
        set(value) = values.edit().putBoolean("automatic_updates", value).apply()
    var dynamicColors: Boolean
        get() = values.getBoolean("dynamic_colors", false)
        set(value) = values.edit().putBoolean("dynamic_colors", value).apply()
    var darkTheme: Boolean
        get() = values.getBoolean("dark_theme", false)
        set(value) = values.edit().putBoolean("dark_theme", value).apply()
    var appearanceMode: String
        get() = values.getString("appearance_mode", "SYSTEM") ?: "SYSTEM"
        set(value) = values.edit().putString("appearance_mode", value).apply()
    var recordingsSort: String
        get() =
            values.getString("recordings_sort", "new")?.takeIf {
                it in setOf("new", "old", "az", "za", "long", "short")
            } ?: "new"
        set(value) = values.edit().putString("recordings_sort", value).apply()
    var recordingsFilter: String
        get() =
            values.getString("recordings_filter", "all")?.takeIf {
                it in setOf("all", "ready", "new")
            } ?: "all"
        set(value) = values.edit().putString("recordings_filter", value).apply()
}
