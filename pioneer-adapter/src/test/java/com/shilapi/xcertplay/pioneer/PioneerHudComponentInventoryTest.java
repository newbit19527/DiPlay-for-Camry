package com.shilapi.xcertplay.pioneer;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 25, manifest = Config.NONE)
public class PioneerHudComponentInventoryTest {
    @Test public void rejectsOtherAppsAndCompiledCodeEvenWhenMapped() {
        Set<String> trusted = new HashSet<>();
        for (String path : new String[]{"/system/app/Navigation/Navigation.apk",
                "/system/priv-app/Settings/Settings.apk", "/system/app/Navigation/oat/arm/Navigation.odex",
                "/system/priv-app/Settings/oat/arm/Settings.vdex", "/data/dalvik-cache/arm/system@app@Navigation.apk@classes.dex",
                "/system/framework/arm/system@app@Navigation@classes.odex", "/system/framework/bluetooth.jar",
                "/vendor/lib/libblueforce.so", "/system/lib/../../data/private", "/system_fake/lib/liba.so",
                "/data/app/other/base.apk", "/proc/1/mem"}) {
            assertFalse(path, PioneerHudComponentInventory.allowedCode(path, trusted));
        }
        String tps = "/data/app/jp.pioneer.ceam.thirdpartyservice-1/base.apk";
        assertFalse(PioneerHudComponentInventory.allowedCode(tps, trusted));
        trusted.add(tps);
        assertTrue(PioneerHudComponentInventory.allowedCode(tps, trusted));
        assertFalse(PioneerHudComponentInventory.allowedCode(tps.replace("base.apk", "other.apk"), trusted));
        assertTrue(PioneerHudComponentInventory.allowedCode("/system/framework/arm/boot.vdex", trusted));
        assertTrue(PioneerHudComponentInventory.allowedCode("/system_ext/lib/libhud.so", trusted));
    }
    @Test public void canonicalScopeRejectsSymlinksOutsideImmutablePartitions() throws Exception {
        File target = File.createTempFile("hud-private", ".so");
        File link = new File(target.getParent(), target.getName() + "-link");
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath());
            assertNull(new PioneerHudComponentInventory(() -> true).safe(link));
        } finally { link.delete(); target.delete(); }
    }
    @Test public void selectsOnlyExactTargetProcessNamesAndParsesMappedPaths() {
        for (String name : new String[]{"/system/bin/MeterServer", "/system/bin/SettingServer",
                PioneerHudComponentInventory.TPS, PioneerHudComponentInventory.TPS + ":palcoreservice"})
            assertTrue(PioneerHudComponentInventory.targetProcess(name));
        for (String name : new String[]{"MeterServer", "com.android.settings", "com.navigation",
                PioneerHudComponentInventory.TPS + ":bluetooth", PioneerHudComponentInventory.TPS + "evil"})
            assertFalse(PioneerHudComponentInventory.targetProcess(name));
        assertEquals("/system/lib/libmeterservice.so", PioneerHudComponentInventory.mappedPath(
            "1000-2000 r-xp 00000000 08:01 123 /system/lib/libmeterservice.so"));
        assertNull(PioneerHudComponentInventory.mappedPath("1000-2000 rw-p 0 00:00 0 [heap]"));
        assertNull(PioneerHudComponentInventory.mappedPath("1000-2000 r-xp 0 08:01 1 /system/lib/a.so (deleted)"));
    }
    @Test public void mapsTextIsByteBoundedAndJsonFitsIpcEvenWithWorstCaseEscapes() throws Exception {
        String raw = "\u0001".repeat(256 * 1024);
        String maps = PioneerHudComponentInventory.boundedUtf8(raw, PioneerHudComponentInventory.MAP_BYTES);
        assertEquals(32768, maps.getBytes(StandardCharsets.UTF_8).length);
        String unicode = PioneerHudComponentInventory.boundedUtf8("界".repeat(20000), 32768);
        assertFalse(unicode.contains("\ufffd"));
        assertTrue(unicode.getBytes(StandardCharsets.UTF_8).length <= 32768);
        org.json.JSONArray processes = new org.json.JSONArray();
        for (int i = 0; i < PioneerHudComponentInventory.MAX_PROCESSES; i++)
            processes.put(new JSONObject().put("maps", maps));
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        PioneerRootIpc.write(frame, new JSONObject().put("processes", processes), PioneerRootIpc.MAX_RESPONSE_BYTES);
        assertTrue(frame.size() < 3 * 1024 * 1024);
    }
    @Test public void cancellationProducesExplicitEmptyMetadataWithoutReadingSources() throws Exception {
        JSONObject result = PioneerHudComponentInventory.collect(new JSONObject(), () -> false);
        assertEquals("cancelled", result.getString("status"));
        assertTrue(result.getBoolean("cancelled"));
        assertTrue(result.getBoolean("truncated"));
        assertEquals(0, result.getInt("fileCount"));
        assertEquals(0, result.getInt("processCount"));
        assertTrue(result.has("build"));
    }
    @Test public void readsDtNeededWithoutSectionsAndRejectsMalformedElf() throws Exception {
        ByteBuffer elf = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
        elf.put(new byte[]{0x7f, 'E', 'L', 'F', 1, 1});
        elf.putInt(28, 52); elf.putShort(42, (short)32); elf.putShort(44, (short)2);
        elf.putInt(52, 1); elf.putInt(56, 0); elf.putInt(60, 4096); elf.putInt(68, 256);
        elf.putInt(84, 2); elf.putInt(88, 128); elf.putInt(92, 4224); elf.putInt(100, 32);
        elf.putInt(128, 1); elf.putInt(132, 0);
        elf.putInt(136, 5); elf.putInt(140, 4320);
        elf.putInt(144, 10); elf.putInt(148, 16);
        elf.position(224); elf.put("libhud.so\0".getBytes(StandardCharsets.US_ASCII));
        File file = File.createTempFile("hud-elf", ".so");
        try {
            try (FileOutputStream out = new FileOutputStream(file)) { out.write(elf.array()); }
            assertEquals(Collections.singletonList("libhud.so"), PioneerHudComponentInventory.needed(file, () -> true));
            assertThrows(IOException.class, () -> PioneerHudComponentInventory.needed(file, () -> false));
            try (FileOutputStream out = new FileOutputStream(file)) { out.write(new byte[64]); }
            assertTrue(PioneerHudComponentInventory.needed(file, () -> true).isEmpty());
        } finally { file.delete(); }
    }
}
