package com.shilapi.xcertplay.feedback

import android.content.Context
import com.shilapi.xcertplay.hud.CamryHudSettings
import com.shilapi.xcertplay.pioneer.PioneerDebugRuntime
import com.shilapi.xcertplay.pioneer.PioneerDiagnosticReader
import com.shilapi.xcertplay.pioneer.PioneerHudDiagnostics
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FeedbackBundleTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "logs").deleteRecursively()
        File(context.filesDir, "logs").mkdirs()
        context.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit()
            .putString("phone_name", "Alice's iPhone")
            .putString("phone_address", "12:34:56:78:90:AB")
            .commit()
    }

    @After
    fun tearDown() {
        File(context.filesDir, "logs").deleteRecursively()
    }

    @Test
    fun bundleContainsRequiredEntriesAndNoPersistentIdentity() {
        File(context.filesDir, "logs/diplay.log").writeText(
            "peer=12:34:56:78:90:AB\npassword=secret\nnormal line\n",
        )
        File(context.filesDir, "logs/last-crash.txt").writeText("crash atEpochMs=1\nnormal crash line\n")
        val output = File(context.cacheDir, "feedback-test.zip")

        FeedbackBundle.build(context, meta(), output)

        ZipFile(output).use { zip ->
            listOf("feedback.json", "report.txt", "manifest.json", "README.txt", "logs/diplay.log")
                .forEach { path -> assertTrue(path, zip.getEntry(path) != null) }
            val feedback = zip.getInputStream(zip.getEntry("feedback.json")).reader().readText()
            val report = zip.getInputStream(zip.getEntry("report.txt")).reader().readText()
            val log = zip.getInputStream(zip.getEntry("logs/diplay.log")).reader().readText()
            assertFalse(feedback.contains("StableClientId"))
            assertFalse(feedback.contains("Alice's iPhone"))
            assertFalse(report.contains("Alice's iPhone"))
            assertFalse(report.contains("12:34:56:78:90:AB"))
            assertFalse(log.contains("12:34:56"))
            assertFalse(log.contains("secret"))
            assertTrue(log.contains("normal line"))
            val manifest = JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).reader().readText())
            assertEquals(1, manifest.getInt("schema"))
            assertTrue(manifest.getJSONArray("items").length() >= 5)
        }
        assertTrue(output.length() <= FeedbackBundle.MAX_BYTES)
        output.delete()
    }

    @Test
    fun descriptionIsScrubbedOfConfiguredPhoneIdentity() {
        val output = File(context.cacheDir, "feedback-description.zip")
        FeedbackBundle.build(context, meta(description = "Alice's iPhone cannot connect"), output)
        ZipFile(output).use { zip ->
            val feedback = zip.getInputStream(zip.getEntry("feedback.json")).reader().readText()
            assertFalse(feedback.contains("Alice's iPhone"))
            assertTrue(feedback.contains("[device] cannot connect"))
        }
        output.delete()
    }

    @Test
    fun hudEvidencePreservesHashesAndRedactsPrivateFields() {
        val snapshot = PioneerDebugRuntime.navigationHudSnapshot
        val config = CamryHudSettings.load(context)
        val output = File(context.cacheDir, "feedback-hud.zip")
        val hash = "a".repeat(64)
        try {
            CamryHudSettings.save(context, config.copy(enabled = false, fixedMeters = 600))
            val state = JSONObject().put("updatedAtMs", System.currentTimeMillis())
                .put("sdkSuccesses", 12).put("sdkFailures", 1).put("road", "private-road")
                .put("originalMap", JSONObject().put("filter", JSONObject().put("ready", false)
                    .put("error", "HUD_FILTER_ACK_TIMEOUT").put("token", "private-token")))
            PioneerDebugRuntime.navigationHudSnapshot = { state }
            PioneerHudDiagnostics.checkpoint(state)
            FeedbackBundle.build(context, meta(), output) {
                PioneerDiagnosticReader.Item("hud_firmware", "success",
                    JSONObject().put("status", "read").put("actualSha256", hash).toString())
            }
            ZipFile(output).use { zip ->
                fun read(name: String) = zip.getInputStream(zip.getEntry(name)).reader().readText()
                val exported = JSONObject(read("hud/state.json"))
                assertTrue(exported.getBoolean("snapshotAvailable"))
                assertEquals(12, exported.getInt("sdkSuccesses"))
                assertFalse(exported.has("road"))
                assertFalse(exported.toString().contains("private-token"))
                val settings = JSONObject(read("hud/settings.json"))
                assertFalse(settings.getBoolean("enabled"))
                assertEquals(600, settings.getInt("fixedMeters"))
                val firmware = JSONObject(read("hud/firmware.json"))
                assertEquals(hash, firmware.getString("actualSha256"))
                assertEquals("read", firmware.getString("status"))
                assertFalse(firmware.has("passed")); assertFalse(firmware.has("expectedSha256"))
                val recent = read("hud/recent.ndjson").lineSequence().filter { it.isNotBlank() }
                    .map { JSONObject(it) }.toList()
                assertTrue(recent.size >= 2)
                assertFalse(read("hud/recent.ndjson").contains("private-road"))
                assertFalse(read("hud/recent.ndjson").contains("private-token"))
                assertFalse(zip.entries().asSequence().any { it.name.startsWith("system/") || it.name.startsWith("components/") })
                val items = JSONObject(read("manifest.json")).getJSONArray("items")
                assertEquals(4, (0 until items.length()).count { items.getJSONObject(it).getString("path").startsWith("hud/") })
            }
            assertTrue(output.length() <= FeedbackBundle.MAX_BYTES)
        } finally {
            PioneerDebugRuntime.navigationHudSnapshot = snapshot
            CamryHudSettings.save(context, config)
            output.delete()
        }
    }

    @Test
    fun unavailableFirmwareAndEmptyStateStillProduceValidFeedback() {
        val snapshot = PioneerDebugRuntime.navigationHudSnapshot
        val output = File(context.cacheDir, "feedback-no-hud.zip")
        try {
            PioneerDebugRuntime.navigationHudSnapshot = { JSONObject() }
            FeedbackBundle.build(context, meta(), output) {
                PioneerDiagnosticReader.Item("hud_firmware", "unavailable", "root service unavailable")
            }
            ZipFile(output).use { zip ->
                val state = JSONObject(zip.getInputStream(zip.getEntry("hud/state.json")).reader().readText())
                assertFalse(state.getBoolean("snapshotAvailable"))
                val firmware = JSONObject(zip.getInputStream(zip.getEntry("hud/firmware.json")).reader().readText())
                assertEquals("unavailable", firmware.getString("status"))
                assertTrue(firmware.getString("detail").contains("root service unavailable"))
            }
        } finally { PioneerDebugRuntime.navigationHudSnapshot = snapshot; output.delete() }
    }

    @Test
    fun firmwareFailureDoesNotDiscardLocalHudEvidence() {
        val output = File(context.cacheDir, "feedback-hud-failure.zip")
        try {
            FeedbackBundle.build(context, meta(), output) { throw java.io.IOException("root unavailable") }
            ZipFile(output).use { zip ->
                assertTrue(zip.getEntry("hud/settings.json") != null)
                assertTrue(zip.getEntry("hud/recent.ndjson") != null)
                val items = JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).reader().readText()).getJSONArray("items")
                val firmware = (0 until items.length()).map { items.getJSONObject(it) }
                    .single { it.getString("path") == "hud/firmware.json" }
                assertEquals("unavailable", firmware.getString("status"))
                assertEquals("IOException", firmware.getString("reason"))
            }
        } finally { output.delete() }
    }

    private fun meta(description: String = "connection failed") = FeedbackMeta(
        id = "a".repeat(32),
        category = FeedbackCategory.CONNECTION,
        description = description,
        entry = FeedbackEntry.SETTINGS,
        createdAt = System.currentTimeMillis(),
        appVersionName = "0.4.0-test",
        appVersionCode = 4000,
        updateChannel = "beta",
        deviceManufacturer = "Test",
        deviceModel = "Head Unit",
        deviceBoard = "test-board",
        androidRelease = "11",
        androidApi = 30,
        sessionMode = "wireless",
        sessionState = "failed",
    )
}
