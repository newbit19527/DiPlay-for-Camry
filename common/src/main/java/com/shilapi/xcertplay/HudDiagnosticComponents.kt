package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.pioneer.PioneerDiagnosticReader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** QR exports collect code, never app data. Inventory/maps use the existing root diagnostic lane. */
internal object HudDiagnosticComponents {
    fun append(
        context: Context, archive: DiagnosticArchive,
        collect: (Context) -> JSONObject = PioneerDiagnosticReader::collectHudComponents,
    ) {
        val inventory = runCatching { JSONObject(collect(context).toString()) }.getOrElse {
            archive.unavailable("hud/components.json", "unavailable", it.javaClass.simpleName)
            return
        }
        val processes = inventory.optJSONArray("processes") ?: JSONArray()
        for (i in 0 until minOf(processes.length(), 16)) {
            val process = processes.optJSONObject(i) ?: continue
            val maps = process.remove("maps") as? String ?: continue
            val path = "hud/process-maps/$i.txt"
            archive.text(path, process.optString("status", "success")) { maps }
            process.put("mapsArchivePath", path)
        }
        archive.json("hud/components.json", inventory.optString("status", "success")) { inventory }
        val updatedCode = updatedThirdPartyCode(context)
        val files = inventory.optJSONArray("files") ?: return
        for (i in 0 until minOf(files.length(), 256)) {
            val item = files.optJSONObject(i) ?: continue
            val path = item.optString("path")
            if (!allowedPath(path, updatedCode)) {
                archive.unavailable("hud/rejected-component-$i", "skipped", "outside_hud_code_scope")
                continue
            }
            archive.component(File(path), updatedCode)
        }
        if (files.length() > 256) archive.unavailable("hud/component-tail", "skipped", "component_count_limit")
    }

    internal fun allowedPath(path: String, updatedCode: Set<String>): Boolean {
        if (!path.startsWith("/") || path.split('/').any { it == ".." || it == "." } || '\u0000' in path) return false
        if (path.contains("bluetooth", true) || path.contains("blueforce", true)) return false
        if (path in updatedCode) return true
        // APK/optimized code is restricted to TPS or framework locations, never another system app.
        if (path.endsWith(".apk") || path.endsWith(".odex") || path.endsWith(".vdex") || path.endsWith(".art") || path.endsWith(".oat")) {
            return listOf("/system/framework/", "/vendor/framework/", "/system_ext/framework/", "/product/framework/")
                .any(path::startsWith)
        }
        return path in setOf("/system/bin/MeterServer", "/system/bin/SettingServer") ||
            listOf("/system/", "/vendor/", "/system_ext/", "/product/").any(path::startsWith) &&
            (path.endsWith(".so") || path.endsWith(".jar") && path.contains("/framework/"))
    }

    @Suppress("DEPRECATION")
    private fun updatedThirdPartyCode(context: Context): Set<String> = runCatching {
        val app = context.packageManager.getApplicationInfo("jp.pioneer.ceam.thirdpartyservice", 0)
        buildSet {
            app.nativeLibraryDir?.let { directory ->
                File(directory).listFiles().orEmpty().take(256).filter { it.isFile && it.extension == "so" }
                    .forEach { add(it.canonicalPath) }
            }
            (listOfNotNull(app.sourceDir) + app.splitSourceDirs.orEmpty()).forEach { path ->
                val code = File(path).canonicalFile
                add(code.path)
                for (arch in listOf("arm", "arm64")) for (ext in listOf("odex", "vdex", "oat", "art"))
                    add(File(code.parentFile, "oat/$arch/${code.nameWithoutExtension}.$ext").canonicalPath)
            }
        }
    }.getOrDefault(emptySet())
}
