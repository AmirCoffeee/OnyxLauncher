package me.amir;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Downloads and installs vanilla Minecraft versions.
 *
 * Features:
 * - Parallel library/asset downloads (8 threads)
 * - SHA-1 based resume: skips files that are already correct
 * - Progress callback with 0-100 %
 */
public class MinecraftInstaller {

    private static final String MANIFEST_URL =
        "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json";

    public interface ProgressCallback {
        void onProgress(String message, int percent);
    }

    // -------------------------------------------------------------------------
    // Version lists
    // -------------------------------------------------------------------------

    public static List<String> fetchReleaseVersions() throws Exception {
        return fetchVersionsByType("release");
    }

    public static List<String> fetchAllVersions() throws Exception {
        return fetchVersionsByType(null);
    }

    /**
     * Returns version IDs filtered by Mojang type.
     * Known types: "release", "snapshot", "old_beta", "old_alpha"
     * Pass null for all types.
     */
    public static List<String> fetchVersionsByType(String type) throws Exception {
        JsonArray versions = fetchObject(MANIFEST_URL).getAsJsonArray("versions");
        List<String> result = new ArrayList<>();
        for (JsonElement el : versions) {
            JsonObject v = el.getAsJsonObject();
            if (type == null || v.get("type").getAsString().equals(type))
                result.add(v.get("id").getAsString());
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Install
    // -------------------------------------------------------------------------

    public static void installVersion(String versionId, ProgressCallback cb) throws Exception {
        String mcDir = AppConfig.getMinecraftDir();

        cb.onProgress("Fetching manifest…", 2);
        JsonArray versions = fetchObject(MANIFEST_URL).getAsJsonArray("versions");

        String versionUrl = null;
        for (JsonElement el : versions) {
            JsonObject v = el.getAsJsonObject();
            if (v.get("id").getAsString().equals(versionId)) {
                versionUrl = v.get("url").getAsString();
                break;
            }
        }
        if (versionUrl == null) throw new IllegalArgumentException("Version not found: " + versionId);

        cb.onProgress("Fetching version JSON…", 4);
        JsonObject versionJson = fetchObject(versionUrl);

        // ── 1. Save version JSON ──────────────────────────────────────────────
        File versionDir = new File(mcDir, "versions/" + versionId);
        versionDir.mkdirs();
        File jsonFile = new File(versionDir, versionId + ".json");
        try (FileWriter fw = new FileWriter(jsonFile)) {
            fw.write(versionJson.toString());
        }

        // ── 2. Client JAR ─────────────────────────────────────────────────────
        cb.onProgress("Downloading client JAR…", 6);
        JsonObject clientDl = versionJson.getAsJsonObject("downloads").getAsJsonObject("client");
        String jarUrl  = clientDl.get("url").getAsString();
        long   jarSize = clientDl.get("size").getAsLong();
        String jarSha1 = clientDl.has("sha1") ? clientDl.get("sha1").getAsString() : null;
        File   jarFile = new File(versionDir, versionId + ".jar");
        if (!isFileValid(jarFile, jarSize, jarSha1)) {
            downloadFile(jarUrl, jarFile, jarSize, 6, 20, cb);
        } else {
            cb.onProgress("Client JAR already up-to-date", 20);
        }

        // ── 3. Libraries (parallel) ───────────────────────────────────────────
        cb.onProgress("Downloading libraries…", 20);
        JsonArray libraries = versionJson.getAsJsonArray("libraries");
        downloadLibrariesParallel(mcDir, libraries, cb, 20, 55);

        // ── 4. Asset index ────────────────────────────────────────────────────
        cb.onProgress("Downloading asset index…", 55);
        JsonObject assetIdx    = versionJson.getAsJsonObject("assetIndex");
        String     assetId     = assetIdx.get("id").getAsString();
        String     assetIdxUrl = assetIdx.get("url").getAsString();
        File assetIdxDir  = new File(mcDir, "assets/indexes");
        assetIdxDir.mkdirs();
        File assetIdxFile = new File(assetIdxDir, assetId + ".json");
        if (!assetIdxFile.exists()) {
            downloadFile(assetIdxUrl, assetIdxFile, -1, 55, 57, cb);
        }

        // ── 5. Asset objects (parallel) ───────────────────────────────────────
        cb.onProgress("Downloading assets…", 57);
        JsonObject objects = fetchObject(assetIdxUrl).getAsJsonObject("objects");
        downloadAssetsParallel(mcDir, objects, cb, 57, 99);

        cb.onProgress("Installation complete!", 100);
    }

    // -------------------------------------------------------------------------
    // Parallel library download
    // -------------------------------------------------------------------------

    private static void downloadLibrariesParallel(String mcDir, JsonArray libraries,
            ProgressCallback cb, int pctStart, int pctEnd) throws Exception {

        List<Runnable> tasks = new ArrayList<>();
        String os = System.getProperty("os.name").toLowerCase();
        String osName = os.contains("win") ? "windows" : os.contains("mac") ? "osx" : "linux";

        for (JsonElement el : libraries) {
            JsonObject lib = el.getAsJsonObject();
            if (!isLibraryAllowed(lib, osName)) continue;

            if (lib.has("downloads")) {
                JsonObject dl = lib.getAsJsonObject("downloads");

                if (dl.has("artifact")) {
                    JsonObject art = dl.getAsJsonObject("artifact");
                    String url   = art.has("url") ? art.get("url").getAsString() : "";
                    if (!url.isEmpty()) {
                        String path  = art.get("path").getAsString();
                        long   size  = art.get("size").getAsLong();
                        String sha1  = art.has("sha1") ? art.get("sha1").getAsString() : null;
                        File   dest  = new File(mcDir, "libraries/" + path);
                        if (!isFileValid(dest, size, sha1)) {
                            tasks.add(() -> {
                                dest.getParentFile().mkdirs();
                                try { downloadFile(url, dest, size, -1, -1, null); }
                                catch (Exception e) { System.err.println("[Libs] " + e.getMessage()); }
                            });
                        }
                    }
                }

                // Natives classifier
                String nativeKey = getNativeClassifier(lib, osName);
                if (nativeKey != null && dl.has("classifiers")) {
                    JsonObject cls = dl.getAsJsonObject("classifiers");
                    if (cls.has(nativeKey)) {
                        JsonObject nat  = cls.getAsJsonObject(nativeKey);
                        String natUrl  = nat.has("url") ? nat.get("url").getAsString() : "";
                        if (!natUrl.isEmpty()) {
                            String path = nat.get("path").getAsString();
                            long   size = nat.get("size").getAsLong();
                            String sha1 = nat.has("sha1") ? nat.get("sha1").getAsString() : null;
                            File   dest = new File(mcDir, "libraries/" + path);
                            if (!isFileValid(dest, size, sha1)) {
                                tasks.add(() -> {
                                    dest.getParentFile().mkdirs();
                                    try { downloadFile(natUrl, dest, size, -1, -1, null); }
                                    catch (Exception e) { System.err.println("[Natives] " + e.getMessage()); }
                                });
                            }
                        }
                    }
                }
            }
        }

        runParallel(tasks, cb, pctStart, pctEnd);
    }

    // -------------------------------------------------------------------------
    // Parallel asset download
    // -------------------------------------------------------------------------

    private static void downloadAssetsParallel(String mcDir, JsonObject objects,
            ProgressCallback cb, int pctStart, int pctEnd) throws Exception {

        File objectsDir = new File(mcDir, "assets/objects");
        objectsDir.mkdirs();

        List<Runnable> tasks = new ArrayList<>();
        for (String key : objects.keySet()) {
            JsonObject asset = objects.getAsJsonObject(key);
            String hash = asset.get("hash").getAsString();
            long   size = asset.get("size").getAsLong();
            String sub  = hash.substring(0, 2);
            File   dest = new File(objectsDir, sub + "/" + hash);
            if (isFileValid(dest, size, null)) continue; // already correct
            String url  = "https://resources.download.minecraft.net/" + sub + "/" + hash;
            tasks.add(() -> {
                dest.getParentFile().mkdirs();
                try { downloadFile(url, dest, size, -1, -1, null); }
                catch (Exception e) { /* skip single bad asset */ }
            });
        }

        runParallel(tasks, cb, pctStart, pctEnd);
    }

    // -------------------------------------------------------------------------
    // Thread pool executor
    // -------------------------------------------------------------------------

    private static void runParallel(List<Runnable> tasks, ProgressCallback cb,
                                    int pctStart, int pctEnd) throws Exception {
        if (tasks.isEmpty()) return;
        int threads = Math.min(8, Runtime.getRuntime().availableProcessors() * 2);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger done = new AtomicInteger(0);
        int total = tasks.size();

        List<Future<?>> futures = new ArrayList<>();
        for (Runnable t : tasks) {
            futures.add(pool.submit(() -> {
                t.run();
                int n = done.incrementAndGet();
                if (cb != null && n % Math.max(1, total / 40) == 0) {
                    int pct = pctStart + (n * (pctEnd - pctStart) / total);
                    cb.onProgress(n + "/" + total + " files", pct);
                }
            }));
        }
        pool.shutdown();
        // Collect exceptions
        for (Future<?> f : futures) {
            try { f.get(); }
            catch (ExecutionException e) { /* individual errors already logged */ }
        }
        if (cb != null) cb.onProgress(total + "/" + total + " files", pctEnd);
    }

    // -------------------------------------------------------------------------
    // File download (single, with range-resume support)
    // -------------------------------------------------------------------------

    static void downloadFile(String urlStr, File dest, long expectedSize,
                             int startPct, int endPct, ProgressCallback cb) throws Exception {
        // Resume: if partial file exists, try Range request
        long existing = dest.exists() ? dest.length() : 0;

        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("User-Agent", "OnyxLauncher/1.0");
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        if (existing > 0) conn.setRequestProperty("Range", "bytes=" + existing + "-");
        conn.connect();

        int status = conn.getResponseCode();
        boolean append = (status == HttpURLConnection.HTTP_PARTIAL); // 206
        if (status != HttpURLConnection.HTTP_OK && !append) {
            throw new IOException("HTTP " + status + " for " + urlStr);
        }

        long total = expectedSize > 0 ? expectedSize
            : (conn.getContentLengthLong() + (append ? existing : 0));
        long downloaded = append ? existing : 0;

        try (InputStream in  = conn.getInputStream();
             OutputStream out = new FileOutputStream(dest, append)) {
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
                downloaded += r;
                if (cb != null && total > 0 && startPct >= 0 && endPct > startPct) {
                    int pct = startPct + (int)(downloaded * (endPct - startPct) / total);
                    cb.onProgress(dest.getName(), pct);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // SHA-1 validation
    // -------------------------------------------------------------------------

    /**
     * Returns true if the file exists, matches the expected size (if > 0),
     * and matches the SHA-1 hash (if provided).
     */
    static boolean isFileValid(File f, long expectedSize, String expectedSha1) {
        if (!f.exists()) return false;
        if (expectedSize > 0 && f.length() != expectedSize) return false;
        if (expectedSha1 != null && !expectedSha1.isEmpty()) {
            try {
                String actual = sha1(f);
                if (!actual.equalsIgnoreCase(expectedSha1)) return false;
            } catch (Exception e) { return false; }
        }
        return true;
    }

    private static String sha1(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // JSON helpers
    // -------------------------------------------------------------------------

    static JsonObject fetchObject(String urlStr) throws Exception {
        try (InputStreamReader r = new InputStreamReader(open(urlStr).getInputStream())) {
            return JsonParser.parseReader(r).getAsJsonObject();
        }
    }

    private static HttpURLConnection open(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setRequestProperty("User-Agent", "OnyxLauncher/1.0");
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        return c;
    }

    // -------------------------------------------------------------------------
    // OS / native helpers
    // -------------------------------------------------------------------------

    private static boolean isLibraryAllowed(JsonObject lib, String osName) {
        if (!lib.has("rules")) return true;
        boolean allowed = false;
        for (JsonElement rEl : lib.getAsJsonArray("rules")) {
            JsonObject rule = rEl.getAsJsonObject();
            String action = rule.get("action").getAsString();
            if (rule.has("os")) {
                if (rule.getAsJsonObject("os").get("name").getAsString().equals(osName))
                    allowed = action.equals("allow");
            } else {
                allowed = action.equals("allow");
            }
        }
        return allowed;
    }

    private static String getNativeClassifier(JsonObject lib, String osName) {
        if (!lib.has("natives")) return null;
        JsonObject natives = lib.getAsJsonObject("natives");
        if (natives.has(osName)) {
            // replace ${arch} if present
            String key = natives.get(osName).getAsString();
            String arch = System.getProperty("os.arch").contains("64") ? "64" : "32";
            return key.replace("${arch}", arch);
        }
        return null;
    }
}
