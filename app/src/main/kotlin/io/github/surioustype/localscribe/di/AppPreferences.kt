package io.github.surioustype.localscribe.di

import android.content.Context

class AppPreferences(context: Context) {
    private val values = context.getSharedPreferences("ui_preferences", Context.MODE_PRIVATE)
    var onboardingComplete: Boolean
        get() = values.getBoolean("onboarding_complete", false)
        set(value) = values.edit().putBoolean("onboarding_complete", value).apply()
    var automaticUpdates: Boolean
        get() = values.getBoolean("automatic_updates", true)
        set(value) = values.edit().putBoolean("automatic_updates", value).apply()
    var dynamicColors: Boolean
        get() = values.getBoolean("dynamic_colors", true)
        set(value) = values.edit().putBoolean("dynamic_colors", value).apply()
    var darkTheme: Boolean
        get() = values.getBoolean("dark_theme", false)
        set(value) = values.edit().putBoolean("dark_theme", value).apply()
    var appearanceMode: String
        get() = values.getString("appearance_mode", "SYSTEM") ?: "SYSTEM"
        set(value) = values.edit().putString("appearance_mode", value).apply()
}
