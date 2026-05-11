package com.alaimtiaz.calendaralarm

import android.content.Context
import android.content.SharedPreferences

/**
 * Feature Flags — runtime toggles for experimental features.
 *
 * Default: ALL FLAGS OFF — app behaves identically to stable Build #42.
 * Toggle ON to enable experimental behavior.
 * Toggle OFF to instantly revert to Build #42 behavior (no restart needed).
 */
object FeatureFlags {

    private const val PREFS_NAME = "feature_flags"

    // ━━━ Flag keys ━━━
    private const val KEY_ARCHIVE_BUTTON = "flag_archive_button_enabled"
    private const val KEY_NEW_ALARM_OVERLAY = "flag_new_alarm_overlay_enabled"

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ━━━ Public API ━━━

    /**
     * "ابحث في تقويم" button opens CalendarArchive instead of system chooser.
     * Default: OFF (Build #42 behavior).
     */
    fun isArchiveButtonEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ARCHIVE_BUTTON, false)
    }

    fun setArchiveButtonEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ARCHIVE_BUTTON, enabled).apply()
    }

    /**
     * Use new alarm overlay design (colored snooze buttons + blue card border +
     * expanded "snooze more" dialog + removed live clock).
     * Default: OFF (Build #42 original overlay).
     */
    fun isNewAlarmOverlayEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_NEW_ALARM_OVERLAY, false)
    }

    fun setNewAlarmOverlayEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_NEW_ALARM_OVERLAY, enabled).apply()
    }
}
