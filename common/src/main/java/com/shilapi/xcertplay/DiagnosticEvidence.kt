package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import com.shilapi.xcertplay.feedback.FeedbackLogcat
import com.shilapi.xcertplay.pioneer.PioneerDiagnosticReader
import com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless.PioneerVehicleWireless
import java.io.File

/**
 * The same bounded, redacted evidence set for QR exports and one-click feedback.
 * Text and JSON only: no APK, JAR, ODEX/VDEX or native binaries are ever copied.
 */
internal object DiagnosticEvidence {
    private const val SYSTEM_ENTRY_LIMIT = 512 * 1024
    private const val LOG_LIMIT_EACH = 512 * 1024
    private const val LOG_LIMIT_TOTAL = 1_500_000
    private const val WHEEL_LIMIT = 128 * 1024
    private const val CRASH_LIMIT = 256 * 1024

    fun appendAll(
        app: Context, archive: DiagnosticArchive,
        diagnostics: (Context) -> List<PioneerDiagnosticReader.Item> = PioneerDiagnosticReader::collect,
    ) {
        appendAppLogs(app, archive)
        HudDiagnosticCollection.appendState(app, archive)
        archive.text("system/build.txt") { build() }
        runCatching { PioneerVehicleWireless.diagnosticSnapshot(DiPlayPreferences.phoneAddress(app)) }.fold({ state ->
            val status = if (state.contains("status=complete")) "success" else "incomplete"
            archive.text("bluetooth/state.txt", status) { state }
        }, { archive.unavailable("bluetooth/state.txt", "unavailable", it.javaClass.simpleName) })

        var failure: Throwable? = null
        val items = runCatching { diagnostics(app) }.getOrElse {
            failure = it
            listOf(PioneerDiagnosticReader.Item("root", "unavailable", it.javaClass.simpleName))
        }
        // Firmware hashes are small and important; write them before the bulk system text.
        val firmware = items.firstOrNull { it.name == "hud_firmware" }
        if (firmware != null) HudDiagnosticCollection.appendFirmware(archive, firmware)
        else archive.unavailable("hud/firmware.json", "unavailable",
            failure?.javaClass?.simpleName ?: "firmware_check_not_available")
        var logcat = false
        for (item in items) {
            if (item.name == "hud_firmware") continue
            archive.text("system/${item.name}.txt", item.status, SYSTEM_ENTRY_LIMIT) { item.text }
            if (item.name == "logcat" && item.status in setOf("success", "truncated")) logcat = true
        }
        // A non-rooted or unadapted head unit still gets its own app log buffer.
        if (!logcat) {
            val capture = FeedbackLogcat.capture()
            capture.text?.let { archive.text("system/logcat.txt", capture.status, SYSTEM_ENTRY_LIMIT) { it } }
                ?: archive.unavailable("system/logcat.txt", capture.status, capture.reason ?: "unavailable")
        }
    }

    private fun appendAppLogs(app: Context, archive: DiagnosticArchive) {
        File(app.filesDir, "logs/last-crash.txt").takeIf { it.isFile }?.let { crash ->
            archive.text("crash/last-crash.txt", limit = CRASH_LIMIT) { readLimited(crash, CRASH_LIMIT) }
        }
        var total = 0
        for (name in SessionLogFile.REPORT_NAMES.takeLast(3).reversed()) {
            val file = File(app.filesDir, "logs/$name")
            if (!file.isFile) continue
            val remaining = LOG_LIMIT_TOTAL - total
            if (remaining <= 0) break
            val text = readLimited(file, minOf(LOG_LIMIT_EACH, remaining))
            if (text.isBlank()) continue
            archive.text("logs/$name", limit = LOG_LIMIT_EACH) { text }
            total += text.toByteArray(Charsets.UTF_8).size
        }
        val wheel = WheelKeyLog.report(WheelKeyService.connected()).joinToString("\n").take(WHEEL_LIMIT)
        if (wheel.isNotBlank()) archive.text("logs/wheel-keys.txt", limit = WHEEL_LIMIT) { wheel }
    }

    private fun build(): String =
        "manufacturer=${Build.MANUFACTURER}\nmodel=${Build.MODEL}\n" +
            "build=${Build.DISPLAY}\nfingerprint=${Build.FINGERPRINT}\nandroid=${Build.VERSION.RELEASE}\napi=${Build.VERSION.SDK_INT}\nabis=${Build.SUPPORTED_ABIS.joinToString()}\n"

    private fun readLimited(file: File, limit: Int): String {
        val bytes = file.inputStream().use { input ->
            val buffer = ByteArray(minOf(limit, 64 * 1024).coerceAtLeast(1))
            val output = java.io.ByteArrayOutputStream()
            var remaining = limit
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (count < 0) break
                output.write(buffer, 0, count)
                remaining -= count
            }
            output.toByteArray()
        }
        return bytes.toString(Charsets.UTF_8)
    }
}
