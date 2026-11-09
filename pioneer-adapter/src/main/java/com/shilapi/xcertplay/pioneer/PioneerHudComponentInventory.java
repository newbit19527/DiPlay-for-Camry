package com.shilapi.xcertplay.pioneer;

import android.content.pm.ApplicationInfo;
import android.os.Build;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.zip.*;

/** Fixed, metadata-only inventory. No caller paths, staging, shell or additional transport. */
final class PioneerHudComponentInventory {
    static final String TPS = "jp.pioneer.ceam.thirdpartyservice";
    static final int MAX_FILES = 256, MAP_BYTES = 32768, MAX_PROCESSES = 8;
    private final Callable<Boolean> current;
    private final long until = System.nanoTime() + 12_000_000_000L;
    private final JSONArray files = new JSONArray(), processes = new JSONArray();
    private final Set<String> seen = new HashSet<>(), trusted = new HashSet<>();
    private int skipped, directories, candidates, processScans;
    private long scanBytes;
    private boolean truncated;
    private String stop = "success";
    PioneerHudComponentInventory(Callable<Boolean> current) { this.current = current; }
    private boolean active() throws Exception {
        if (!current.call() || Thread.currentThread().isInterrupted()) { stop = "cancelled"; return false; }
        if (System.nanoTime() >= until) { stop = "deadline"; return false; }
        return true;
    }
    static boolean targetProcess(String name) {
        return name.equals("/system/bin/MeterServer") || name.equals("/system/bin/SettingServer") ||
            name.equals(TPS) || name.equals(TPS + ":palcoreservice");
    }
    static boolean immutable(String path) {
        for (String root : new String[]{"/system/", "/vendor/", "/product/", "/system_ext/"})
            if (path.startsWith(root)) return true;
        return false;
    }
    static boolean allowedCode(String path, Set<String> trusted) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.contains("bluetooth") || lower.contains("blueforce")) return false;
        if (trusted.contains(path)) return true;
        if (!immutable(path) || path.contains("/../") || path.contains("/./")) return false;
        if (lower.endsWith(".apk") || lower.contains("/app/") || lower.contains("/priv-app/")) return false;
        if (lower.contains(".apk") || lower.contains("@app@") || lower.contains("@priv-app@")) return false;
        if (lower.matches(".*\\.(odex|vdex|oat|art)$") && !lower.contains("/framework/")) return false;
        return true;
    }
    String safe(File file) throws IOException {
        String path = file.getCanonicalPath();
        return path.length() <= 1024 && allowedCode(path, trusted) ? path : null;
    }
    private void add(File file, String role) throws Exception {
        if (!active()) return;
        String path = safe(file);
        if (path == null) { skipped++; return; }
        if (!seen.add(path)) return;
        if (files.length() >= MAX_FILES) { truncated = true; skipped++; return; }
        boolean exists = file.isFile();
        files.put(new JSONObject().put("path", path).put("role", role)
            .put("status", exists ? "available" : "missing").put("size", exists ? file.length() : 0));
    }
    static JSONObject collect(JSONObject owner, Callable<Boolean> current) throws Exception {
        return new PioneerHudComponentInventory(current).collect(owner);
    }
    private JSONObject collect(JSONObject owner) throws Exception {
        JSONObject pkg = new JSONObject().put("name", TPS);
        if (!active()) return result(pkg);
        for (String path : new String[]{"/system/bin/MeterServer", "/system/bin/SettingServer",
                "/system/lib/libmeterservice.so", "/system/lib/libmeterservice_jni.so", "/system/lib/libNPMeterHandler.so",
                "/system/framework/framework.jar", "/system/framework/services.jar"}) add(new File(path), "hud_core");
        corresponding(new File("/system/framework/framework.jar"));
        corresponding(new File("/system/framework/services.jar"));
        try {
            Object manager = Class.forName("android.app.AppGlobals").getMethod("getPackageManager").invoke(null);
            Class<?> type = Class.forName("android.content.pm.IPackageManager");
            int user = owner.getInt("uid") / 100000;
            ApplicationInfo info = (ApplicationInfo) type.getMethod("getApplicationInfo", String.class, int.class, int.class)
                .invoke(manager, TPS, 0, user);
            if (info == null || !TPS.equals(info.packageName)) throw new IOException("TPS_NOT_INSTALLED");
            android.content.pm.PackageInfo pi = (android.content.pm.PackageInfo) type
                .getMethod("getPackageInfo", String.class, int.class, int.class).invoke(manager, TPS, 0, user);
            pkg.put("versionName", pi.versionName).put("versionCode", pi.versionCode).put("status", "available");
            List<String> sources = new ArrayList<>();
            sources.add(info.sourceDir);
            if (info.splitSourceDirs != null) Collections.addAll(sources, info.splitSourceDirs);
            for (String source : sources.subList(0, Math.min(32, sources.size()))) {
                if (!active()) break;
                if (source == null) continue;
                String path = new File(source).getCanonicalPath();
                if (path.endsWith(".apk") && (immutable(path) || path.startsWith("/data/app/"))) {
                    trusted.add(path); add(new File(path), "thirdparty_apk");
                    String stem = new File(path).getName().replaceFirst("\\.apk$", "");
                    for (String arch : new String[]{"arm", "arm64", "x86", "x86_64"})
                        for (String ext : new String[]{".odex", ".vdex", ".oat", ".art"}) {
                            File compiled = new File(new File(path).getParentFile(), "oat/" + arch + "/" + stem + ext);
                            String compiledPath = compiled.getCanonicalPath();
                            // No symlink escape from the PM source's corresponding oat directory.
                            if ((immutable(compiledPath) || compiledPath.startsWith("/data/app/")) &&
                                    compiledPath.equals(compiled.getAbsolutePath()) && compiled.isFile()) {
                                trusted.add(compiledPath); add(compiled, "thirdparty_compiled");
                            }
                        }
                } else skipped++;
            }
            if (sources.size() > 32) truncated = true;
            if (info.nativeLibraryDir != null) {
                File nativeDir = new File(info.nativeLibraryDir);
                String canonical = nativeDir.getCanonicalPath();
                if (immutable(canonical) || canonical.startsWith("/data/app/")) {
                    File[] libs = nativeDir.listFiles();
                    if (libs != null) for (int i = 0; i < libs.length && i < 128 && active(); i++) {
                        String lib = libs[i].getCanonicalPath();
                        if (libs[i].isFile() && libs[i].getName().endsWith(".so") &&
                                (immutable(lib) || lib.startsWith(canonical + "/") && new File(lib).getParent().equals(canonical))) {
                            trusted.add(lib); add(new File(lib), "thirdparty_native");
                        } else skipped++;
                    }
                    if (libs != null && libs.length > 128) truncated = true;
                } else skipped++;
            }
        } catch (Exception error) { pkg.put("status", "unavailable").put("error", error.getClass().getSimpleName()); }

        collectProcesses();
        for (String root : new String[]{"/system/framework", "/system_ext/framework", "/system/system_ext/framework", "/vendor/framework"})
            walk(new File(root), 3, "framework", true);
        // Recursively follow DT_NEEDED only into immutable library directories.
        for (int i = 0; i < files.length() && active(); i++) {
            File code = new File(files.getJSONObject(i).getString("path"));
            if (code.getName().endsWith(".so") || code.getParent().equals("/system/bin")) dependencies(code);
        }
        return result(pkg);
    }
    private JSONObject result(JSONObject pkg) throws Exception {
        if (stop.equals("success") && truncated) stop = "truncated";
        return new JSONObject().put("files", files).put("processes", processes).put("package", pkg)
            .put("build", new JSONObject().put("fingerprint", Build.FINGERPRINT).put("display", Build.DISPLAY)
                .put("id", Build.ID).put("sdk", Build.VERSION.SDK_INT).put("release", Build.VERSION.RELEASE))
            .put("status", stop).put("truncated", truncated || !stop.equals("success"))
            .put("fileCount", files.length()).put("processCount", processes.length()).put("skippedCount", skipped)
            .put("directoryCount", directories).put("candidateCount", candidates)
            .put("mapsByteLimit", MAP_BYTES).put("mapsDiscoveryByteLimit", 256 * 1024)
            .put("fileLimit", MAX_FILES).put("processLimit", MAX_PROCESSES)
            .put("deadlineReached", stop.equals("deadline")).put("cancelled", stop.equals("cancelled"))
            .put("dependencyDiscovery", "bounded_recursive_dt_needed");
    }
    private void collectProcesses() throws Exception {
        File[] entries = new File("/proc").listFiles();
        if (entries == null) { stop = "processes_unavailable"; return; }
        {
            for (File entry : entries) {
                if (!active()) break;
                if (++processScans > 4096) { truncated = true; break; }
                String pid = entry.getName();
                if (!pid.matches("[0-9]{1,9}")) continue;
                try {
                    String name = read(new File(entry, "cmdline"), 256).split("\u0000", 2)[0];
                    if (!targetProcess(name)) continue;
                    if (processes.length() >= MAX_PROCESSES) { truncated = true; skipped++; continue; }
                    JSONObject proc = new JSONObject().put("name", name).put("pid", Integer.parseInt(pid));
                    try {
                        String discoveryMaps = read(new File(entry, "maps"), 256 * 1024 + 1);
                        boolean discoveryCut = discoveryMaps.getBytes(StandardCharsets.UTF_8).length > 256 * 1024;
                        String maps = boundedUtf8(discoveryMaps, MAP_BYTES);
                        boolean cut = !maps.equals(discoveryMaps);
                        if (cut || discoveryCut) truncated = true;
                        // Verify identity again so a reused PID never attributes another process's maps.
                        if (!name.equals(read(new File(entry, "cmdline"), 256).split("\u0000", 2)[0])) throw new IOException("PID_CHANGED");
                        proc.put("maps", maps).put("status", cut ? "truncated" : "success").put("truncated", cut).put("mapsTruncated", cut)
                            .put("mapsDiscoveryTruncated", discoveryCut);
                        for (String line : discoveryMaps.split("\n")) {
                            String path = mappedPath(line);
                            if (path != null) add(new File(path), "runtime_dependency");
                        }
                    } catch (IOException error) { proc.put("maps", "").put("status", "unavailable").put("mapsTruncated", false); }
                    processes.put(proc);
                } catch (IOException ignored) { skipped++; }
            }
        }
        if (processes.length() == 0) stop = stop.equals("success") ? "processes_unavailable" : stop;
    }
    static String boundedUtf8(String text, int bytes) {
        byte[] encoded = text.getBytes(StandardCharsets.UTF_8);
        if (encoded.length <= bytes) return text;
        int end = bytes;
        while (end > 0 && (encoded[end] & 0xc0) == 0x80) end--;
        return new String(encoded, 0, end, StandardCharsets.UTF_8);
    }
    static String mappedPath(String line) {
        String[] fields = line.trim().split("\\s+", 6);
        if (fields.length != 6 || !fields[5].startsWith("/") || fields[5].endsWith(" (deleted)")) return null;
        return fields[5];
    }
    private String read(File file, int limit) throws Exception {
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096]; int n;
            while (out.size() < limit && active() && (n = in.read(buf, 0, Math.min(buf.length, limit - out.size()))) != -1) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
    private void walk(File dir, int depth, String role, boolean framework) throws Exception {
        if (!active() || depth < 0) return;
        if (++directories > 64) { truncated = true; return; }
        String canonical = safe(dir);
        if (canonical == null || !dir.isDirectory()) return;
        File[] entries = dir.listFiles();
        if (entries == null) { skipped++; return; }
        {
            for (File entry : entries) {
                if (!active()) break;
                if (++candidates > 2048) { truncated = true; break; }
                File file = entry;
                if (safe(file) == null) { skipped++; continue; }
                if (file.isDirectory()) { walk(file, depth - 1, role, framework); continue; }
                String name = file.getName().toLowerCase(Locale.ROOT);
                if (!framework) { if (name.endsWith(".so")) add(file, role); continue; }
                boolean boot = name.startsWith("boot") && name.matches(".*\\.(oat|art|vdex)$");
                boolean relevant = name.contains("pioneer") || name.contains("meter") || name.contains("setting");
                if (boot || (relevant && name.matches(".*\\.(jar|odex|vdex|oat|art)$")) || (name.endsWith(".jar") && relevantJar(file))) {
                    add(file, boot ? "boot_framework" : role);
                    corresponding(file);
                }
            }
        }
    }
    private boolean relevantJar(File file) throws Exception {
        if (scanBytes >= 32L * 1024 * 1024) { truncated = true; return false; }
        try (ZipFile zip = new ZipFile(file)) {
            Enumeration<? extends ZipEntry> entries = zip.entries(); int count = 0;
            while (entries.hasMoreElements() && count++ < 512 && active()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (name.contains("pioneer") || name.contains("meterservice") || name.contains("settingservice")) return true;
                if (!name.matches("classes[0-9]*\\.dex")) continue;
                try (InputStream in = zip.getInputStream(entry)) {
                    byte[] buf = new byte[8192]; int n; long local = 0; String tail = "";
                    while (active() && local < 8L * 1024 * 1024 && scanBytes < 32L * 1024 * 1024 && (n = in.read(buf)) != -1) {
                        local += n; scanBytes += n;
                        String text = tail + new String(buf, 0, n, StandardCharsets.ISO_8859_1);
                        if (text.contains("jp/pioneer/") || text.contains("IMeterService") || text.contains("ISettingService")) return true;
                        tail = text.substring(Math.max(0, text.length() - 64));
                    }
                    if (local >= 8L * 1024 * 1024 || scanBytes >= 32L * 1024 * 1024) truncated = true;
                }
            }
            if (entries.hasMoreElements()) truncated = true;
        } catch (IOException ignored) { skipped++; }
        return false;
    }
    private void corresponding(File file) throws Exception {
        String name = file.getName(); int dot = name.lastIndexOf('.');
        if (dot < 0) return;
        String stem = name.substring(0, dot);
        boolean bootClasspath = file.getParentFile() != null && "framework".equals(file.getParentFile().getName());
        for (String arch : new String[]{"arm", "arm64", "x86", "x86_64"})
            for (String ext : new String[]{".odex", ".vdex", ".oat", ".art"}) {
                File code = new File(file.getParentFile(), "oat/" + arch + "/" + stem + ext);
                if (code.isFile()) add(code, "framework_compiled");
                if (bootClasspath) {
                    // Boot jars are compiled beside the arch directory as boot-<stem>.<ext>.
                    File boot = new File(file.getParentFile(), arch + "/boot-" + stem + ext);
                    if (boot.isFile()) add(boot, "boot_framework");
                }
            }
    }
    private void dependencies(File file) throws Exception {
        if (!active()) return;
        try {
            for (String name : needed(file, current)) {
                if (!active()) break;
                boolean found = false;
                for (String root : new String[]{"/system/lib/", "/system/lib64/", "/vendor/lib/", "/vendor/lib64/",
                        "/product/lib/", "/product/lib64/", "/system_ext/lib/", "/system_ext/lib64/"}) {
                    File dependency = new File(root + name);
                    if (dependency.isFile()) { add(dependency, "elf_dependency"); found = true; }
                }
                if (!found) skipped++;
            }
        } catch (IOException ignored) { skipped++; }
    }
    /** Bounded ELF32/ELF64 program-header/dynamic-table reader, independent of sections/tools. */
    static List<String> needed(File file, Callable<Boolean> current) throws Exception {
        List<String> names = new ArrayList<>();
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            if (in.length() < 64 || in.readInt() != 0x7f454c46) return names;
            int bits = in.readUnsignedByte(), encoding = in.readUnsignedByte();
            if ((bits != 1 && bits != 2) || (encoding != 1 && encoding != 2)) return names;
            boolean wide = bits == 2, little = encoding == 1;
            in.seek(wide ? 32 : 28); long ph = number(in, wide ? 8 : 4, little);
            in.seek(wide ? 54 : 42); long size = number(in, 2, little), count = number(in, 2, little);
            if (count > 128 || size < (wide ? 56 : 32) || ph < 0 || ph > in.length() - size * count) return names;
            List<long[]> loads = new ArrayList<>(); long dynamic = -1, dynamicSize = 0;
            for (int i = 0; i < count; i++) {
                if (!current.call()) throw new IOException("CANCELLED");
                in.seek(ph + size * i); long type = number(in, 4, little);
                if (wide) number(in, 4, little);
                long offset = number(in, wide ? 8 : 4, little), address = number(in, wide ? 8 : 4, little);
                number(in, wide ? 8 : 4, little); long length = number(in, wide ? 8 : 4, little);
                if (offset < 0 || length < 0 || offset > in.length() - length) continue;
                if (type == 1) loads.add(new long[]{offset, address, length});
                if (type == 2) { dynamic = offset; dynamicSize = length; }
            }
            if (dynamic < 0) return names;
            long strings = -1, stringSize = 0; List<Long> offsets = new ArrayList<>();
            int word = wide ? 8 : 4;
            for (int i = 0; i < Math.min(2048L, dynamicSize / (word * 2)); i++) {
                if (!current.call()) throw new IOException("CANCELLED");
                in.seek(dynamic + (long) i * word * 2);
                long tag = number(in, word, little), value = number(in, word, little);
                if (tag == 0) break;
                if (tag == 1 && offsets.size() < 64) offsets.add(value);
                if (tag == 5) strings = value;
                if (tag == 10) stringSize = value;
            }
            for (long[] load : loads) {
                if (strings < load[1] || strings - load[1] >= load[2]) continue;
                long table = load[0] + strings - load[1];
                long available = Math.min(stringSize, load[2] - (strings - load[1]));
                for (long offset : offsets) {
                    if (!current.call()) throw new IOException("CANCELLED");
                    if (offset < 0 || offset >= available) continue;
                    in.seek(table + offset); StringBuilder name = new StringBuilder();
                    for (int j = 0; j < 128 && offset + j < available; j++) {
                        int b = in.read(); if (b <= 0) break; name.append((char) b);
                    }
                    if (name.toString().matches("[A-Za-z0-9_+.-]{1,120}\\.so")) names.add(name.toString());
                }
                break;
            }
        }
        return names;
    }
    private static long number(RandomAccessFile in, int bytes, boolean little) throws IOException {
        long result = 0;
        for (int i = 0; i < bytes; i++) result |= (long) in.readUnsignedByte() << ((little ? i : bytes - 1 - i) * 8);
        return result;
    }
}
