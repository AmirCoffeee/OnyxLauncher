package me.amir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class OptiFineInstaller {

    private static final String OPTIFINE_LIST_URL =
        "https://optifine.net/adloadx?f=OptiFine_";

    public interface ProgressCallback {
        void onProgress(String message, int percent);
    }

    public static class OptiFineVersion {
        public final String mcVersion;
        public final String ofEdition;
        public final String filename;
        public final String downloadUrl;

        public OptiFineVersion(String mcVersion, String ofEdition, String filename, String downloadUrl) {
            this.mcVersion   = mcVersion;
            this.ofEdition   = ofEdition;
            this.filename    = filename;
            this.downloadUrl = downloadUrl;
        }

        @Override public String toString() { return ofEdition + " (" + mcVersion + ")"; }
    }

    private static final Map<String, List<String>> KNOWN_VERSIONS = new LinkedHashMap<>();
    static {
        KNOWN_VERSIONS.put("1.21.4", List.of("HD_U_J1_pre9"));
        KNOWN_VERSIONS.put("1.21.1", List.of("HD_U_J1"));
        KNOWN_VERSIONS.put("1.20.6", List.of("HD_U_I8_pre5"));
        KNOWN_VERSIONS.put("1.20.4", List.of("HD_U_I7", "HD_U_I6", "HD_U_I5"));
        KNOWN_VERSIONS.put("1.20.2", List.of("HD_U_I6_pre5"));
        KNOWN_VERSIONS.put("1.20.1", List.of("HD_U_I6", "HD_U_I5", "HD_U_I4"));
        KNOWN_VERSIONS.put("1.19.4", List.of("HD_U_I4_pre7", "HD_U_I3"));
        KNOWN_VERSIONS.put("1.19.3", List.of("HD_U_I4_pre5"));
        KNOWN_VERSIONS.put("1.19.2", List.of("HD_U_H8", "HD_U_H7", "HD_U_H6"));
        KNOWN_VERSIONS.put("1.18.2", List.of("HD_U_H7", "HD_U_H6", "HD_U_H5"));
        KNOWN_VERSIONS.put("1.17.1", List.of("HD_U_G9", "HD_U_G8"));
        KNOWN_VERSIONS.put("1.16.5", List.of("HD_U_G8", "HD_U_G7", "HD_U_G6"));
        KNOWN_VERSIONS.put("1.12.2", List.of("HD_U_F5", "HD_U_E3"));
        KNOWN_VERSIONS.put("1.8.9",  List.of("HD_U_M5", "HD_U_L5"));
        KNOWN_VERSIONS.put("1.7.10", List.of("HD_U_E7", "HD_U_D8"));
    }

    public static List<String> supportedMcVersions() {
        return new ArrayList<>(KNOWN_VERSIONS.keySet());
    }

    public static List<OptiFineVersion> versionsForMc(String mcVersion) {
        List<String> editions = KNOWN_VERSIONS.getOrDefault(mcVersion, List.of());
        List<OptiFineVersion> out = new ArrayList<>();
        for (String ed : editions) {
            String filename = "OptiFine_" + mcVersion + "_" + ed + ".jar";
            String url = "https://optifine.net/adloadx?f=" + filename;
            out.add(new OptiFineVersion(mcVersion, ed, filename, url));
        }
        return out;
    }

    public static String installOptiFine(String mcVersion, String ofEdition,
                                         ProgressCallback cb) throws Exception {
        String mcDir   = AppConfig.getMinecraftDir();
        String filename = "OptiFine_" + mcVersion + "_" + ofEdition + ".jar";
        String versionId = mcVersion + "-OptiFine_" + ofEdition;

        cb.onProgress("Preparing OptiFine download…", 2);

        File cacheDir = new File(System.getProperty("java.io.tmpdir"), "onyx-optifine");
        cacheDir.mkdirs();
        File ofJar = new File(cacheDir, filename);

        if (!ofJar.exists() || ofJar.length() < 1024) {
            cb.onProgress("Downloading OptiFine JAR…", 5);
            boolean downloaded = false;
            String[] mirrors = {
                "https://optifine.net/adloadx?f=" + filename,
                "https://optifine.net/downloadx?f=" + filename + "&x=optifine.net"
            };
            for (String mirrorUrl : mirrors) {
                try {
                    downloadFile(mirrorUrl, ofJar, cb, 5, 45);
                    if (ofJar.length() > 1024) { downloaded = true; break; }
                } catch (Exception ignored) {}
            }
            if (!downloaded)
                throw new IOException(
                    "Could not download OptiFine " + ofEdition + " for " + mcVersion + ".\n" +
                    "Please download " + filename + " manually from optifine.net\n" +
                    "and place it in: " + cacheDir.getAbsolutePath());
        } else {
            cb.onProgress("OptiFine JAR already cached", 45);
        }

        cb.onProgress("Creating OptiFine version profile…", 50);

        File baseVersionDir = new File(mcDir, "versions/" + mcVersion);
        File baseJsonFile   = new File(baseVersionDir, mcVersion + ".json");
        if (!baseJsonFile.exists())
            throw new IllegalStateException(
                "Vanilla " + mcVersion + " is not installed. Install it first.");

        JsonObject baseJson;
        try (FileReader fr = new FileReader(baseJsonFile)) {
            baseJson = JsonParser.parseReader(fr).getAsJsonObject();
        }

        File ofVersionDir = new File(mcDir, "versions/" + versionId);
        ofVersionDir.mkdirs();

        File ofLibDir = new File(mcDir, "libraries/optifine/OptiFine/" + mcVersion + "_" + ofEdition);
        ofLibDir.mkdirs();
        File ofLibJar = new File(ofLibDir, "OptiFine-" + mcVersion + "_" + ofEdition + ".jar");

        cb.onProgress("Copying OptiFine library…", 55);
        copyFile(ofJar, ofLibJar);

        String mainClass = baseJson.has("mainClass")
            ? baseJson.get("mainClass").getAsString()
            : "net.minecraft.client.main.Main";

        JsonObject ofJson = new JsonObject();
        ofJson.addProperty("id", versionId);
        ofJson.addProperty("type", "release");
        ofJson.addProperty("mainClass", mainClass);
        ofJson.addProperty("inheritsFrom", mcVersion);
        ofJson.addProperty("time", "1970-01-01T00:00:00+00:00");
        ofJson.addProperty("releaseTime", "1970-01-01T00:00:00+00:00");

        com.google.gson.JsonArray libs = new com.google.gson.JsonArray();
        JsonObject ofLib = new JsonObject();
        ofLib.addProperty("name", "optifine:OptiFine:" + mcVersion + "_" + ofEdition);
        JsonObject dlObj = new JsonObject();
        JsonObject artObj = new JsonObject();
        artObj.addProperty("path", "optifine/OptiFine/" + mcVersion + "_" + ofEdition
            + "/OptiFine-" + mcVersion + "_" + ofEdition + ".jar");
        artObj.addProperty("url", "");
        artObj.addProperty("sha1", "");
        artObj.addProperty("size", ofLibJar.length());
        dlObj.add("artifact", artObj);
        ofLib.add("downloads", dlObj);
        libs.add(ofLib);
        ofJson.add("libraries", libs);

        File ofJsonFile = new File(ofVersionDir, versionId + ".json");
        try (FileWriter fw = new FileWriter(ofJsonFile)) {
            fw.write(ofJson.toString());
        }

        File ofVersionJar = new File(ofVersionDir, versionId + ".jar");
        if (!ofVersionJar.exists()) {
            File baseJar = new File(baseVersionDir, mcVersion + ".jar");
            if (baseJar.exists()) copyFile(baseJar, ofVersionJar);
        }

        cb.onProgress("OptiFine " + ofEdition + " installed!", 100);
        return versionId;
    }

    public static File locateCachedJar(String mcVersion, String ofEdition) {
        String filename = "OptiFine_" + mcVersion + "_" + ofEdition + ".jar";
        File cacheDir = new File(System.getProperty("java.io.tmpdir"), "onyx-optifine");
        return new File(cacheDir, filename);
    }

    public static void importLocalJar(File localJar, String mcVersion, String ofEdition,
                                      ProgressCallback cb) throws Exception {
        File cacheDir = new File(System.getProperty("java.io.tmpdir"), "onyx-optifine");
        cacheDir.mkdirs();
        String filename = "OptiFine_" + mcVersion + "_" + ofEdition + ".jar";
        File dest = new File(cacheDir, filename);
        cb.onProgress("Importing local OptiFine JAR…", 10);
        copyFile(localJar, dest);
        cb.onProgress("Import complete", 100);
    }

    private static void downloadFile(String urlStr, File dest, ProgressCallback cb, int s, int e2) throws Exception {
        dest.getParentFile().mkdirs();
        HttpURLConnection con = open(urlStr);
        int status = con.getResponseCode();
        if (status != 200) throw new IOException("HTTP " + status + " for " + urlStr);
        long total = con.getContentLengthLong();
        try (InputStream in = con.getInputStream(); FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[32768]; long done = 0; int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r); done += r;
                if (total > 0 && cb != null)
                    cb.onProgress("Downloading…", s + (int)(done * (e2 - s) / total));
            }
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        dst.getParentFile().mkdirs();
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[32768]; int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        }
    }

    private static HttpURLConnection open(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setRequestProperty("User-Agent",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/120.0.0.0");
        c.setConnectTimeout(20_000);
        c.setReadTimeout(60_000);
        c.setInstanceFollowRedirects(true);
        return c;
    }
}
