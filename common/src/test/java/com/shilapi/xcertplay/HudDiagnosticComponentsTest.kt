package com.shilapi.xcertplay

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HudDiagnosticComponentsTest {
    @Test fun excludesOemAppsAndTheirOptimizedCodeEvenWhenSystemInstalled() {
        for (path in listOf("/system/app/AMap/AMap.apk", "/system/app/AMap/oat/arm/AMap.odex",
            "/system/priv-app/Setting/Setting.apk", "/system/priv-app/Setting/oat/arm/Setting.vdex",
            "/data/data/com.autonavi.amapauto/settings.db", "/system/lib/../../data/secret.so",
            "/system/lib/libbluetooth.so")) assertFalse(path, HudDiagnosticComponents.allowedPath(path, emptySet()))
        for (path in listOf("/system/lib/libmeterservice.so", "/system/bin/MeterServer",
            "/system/bin/SettingServer", "/system/framework/services.jar", "/system/framework/arm/boot.oat"))
            assertTrue(path, HudDiagnosticComponents.allowedPath(path, emptySet()))
        val apk = "/data/app/tps/base.apk"
        assertFalse(HudDiagnosticComponents.allowedPath(apk, emptySet()))
        assertTrue(HudDiagnosticComponents.allowedPath(apk, setOf(apk)))
    }

    @Test fun mapsAreSeparateCompleteTextAndMissingFilesAreExplicit() {
        val file = Files.createTempFile("hud-components", ".zip").toFile()
        val maps = ("00100000-00200000 r-xp 00000000 00:00 1 /system/lib/libmeterservice.so\n").repeat(100)
        try {
            DiagnosticArchive(file).use { archive ->
                HudDiagnosticComponents.append(RuntimeEnvironment.getApplication(), archive) {
                    JSONObject().put("status", "partial").put("files", JSONArray()
                        .put(JSONObject().put("path", "/system/lib/diplay-missing-meter.so"))
                        .put(JSONObject().put("path", "/system/app/AMap/AMap.apk")))
                        .put("processes", JSONArray().put(JSONObject().put("pid", 313)
                            .put("name", "/system/bin/MeterServer").put("status", "truncated").put("maps", maps)))
                }
                archive.component(File("/system/lib/diplay-missing-meter.so")) // Same path must not duplicate manifest entries.
            }
            ZipFile(file).use { zip ->
                val inventory = JSONObject(zip.getInputStream(zip.getEntry("hud/components.json")).reader().readText())
                val process = inventory.getJSONArray("processes").getJSONObject(0)
                assertFalse(process.has("maps"))
                assertEquals("hud/process-maps/0.txt", process.getString("mapsArchivePath"))
                val exportedMaps = zip.getInputStream(zip.getEntry("hud/process-maps/0.txt")).reader().readText()
                assertTrue(exportedMaps.length > 4096)
                assertEquals(100, exportedMaps.lineSequence().filter { it.isNotBlank() }.count())
                val items = JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).reader().readText()).getJSONArray("items")
                val entries = (0 until items.length()).map { items.getJSONObject(it) }
                assertEquals("truncated", entries.single { it.getString("path") == "hud/process-maps/0.txt" }.getString("status"))
                assertEquals("missing", entries.single { it.getString("path") == "components/system/lib/diplay-missing-meter.so" }.getString("status"))
                assertTrue(entries.any { it.optString("reason") == "outside_hud_code_scope" })
            }
        } finally { file.delete() }
    }

    @Test fun failedRootInventoryKeepsArchiveUsable() {
        val file = Files.createTempFile("hud-no-root", ".zip").toFile()
        try {
            DiagnosticArchive(file).use { archive ->
                HudDiagnosticComponents.append(RuntimeEnvironment.getApplication(), archive) { throw java.io.IOException() }
                archive.text("report.txt") { "existing diagnostics" }
            }
            ZipFile(file).use { zip ->
                assertNotNull(zip.getEntry("report.txt"))
                val manifest = zip.getInputStream(zip.getEntry("manifest.json")).reader().readText()
                assertTrue(manifest.replace("\\/", "/").contains("hud/components.json"))
                assertTrue(manifest.contains("unavailable"))
            }
        } finally { file.delete() }
    }
}
