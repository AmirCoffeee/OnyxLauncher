package me.amir;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class FabricInstaller {
    private static final String FABRIC_META   = "https://meta.fabricmc.net/v2";
    private static final String FABRIC_MAVEN  = "https://maven.fabricmc.net/";

    public interface ProgressCallback {
        void onProgress(String message, int percent);
    }

    public static List<String> fetchLoaderVersions() throws Exception {
        JsonArray arr = fetchArray(FABRIC_META + "/versions/loader");
        List<String> stable = new ArrayList<>();
        List<String> all    = new ArrayList<>();
        for (JsonElement el : arr) {
            JsonObject obj = el.getAsJsonObject();
            String ver = obj.get("version").getAsString();
            all.add(ver);
            if (obj.has("stable") && obj.get("stable").getAsBoolean())
                stable.add(ver);
        }
        return stable.isEmpty() ? all : stable;
    }

    public static String fetchLatestLoaderVersion() throws Exception {
        List<String> vers = fetchLoaderVersions();
        if (vers.isEmpty()) throw new IOException("No Fabric loader versions found");
        return vers.get(0);
    }

    public static String installFabric(String mcVersion, ProgressCallback cb) throws Exception {
        return installFabric(mcVersion, fetchLatestLoaderVersion(), cb);
    }

    public static String installFabric(String mcVersion, String loaderVersion,
                                       ProgressCallback cb) throws Exception {
        String mcDir = AppConfig.getMinecraftDir();
        String fabricId = "fabric-loader-" + loaderVersion + "-" + mcVersion;

        cb.onProgress("Fetching Fabric profile…", 5);
        String profileUrl = FABRIC_META + "/versions/loader/" + mcVersion
            + "/" + loaderVersion + "/profile/json";
        JsonObject profile = fetchObject(profileUrl);

        File vDir = new File(mcDir, "versions/" + fabricId);
        vDir.mkdirs();
        try (FileWriter fw = new FileWriter(new File(vDir, fabricId + ".json"))) {
            fw.write(profile.toString());
        }
        cb.onProgress("Profile JSON saved", 15);

        if (profile.has("libraries")) {
            JsonArray libs = profile.getAsJsonArray("libraries");
            int total = libs.size(), idx = 0;
            for (JsonElement el : libs) {
                idx++;
                JsonObject lib = el.getAsJsonObject();
                int pct = 15 + idx * 80 / total;
                if (!lib.has("name")) continue;
                String name   = lib.get("name").getAsString();
                String base   = lib.has("url") ? lib.get("url").getAsString() : FABRIC_MAVEN;
                String relPath = mavenToPath(name);
                if (relPath == null) continue;
                File dest = new File(mcDir, "libraries/" + relPath);
                if (dest.exists() && dest.length() > 0) continue;
                dest.getParentFile().mkdirs();
                cb.onProgress("Downloading: " + dest.getName(), pct);
                String dlUrl = (base.endsWith("/") ? base : base + "/") + relPath;
                if (!tryDownload(dlUrl, dest)) {
                    tryDownload(FABRIC_MAVEN + relPath, dest);
                }
            }
        }
        cb.onProgress("Fabric " + loaderVersion + " ready!", 100);
        return fabricId;
    }

    public static String mavenToPath(String coord) {
        String[] p = coord.split(":");
        if (p.length < 3) return null;
        String group      = p[0].replace('.', '/');
        String artifact   = p[1];
        String version    = p[2];
        String classifier = p.length >= 4 ? "-" + p[3] : "";
        return group + "/" + artifact + "/" + version
            + "/" + artifact + "-" + version + classifier + ".jar";
    }

    private static boolean tryDownload(String urlStr, File dest) {
        try {
            HttpURLConnection c = open(urlStr);
            if (c.getResponseCode() != 200) return false;
            try (InputStream in = c.getInputStream();
                 FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[16384];
                int r;
                while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            }
            return true;
        } catch (Exception e) {
            System.err.println("[FabricInstaller] Failed: " + urlStr + " — " + e.getMessage());
            return false;
        }
    }

    private static JsonObject fetchObject(String url) throws Exception {
        try (InputStreamReader r = new InputStreamReader(open(url).getInputStream())) {
            return JsonParser.parseReader(r).getAsJsonObject();
        }
    }

    private static JsonArray fetchArray(String url) throws Exception {
        try (InputStreamReader r = new InputStreamReader(open(url).getInputStream())) {
            return JsonParser.parseReader(r).getAsJsonArray();
        }
    }

    private static HttpURLConnection open(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setRequestProperty("User-Agent", "OnyxLauncher/1.0");
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        return c;
    }
}
