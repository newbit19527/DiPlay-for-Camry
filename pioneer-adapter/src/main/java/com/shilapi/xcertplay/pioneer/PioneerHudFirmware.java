package com.shilapi.xcertplay.pioneer;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;

/** One whitelist for startup enforcement and read-only export evidence. Never weakens the gate. */
final class PioneerHudFirmware {
    static final String[][] MODULES = {
        {"/system/lib/libmeterservice.so", "20d3521ff1294d3a56579e1b0c649be581a94a9a0276a362ad79e130271dc660"},
        {"/system/lib/libmeterservice_jni.so", "e880f91b1932645d3ae2a83b739aa24c1fb0209cbc3f393b5f0539ef7981dbbc"},
        {"/system/lib/libNPMeterHandler.so", "937c951c9594057fed65be0bd41163379cca4a48c9ccd2f9b91929cdee2b49a6"}
    };
    interface HashReader { String sha(String path) throws Exception; }
    static JSONObject inspect(String scope, HashReader reader) throws Exception {
        JSONArray modules = new JSONArray();
        boolean passed = true;
        for (String[] module : MODULES) {
            JSONObject item = new JSONObject().put("path", module[0]).put("expectedSha256", module[1]);
            try {
                String actual = reader.sha(module[0]);
                if (!actual.matches("[0-9a-fA-F]{64}")) throw new IOException("Invalid SHA-256 output");
                boolean match = module[1].equalsIgnoreCase(actual);
                item.put("actualSha256", actual.toLowerCase(java.util.Locale.ROOT)).put("status", match ? "pass" : "mismatch");
                passed &= match;
            } catch (Exception error) {
                item.put("actualSha256", JSONObject.NULL).put("status", "read_failed")
                    .put("error", error.toString().substring(0, Math.min(1024, error.toString().length())));
                passed = false;
            }
            modules.put(item);
        }
        return new JSONObject().put("scope", scope).put("checkedAtMs", System.currentTimeMillis())
            .put("passed", passed).put("modules", modules);
    }
    static void requirePassed(JSONObject evidence) throws Exception {
        JSONArray modules = evidence.getJSONArray("modules");
        for (int i = 0; i < modules.length(); i++) {
            JSONObject item = modules.getJSONObject(i);
            if (!"pass".equals(item.getString("status"))) throw new IOException(
                ("mismatch".equals(item.getString("status")) ? "HUD_FILTER_FIRMWARE_MISMATCH " : "HUD_FILTER_FIRMWARE_READ_FAILED ") +
                item.getString("path") + " expected=" + item.getString("expectedSha256") +
                " actual=" + item.opt("actualSha256") + " error=" + item.optString("error"));
        }
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
