package com.shilapi.xcertplay

import com.shilapi.xcertplay.pioneer.PioneerDiagnosticReader
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DiagnosticEvidenceTest {
    @Test fun sharedEvidenceIsTextOnlyAndCoversBothExports() {
        val file = Files.createTempFile("shared-evidence", ".zip").toFile()
        try {
            DiagnosticArchive(file).use { archive ->
                DiagnosticEvidence.appendAll(RuntimeEnvironment.getApplication(), archive) {
                    listOf(
                        PioneerDiagnosticReader.Item("identity", "success", "uid=0(root)"),
                        PioneerDiagnosticReader.Item("logcat", "success", "01-01 00:00:00.000 I/DiPlay( 1): ok"),
                        PioneerDiagnosticReader.Item("kernel_usb", "truncated", "usb 1-1: new device"),
                        PioneerDiagnosticReader.Item("hud_firmware", "success",
                            JSONObject().put("status", "read").put("actualSha256", "a".repeat(64)).toString()),
                    )
                }
            }
            ZipFile(file).use { zip ->
                for (path in listOf("system/build.txt", "system/identity.txt", "system/logcat.txt",
                        "system/kernel_usb.txt", "hud/state.json", "hud/settings.json", "hud/recent.ndjson", "hud/firmware.json")) {
                    assertNotNull(path, zip.getEntry(path))
                }
                assertFalse(zip.entries().asSequence().any { it.name.startsWith("components/") || it.name == "hud/components.json" })
                val firmware = JSONObject(zip.getInputStream(zip.getEntry("hud/firmware.json")).reader().readText())
                assertEquals("a".repeat(64), firmware.getString("actualSha256"))
            }
        } finally { file.delete() }
    }
}
