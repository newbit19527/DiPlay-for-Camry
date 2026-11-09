package com.shilapi.xcertplay.pioneer;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;

/** Reads the meter libraries' SHA-256 as diagnostics only. No allow-list: every firmware is supported. */
final class PioneerHudFirmware {
    static final String[] PATHS = {
        "/system/lib/libmeterservice.so",
        "/system/lib/libmeterservice_jni.so",
        "/system/lib/libNPMeterHandler.so"
    };
    interface HashReader { String sha(String path) throws Exception; }
    static JSONObject inspect(String scope, HashReader reader) throws Exception {
        JSONArray modules = new JSONArray();
        for (String path : PATHS) {
            JSONObject item = new JSONObject().put("path", path);
            try {
                String actual = reader.sha(path);
                if (!actual.matches("[0-9a-fA-F]{64}")) throw new IOException("Invalid SHA-256 output");
                item.put("actualSha256", actual.toLowerCase(java.util.Locale.ROOT)).put("status", "read");
            } catch (Exception error) {
                item.put("actualSha256", JSONObject.NULL).put("status", "read_failed")
                    .put("error", error.toString().substring(0, Math.min(1024, error.toString().length())));
            }
            modules.put(item);
        }
        return new JSONObject().put("scope", scope).put("checkedAtMs", System.currentTimeMillis())
            .put("modules", modules);
    }
    static String javaSha(String path, java.util.concurrent.Callable<Boolean> current) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream in = new java.io.FileInputStream(path)) {
            byte[] buffer = new byte[16384]; int n; long total = 0;
            while ((n = in.read(buffer)) != -1) {
                if (!current.call() || Thread.currentThread().isInterrupted()) throw new IOException("DIAGNOSTIC_CANCELLED");
                if ((total += n) > 64L * 1024 * 1024) throw new IOException("HASH_SIZE_LIMIT");
                digest.update(buffer, 0, n);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }
}
