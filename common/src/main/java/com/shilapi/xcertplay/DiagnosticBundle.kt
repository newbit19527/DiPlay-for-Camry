package com.shilapi.xcertplay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Generates a private immutable archive. The HTTP service streams this file, not a giant byte array. */
internal object DiagnosticBundle {
    @Synchronized fun collect(context: Context, report: () -> String): File {
        val app = context.applicationContext
        val directory = File(app.cacheDir, "diagnostic-bundles").apply { check(isDirectory || mkdirs()) }
        directory.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
            ?.forEachIndexed { index, old -> if (index >= 2 || System.currentTimeMillis() - old.lastModified() > 86_400_000) old.delete() }
        val file = File.createTempFile("diplay-diagnostics-", ".zip", directory)
        try {
            DiagnosticArchive(file, totalLimit = 256L * 1024 * 1024).use { archive ->
                archive.text("report.txt") {
                    val flushed = AsyncDiagnosticLog.awaitIdle(2_000)
                    "Log flush complete=$flushed\n" + report()
                }
                DiagnosticEvidence.appendAll(app, archive)
                archive.text("README.txt") {
                    "DiPlay diagnostic bundle\nSee manifest.json for collection status, source paths, byte counts and SHA-256.\n" +
                        "Missing/denied/timeout entries are not successful collections. System logcat is a filtered current buffer, not a complete boot history.\n" +
                        "Bluetooth/Blueforce system components are intentionally excluded; bluetooth/state.txt is a read-only state snapshot.\n" +
                        "No APK, JAR, ODEX/VDEX or native binaries are collected; only bounded text and JSON evidence is exported.\n" +
                        "hud/state.json, settings.json and recent.ndjson describe the current process; firmware.json records the current hashes of three navigation libraries.\n" +
                        "No settings database, pairing keys, hotspot passwords or protocol payloads are intentionally exported.\n"
                }
            }
            // Validate structure and decompress every entry before offering a successful download.
            ZipFile(file).use { zip ->
                check(zip.getEntry("manifest.json") != null && zip.getEntry("README.txt") != null)
                zip.entries().asSequence().forEach { entry -> zip.getInputStream(entry).use { it.copyTo(NullOutput) } }
            }
            return file
        } catch (error: Throwable) { file.delete(); throw error }
    }

    private object NullOutput : java.io.OutputStream() {
        override fun write(value: Int) = Unit
        override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
    }
}

/** Bounded, streaming archive writer with a truthful per-item manifest. */
internal class DiagnosticArchive(
    file: File,
    private val textLimit: Int = TEXT_LIMIT,
    private val totalLimit: Long = TOTAL_LIMIT,
) : java.io.Closeable {
    private val zip = ZipOutputStream(file.outputStream().buffered())
    private val manifest = JSONArray()
    private var written = 0L
    fun unavailable(path: String, status: String, reason: String) {
        manifest.put(JSONObject().put("path", path).put("status", status).put("reason", reason))
    }
    fun text(path: String, status: String = "success", limit: Int = textLimit, content: () -> String) {
        val value = runCatching(content).getOrElse { unavailable(path, "unavailable", it.javaClass.simpleName); return }
        val safe = value.lineSequence().mapNotNull(DiagnosticRedactor::redact).joinToString("\n")
        val bytes = safe.toByteArray(Charsets.UTF_8)
        val bounded = bytes.copyOf(minOf(bytes.size, limit))
        if (written + bounded.size > totalLimit) { unavailable(path, "skipped", "archive_size_limit"); return }
        zip.putNextEntry(ZipEntry(path)); zip.write(bounded); zip.closeEntry()
        written += bounded.size
        manifest.put(JSONObject().put("path", path).put("status", if (bytes.size > limit) "truncated" else status)
            .put("bytes", bounded.size).put("sha256", digest(bounded)))
    }
    fun json(path: String, status: String = "success", content: () -> JSONObject) {
        structured(path, status) { HudDiagnosticJson.sanitize(content()).toString() }
    }
    fun jsonLines(path: String, status: String = "success", content: () -> String) {
        structured(path, status) {
            content().lineSequence().filter { it.isNotBlank() }
                .joinToString("\n") { HudDiagnosticJson.sanitize(JSONObject(it)).toString() }
        }
    }
    private fun structured(path: String, status: String, content: () -> String) {
        val bytes = runCatching { content().toByteArray(Charsets.UTF_8) }.getOrElse {
            unavailable(path, "unavailable", it.javaClass.simpleName); return
        }
        // Never cut machine-readable evidence in the middle of JSON or a UTF-8 character.
        if (bytes.size > textLimit) { unavailable(path, "skipped", "structured_entry_size_limit"); return }
        raw(path, bytes, status)
    }
    /** Writes content without line redaction; used for machine-readable metadata and the README. */
    fun raw(path: String, content: ByteArray, status: String = "success") {
        val bounded = content.copyOf(minOf(content.size, textLimit))
        if (written + bounded.size > totalLimit) { unavailable(path, "skipped", "archive_size_limit"); return }
        zip.putNextEntry(ZipEntry(path)); zip.write(bounded); zip.closeEntry()
        written += bounded.size
        manifest.put(JSONObject().put("path", path).put("status", if (content.size > textLimit) "truncated" else status)
            .put("bytes", bounded.size).put("sha256", digest(bounded)))
    }
    override fun close() {
        zip.putNextEntry(ZipEntry("manifest.json"))
        zip.write(JSONObject().put("schema", 1).put("createdAt", System.currentTimeMillis()).put("items", manifest)
            .toString(2).toByteArray(Charsets.UTF_8))
        zip.closeEntry(); zip.close()
    }
    private fun digest(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    companion object {
        private const val TEXT_LIMIT = 4 * 1024 * 1024
        private const val TOTAL_LIMIT = 96L * 1024 * 1024
    }
}
