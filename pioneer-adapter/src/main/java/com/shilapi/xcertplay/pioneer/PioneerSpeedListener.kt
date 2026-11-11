package com.shilapi.xcertplay.pioneer

import android.os.Looper
import jp.pioneer.ceam.handler.GpsChipsListener

/** Loaded by name only after checking the CEAM sensor API. Keep callbacks on our worker. */
class PioneerSpeedListener(looper: Looper, private val changed: (String) -> Unit) : GpsChipsListener(looper) {
    override fun OnSpeedChangedForJson(speed: String) = changed(speed)
}
