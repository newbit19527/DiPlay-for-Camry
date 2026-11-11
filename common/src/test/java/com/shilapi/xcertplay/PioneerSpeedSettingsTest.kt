package com.shilapi.xcertplay

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import com.shilapi.xcertplay.host.R
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PioneerSpeedSettingsTest {
    @Test fun explicitOptInPersistsAndRendersInCompactFullAndRtlLayouts() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        activity.getSharedPreferences("xcertplay_airplay", 0).edit().remove("pioneer_vehicle_speed_enabled").commit()
        assertFalse(AirPlayPersistence.loadPioneerVehicleSpeedEnabled(activity))
        try {
            for ((name, widthDp, language) in listOf(Triple("compact-zh", 320, "zh-CN"),
                    Triple("full-en", 960, "en-US"), Triple("compact-ar-rtl", 320, "ar-SA"))) {
                val locale = Locale.forLanguageTag(language)
                val config = Configuration(activity.resources.configuration).apply {
                    fontScale = 1.5f; setLocale(locale); setLayoutDirection(locale)
                }
                @Suppress("DEPRECATION")
                activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutDirection = config.layoutDirection
                }
                DiPlayActivity::class.java.getDeclaredMethod("pioneerVehicleSpeedControl", LinearLayout::class.java)
                    .apply { isAccessible = true }.invoke(activity, row)
                val toggle = descendants(row).filterIsInstance<Switch>().single()
                assertEquals(activity.getString(R.string.settings_pioneer_vehicle_speed), toggle.contentDescription)
                toggle.performClick()
                assertEquals(toggle.isChecked, AirPlayPersistence.loadPioneerVehicleSpeedEnabled(activity))
                activity.setContentView(row)
                val width = (widthDp * activity.resources.displayMetrics.density).toInt()
                row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                row.layout(0, 0, width, row.measuredHeight)
                val bitmap = Bitmap.createBitmap(width, row.measuredHeight, Bitmap.Config.ARGB_8888)
                try {
                    row.draw(Canvas(bitmap))
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    assertTrue("Screenshot must contain visible content: $name", pixels.any { (it ushr 24) != 0 })
                    val file = File("build/reports/pioneer-speed-ui/$name.png").apply { requireNotNull(parentFile).mkdirs() }
                    file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { bitmap.recycle() }
            }
        } finally { activity.finish() }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
