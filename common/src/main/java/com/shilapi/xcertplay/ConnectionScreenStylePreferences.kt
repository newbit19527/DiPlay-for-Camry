// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.content.Context

/**
 * Opt-in switch for the Apple/CarPlay-style full-screen connection waiting screen.
 *
 * The classic native [connection.ConnectionProgressPanel] layout remains the default
 * (and the automatic fallback when WebView is unavailable on a head unit). Nothing in
 * the connection state pipeline depends on this flag: the host keeps feeding
 * [connection.CamryConnectionScreen], which mirrors every call into the legacy panel,
 * so switching screens never loses attempt history, timings, guidance or actions.
 *
 * Saved in the same "diplay" SharedPreferences file as the other settings overrides
 * (see [SettingsLayoutPreferences]); no new storage, no new permissions, no new deps.
 */
object ConnectionScreenStylePreferences {

    private const val KEY_APPLE_STYLE = "apple_connection_screen_enabled"

    /** Default false: keep the proven native waiting screen until the new one is stable. */
    fun isAppleStyleEnabled(context: Context): Boolean =
        context.getSharedPreferences("diplay", Context.MODE_PRIVATE)
            .getBoolean(KEY_APPLE_STYLE, false)

    fun saveAppleStyleEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_APPLE_STYLE, enabled).apply()
    }
}
