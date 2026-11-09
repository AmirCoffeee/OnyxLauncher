package me.amir;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;

public class NeoForgeInstaller {

    private static final String META_URL  =
        "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml";
    private static final String MAVEN_BASE =
        "https://maven.neoforged.net/releases/net/neoforged/neoforge/";

    public interface ProgressCallback {
        void onProgress(String message, int percent);
    }

    public static List<String> fetchSupportedMcVersions() throws Exception {
        List<String> all = fetchAllVersions();
        LinkedHashSet<String> mc = new LinkedHashSet<>();
        for (String v : all) {
            String mcv = extractMcVersion(v);
            if (mcv != null) mc.add(mcv);
        }
        List<String> list = new ArrayList<>(mc);
        list.sort((a, b) -> -compareSemVer(a, b));
        return list;
    }

    public static List<String> fetchVersionsForMc(String mcVersion) throws Exception {
        List<String> all = fetchAllVersions();
        List<String> out = new ArrayList<>();
        for (String v : all) {
            String mv = extractMcVersion(v);
            if (mcVersion.equals(mv)) out.add(v);
        }
        out.sort((a, b) -> -compareSemVer(a, b));
        return out;
    }

    private static List<String> fetchAllVersions() throws Exception {
        String xml = fetchText(META_URL);
        List<String> result = new ArrayList<>();
        int idx = 0;
        while ((idx = xml.indexOf("<version>", idx)) >= 0) {
            int end = xml.indexOf("</version>", idx);
            if (end < 0) break;
            result.add(xml.substring(idx + 9, end).trim());
            idx = end + 10;
        }
        return result;
    }

    public static String installNeoForge(String neoForgeVersion, ProgressCallback cb) throws Exception {
        String mcDir = AppConfig.getMinecraftDir();
        cb.onProgress("Preparing NeoForge installer…", 2);

        String installerName = "neoforge-" + neoForgeVersion + "-installer.jar";
        String installerUrl  = MAVEN_BASE + neoForgeVersion + "/" + installerName;

        File tempDir = new File(System.getProperty("java.io.tmpdir"), "onyx-neoforge-" + neoForgeVersion);
        tempDir.mkdirs();
        File installerJar = new File(tempDir, installerName);

        cb.onProgress("Downloading NeoForge installer…", 5);
        downloadFile(installerUrl, installerJar, cb, 5, 50);

        cb.onProgress("Running NeoForge installer…", 50);
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
                System.out.println("[NeoForge] " + line);
                if (pct < 95) pct++;
                cb.onProgress("[NeoForge] " + line, pct);
            }
        }

        int exit = proc.waitFor();
        if (exit != 0)
            throw new IOException("NeoForge installer exited with code " + exit);

        String versionId = deriveVersionId(mcDir, neoForgeVersion);
        cb.onProgress("NeoForge " + neoForgeVersion + " installed!", 100);
        return versionId;
    }

    private static String extractMcVersion(String neoForgeVer) {
        String[] parts = neoForgeVer.split("\\.");
        if (parts.length >= 2) {
            try {
                int major = Integer.parseInt(parts[0]);
                int minor = Integer.parseInt(parts[1]);
                return "1." + major + "." + minor;
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private static String deriveVersionId(String mcDir, String neoForgeVersion) {
        File versionsDir = new File(mcDir, "versions");
        if (versionsDir.exists()) {
            for (File f : Objects.requireNonNull(versionsDir.listFiles())) {
                if (f.isDirectory() && f.getName().toLowerCase().contains("neoforge")) {
                    return f.getName();
                }
            }
        }
        return "neoforge-" + neoForgeVersion;
    }

    private static String findJava() {
        String jh = System.getProperty("java.home");
        if (jh != null) {
            File f = new File(jh, "bin/java");
            if (f.exists()) return f.getAbsolutePath();
        }
        return "java";
    }

    private static void downloadFile(String urlStr, File dest, ProgressCallback cb, int s, int e2) throws Exception {
        if (dest.exists() && dest.length() > 1024) { cb.onProgress("Already downloaded", e2); return; }
        dest.getParentFile().mkdirs();
        HttpURLConnection con = open(urlStr);
        long total = con.getContentLengthLong();
        try (InputStream in = con.getInputStream(); FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[32768]; long done = 0; int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r); done += r;
                if (total > 0) cb.onProgress("Downloading…", s + (int)(done * (e2 - s) / total));
            }
        }
    }

    private static String fetchText(String urlStr) throws Exception {
        HttpURLConnection con = open(urlStr);
        try (BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream()))) {
            StringBuilder sb = new StringBuilder(); String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        }
    }

    private static HttpURLConnection open(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setRequestProperty("User-Agent", "OnyxLauncher/1.0");
        c.setConnectTimeout(20_000); c.setReadTimeout(60_000);
        return c;
    }

    private static int compareSemVer(String a, String b) {
        String[] pa = a.split("\\."), pb = b.split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            int ia = 0, ib = 0;
            try { if (i < pa.length) ia = Integer.parseInt(pa[i]); } catch (Exception ignored) {}
            try { if (i < pb.length) ib = Integer.parseInt(pb[i]); } catch (Exception ignored) {}
            if (ia != ib) return Integer.compare(ia, ib);
        }
        return 0;
    }
}
