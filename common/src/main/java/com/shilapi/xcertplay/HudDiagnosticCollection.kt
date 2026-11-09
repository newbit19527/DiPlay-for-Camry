package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.pioneer.PioneerDiagnosticReader
import org.json.JSONObject

/** Same bounded, structured HUD evidence for local exports and anonymous feedback. */
internal object HudDiagnosticCollection {
    fun appendState(app: Context, archive: DiagnosticArchive) {
        archive.json("hud/state.json") {
            val state = com.shilapi.xcertplay.pioneer.PioneerDebugRuntime.hudSnapshot()
            state.put("snapshotAvailable", state.length() > 0)
                .put("adaptationMode", com.shilapi.xcertplay.pioneer.CamryAdaptation.mode(app).name)
                .put("adaptationEnabled", com.shilapi.xcertplay.pioneer.CamryAdaptation.enabled(app))
                .put("snapshotAgeMs", state.optLong("updatedAtMs").takeIf { it > 0 }?.let { (System.currentTimeMillis() - it).coerceAtLeast(0) } ?: JSONObject.NULL)
                .put("exportedAtMs", System.currentTimeMillis())
        }
        archive.json("hud/settings.json") {
            val config = com.shilapi.xcertplay.hud.CamryHudSettings.load(app)
            JSONObject().put("enabled", config.enabled).put("strategy", config.strategy.name)
                .put("fixedMeters", config.fixedMeters).put("turnMeters", config.turnMeters)
                .put("complexMeters", config.complexMeters).put("straightMeters", config.straightMeters)
                .put("advance", config.advance.name)
        }
        archive.jsonLines("hud/recent.ndjson") { com.shilapi.xcertplay.pioneer.PioneerHudDiagnostics.recent() }
    }

    fun appendFirmware(archive: DiagnosticArchive, item: PioneerDiagnosticReader.Item) {
        archive.json("hud/firmware.json", item.status) {
            runCatching { JSONObject(item.text) }.getOrElse { error ->
                JSONObject().put("status", item.status).put("error", error.toString()).put("detail", item.text)
            }
        }
    }
}
