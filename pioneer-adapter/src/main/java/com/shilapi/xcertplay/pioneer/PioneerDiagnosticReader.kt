package com.shilapi.xcertplay.pioneer

import android.content.Context
import org.json.JSONObject

/** Read-only diagnostics share the root service; no shell dispatch, temp scripts, or result polling. */
object PioneerDiagnosticReader {
    data class Item(val name: String, val status: String, val text: String)
    @Synchronized fun collect(context: Context): List<Item> {
        if (!CamryAdaptation.enabled()) return listOf(Item("root", "unavailable", "Camry adaptation disabled"))
        return PioneerRootDiagnostics.names().map { name ->
            collectOne(context, name)
        }
    }
    /** Feedback requests only firmware hashes, not system logs, package/process lists or binaries. */
    @Synchronized fun collectHudFirmware(context: Context): Item =
        if (!CamryAdaptation.enabled()) Item("hud_firmware", "unavailable", "Camry adaptation disabled")
        else collectOne(context, "hud_firmware")

    @Synchronized fun collectHudComponents(context: Context): JSONObject = runCatching {
        check(CamryAdaptation.enabled()) { "Camry adaptation disabled" }
        PioneerRootSession.request(context, "HUD_COMPONENTS", JSONObject(), 15000)
    }.getOrElse {
        JSONObject().put("status", "unavailable").put("error", it.message.orEmpty())
            .put("files", org.json.JSONArray()).put("processes", org.json.JSONArray())
            .put("fileCount", 0).put("processCount", 0).put("skippedCount", 0).put("truncated", false)
    }

    private fun collectOne(context: Context, name: String): Item = runCatching {
        val result = PioneerRootSession.request(context, "DIAGNOSTIC", JSONObject().put("name", name), 5000)
        Item(name, result.getString("status"), result.getString("text"))
    }.getOrElse { Item(name, "unavailable", it.message.orEmpty()) }
}
