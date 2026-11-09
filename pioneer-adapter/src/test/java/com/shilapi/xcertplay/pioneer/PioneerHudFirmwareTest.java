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
    private String expected(String path) {
        for (String[] module : PioneerHudFirmware.MODULES) if (module[0].equals(path)) return module[1];
        throw new AssertionError(path);
    }
    @Test public void recordsAllExpectedAndActualHashesBeforeEnforcingGate() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        JSONObject evidence = PioneerHudFirmware.inspect("startup_enforced", path -> { reads.incrementAndGet(); return expected(path); });
        assertEquals(3, reads.get()); assertTrue(evidence.getBoolean("passed"));
        assertEquals(3, evidence.getJSONArray("modules").length());
        JSONObject first = evidence.getJSONArray("modules").getJSONObject(0);
        assertEquals(first.getString("expectedSha256"), first.getString("actualSha256"));
        PioneerHudFirmware.requirePassed(evidence);
    }
    @Test public void mismatchKeepsGateClosedAndRecordsBothHashes() throws Exception {
        JSONObject evidence = PioneerHudFirmware.inspect("startup_enforced", path -> "0".repeat(64));
        IOException error = assertThrows(IOException.class, () -> PioneerHudFirmware.requirePassed(evidence));
        assertTrue(error.getMessage().contains("HUD_FILTER_FIRMWARE_MISMATCH"));
        assertTrue(error.getMessage().contains("expected=")); assertTrue(error.getMessage().contains("actual="));
        assertFalse(evidence.getBoolean("passed"));
    }
    @Test public void unreadableModuleDoesNotHideOtherResultsOrMasqueradeAsMismatch() throws Exception {
        JSONObject evidence = PioneerHudFirmware.inspect("startup_enforced", path -> {
            if (path.endsWith("libmeterservice.so")) throw new IOException("Permission denied");
            return expected(path);
        });
        assertEquals("read_failed", evidence.getJSONArray("modules").getJSONObject(0).getString("status"));
        assertTrue(evidence.getJSONArray("modules").getJSONObject(0).isNull("actualSha256"));
        assertEquals("pass", evidence.getJSONArray("modules").getJSONObject(1).getString("status"));
        assertTrue(assertThrows(IOException.class, () -> PioneerHudFirmware.requirePassed(evidence))
            .getMessage().contains("HUD_FILTER_FIRMWARE_READ_FAILED"));
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
