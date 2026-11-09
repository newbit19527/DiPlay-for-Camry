package com.shilapi.xcertplay.pioneer;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 25, manifest = Config.NONE)
public class PioneerHudFirmwareTest {
    @Test public void recordsActualHashesForEveryModuleWithoutAVerdict() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        JSONObject evidence = PioneerHudFirmware.inspect("startup_hash_evidence",
            path -> { reads.incrementAndGet(); return "a".repeat(64); });
        assertEquals(3, reads.get());
        assertEquals(3, evidence.getJSONArray("modules").length());
        assertFalse(evidence.has("passed"));
        for (int i = 0; i < 3; i++) {
            JSONObject module = evidence.getJSONArray("modules").getJSONObject(i);
            assertEquals("read", module.getString("status"));
            assertEquals("a".repeat(64), module.getString("actualSha256"));
            assertFalse(module.has("expectedSha256"));
        }
    }
    @Test public void unreadableModuleIsRecordedWithoutBlockingOthers() throws Exception {
        JSONObject evidence = PioneerHudFirmware.inspect("startup_hash_evidence", path -> {
            if (path.endsWith("libmeterservice.so")) throw new IOException("Permission denied");
            return "b".repeat(64);
        });
        JSONObject first = evidence.getJSONArray("modules").getJSONObject(0);
        assertEquals("read_failed", first.getString("status"));
        assertTrue(first.isNull("actualSha256"));
        assertTrue(first.getString("error").contains("Permission denied"));
        assertEquals("read", evidence.getJSONArray("modules").getJSONObject(1).getString("status"));
    }
    @Test public void javaHashWorksWithoutAnExternalCommandAndHonorsCancellation() throws Exception {
        java.io.File file = java.io.File.createTempFile("hud-hash", ".bin");
        try {
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
                out.write("abc".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                PioneerHudFirmware.javaSha(file.getPath(), () -> true));
            assertThrows(IOException.class, () -> PioneerHudFirmware.javaSha(file.getPath(), () -> false));
            assertTrue(java.util.Arrays.asList(PioneerRootDiagnostics.names()).contains("hud_firmware"));
        } finally { file.delete(); }
    }
}
