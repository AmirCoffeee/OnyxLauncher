package me.amir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

public class ForgeInstaller {

    private static final String PROMOTIONS_URL =
        "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json";
    private static final String MAVEN_BASE =
        "https://maven.minecraftforge.net/net/minecraftforge/forge/";

    public interface ProgressCallback {
        void onProgress(String message, int percent);
    }

    public static List<String> fetchSupportedMcVersions() throws Exception {
        JsonObject promos = fetchObject(PROMOTIONS_URL).getAsJsonObject("promos");
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Map.Entry<String, ?> e : promos.entrySet()) {
            String key = e.getKey();
            int dash = key.lastIndexOf('-');
            if (dash > 0) seen.add(key.substring(0, dash));
        }
        List<String> list = new ArrayList<>(seen);
        list.sort((a, b) -> -compareVersion(a, b));
        return list;
    }

    public static List<String> fetchForgeVersions(String mcVersion) throws Exception {
        String metaUrl = MAVEN_BASE + "maven-metadata.json";
        List<String> result = new ArrayList<>();
        try {
            String xmlUrl = MAVEN_BASE + "maven-metadata.xml";
            String xml = fetchText(xmlUrl);
            int idx = 0;
            while ((idx = xml.indexOf("<version>", idx)) >= 0) {
                int end = xml.indexOf("</version>", idx);
                if (end < 0) break;
                String ver = xml.substring(idx + 9, end).trim();
                if (ver.startsWith(mcVersion + "-")) {
                    result.add(0, ver);
                }
                idx = end + 10;
            }
        } catch (Exception ignored) {}

        if (result.isEmpty()) {
            JsonObject promos = fetchObject(PROMOTIONS_URL).getAsJsonObject("promos");
            for (String suffix : new String[]{"latest", "recommended"}) {
                String key = mcVersion + "-" + suffix;
                if (promos.has(key)) {
                    String forgeVer = promos.get(key).getAsString();
                    String fullId   = mcVersion + "-" + forgeVer;
                    if (!result.contains(fullId)) result.add(fullId);
                }
            }
        }

        result.sort((a, b) -> -compareForgeVersion(a, b));
        return result;
    }

    public static String installForge(String forgeVersion, ProgressCallback cb) throws Exception {
        String mcDir = AppConfig.getMinecraftDir();
        cb.onProgress("Preparing Forge installer…", 2);

        String installerName = "forge-" + forgeVersion + "-installer.jar";
        String installerUrl  = MAVEN_BASE + forgeVersion + "/" + installerName;

        File tempDir = new File(System.getProperty("java.io.tmpdir"), "onyx-forge-" + forgeVersion);
        tempDir.mkdirs();
        File installerJar = new File(tempDir, installerName);

        cb.onProgress("Downloading Forge installer…", 5);
        downloadFile(installerUrl, installerJar, cb, 5, 50);

        cb.onProgress("Running Forge installer (this may take a minute)…", 50);
        String javaPath = findJava();
        ProcessBuilder pb = new ProcessBuilder(
            javaPath, "-jar", installerJar.getAbsolutePath(),
            "--installClient", mcDir
        );
        pb.redirectErrorStream(true);
        Process proc = pb.start();

        try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            String line;
            int pct = 50;
            while ((line = br.readLine()) != null) {
                System.out.println("[Forge] " + line);
                if (pct < 95) pct++;
                cb.onProgress("[Forge] " + line, pct);
            }
        }

        int exit = proc.waitFor();
        if (exit != 0) {
            throw new IOException("Forge installer exited with code " + exit
                + ". Check that Minecraft " + extractMcVersion(forgeVersion) + " is installed first.");
        }

        String versionId = deriveForgeVersionId(mcDir, forgeVersion);
        cb.onProgress("Forge " + forgeVersion + " installed!", 100);
        return versionId;
    }

    private static String deriveForgeVersionId(String mcDir, String forgeVersion) {
        String mcVer    = extractMcVersion(forgeVersion);
        String forgeVer = forgeVersion.substring(mcVer.length() + 1);
        String candidate = mcVer + "-forge-" + forgeVer;

        File versionsDir = new File(mcDir, "versions");
        if (versionsDir.exists()) {
            for (File f : Objects.requireNonNull(versionsDir.listFiles())) {
                if (f.isDirectory() && f.getName().toLowerCase().contains("forge")) {
                    return f.getName();
                }
            }
        }
        return candidate;
    }

    public static String extractMcVersion(String forgeVersion) {
        int dash = forgeVersion.indexOf('-');
        return dash >= 0 ? forgeVersion.substring(0, dash) : forgeVersion;
    }

    private static String findJava() {
        String jh = System.getenv("JAVA_HOME");
        if (jh != null) {
            File f = new File(jh, "bin/java");
            if (f.exists()) return f.getAbsolutePath();
            File fw = new File(jh, "bin/java.exe");
            if (fw.exists()) return fw.getAbsolutePath();
        }
        String jhProp = System.getProperty("java.home");
        if (jhProp != null) {
            File f = new File(jhProp, "bin/java");
            if (f.exists()) return f.getAbsolutePath();
            f = new File(jhProp, "bin/java.exe");
            if (f.exists()) return f.getAbsolutePath();
        }
        return "java";
    }

    private static void downloadFile(String urlStr, File dest,
                                     ProgressCallback cb, int pctStart, int pctEnd)
            throws Exception {
        if (dest.exists() && dest.length() > 1024) {
            cb.onProgress("Already downloaded: " + dest.getName(), pctEnd);
            return;
        }
        dest.getParentFile().mkdirs();
        HttpURLConnection con = open(urlStr);
        long total = con.getContentLengthLong();
        try (InputStream in = con.getInputStream();
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[32768];
            long done = 0;
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
                done += r;
                if (total > 0 && cb != null) {
                    int pct = pctStart + (int) ((done * (pctEnd - pctStart)) / total);
                    cb.onProgress("Downloading " + dest.getName() + "…", pct);
                }
            }
        }
    }

    private static JsonObject fetchObject(String urlStr) throws Exception {
        try (InputStreamReader r = new InputStreamReader(open(urlStr).getInputStream())) {
            return JsonParser.parseReader(r).getAsJsonObject();
        }
    }

    private static String fetchText(String urlStr) throws Exception {
        HttpURLConnection con = open(urlStr);
        try (BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        }
    }

    private static HttpURLConnection open(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setRequestProperty("User-Agent", "OnyxLauncher/1.0");
        c.setConnectTimeout(20_000);
        c.setReadTimeout(60_000);
        return c;
    }

    private static int compareVersion(String a, String b) {
        int[] pa = parseParts(a), pb = parseParts(b);
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            int ia = i < pa.length ? pa[i] : 0;
            int ib = i < pb.length ? pb[i] : 0;
            if (ia != ib) return Integer.compare(ia, ib);
        }
        return 0;
    }

    private static int compareForgeVersion(String a, String b) {
        return compareVersion(
            a.contains("-") ? a.substring(a.indexOf('-') + 1) : a,
            b.contains("-") ? b.substring(b.indexOf('-') + 1) : b
        );
    }

    private static int[] parseParts(String v) {
        String[] parts = v.split("[.\\-]");
        int[] nums = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { nums[i] = Integer.parseInt(parts[i]); }
            catch (NumberFormatException ignored) { nums[i] = 0; }
        }
        return nums;
    }
}
