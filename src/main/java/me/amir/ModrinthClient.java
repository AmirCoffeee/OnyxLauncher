package me.amir;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class ModrinthClient {

    private static final String BASE = "https://api.modrinth.com/v2";

    public enum ProjectType { MOD, RESOURCEPACK, SHADER, MODPACK }

    public static class ModrinthProject {
        public final String projectId;
        public final String slug;
        public final String title;
        public final String description;
        public final String iconUrl;
        public final long   downloads;
        public final String projectType;

        public ModrinthProject(String projectId, String slug, String title,
                               String description, String iconUrl,
                               long downloads, String projectType) {
            this.projectId   = projectId;
            this.slug        = slug;
            this.title       = title;
            this.description = description;
            this.iconUrl     = iconUrl;
            this.downloads   = downloads;
            this.projectType = projectType;
        }

        @Override public String toString() { return title; }
    }

    public static class ModrinthVersion {
        public final String versionId;
        public final String versionNumber;
        public final String name;
        public final String filename;
        public final String downloadUrl;
        public final long   size;

        public ModrinthVersion(String versionId, String versionNumber, String name,
                               String filename, String downloadUrl, long size) {
            this.versionId     = versionId;
            this.versionNumber = versionNumber;
            this.name          = name;
            this.filename      = filename;
            this.downloadUrl   = downloadUrl;
            this.size          = size;
        }

        @Override public String toString() { return versionNumber + " — " + name; }
    }

    public static List<ModrinthProject> searchPopular(ProjectType type, int limit) throws Exception {
        return searchPage(type, null, 0, limit);
    }

    /** Page-based search — offset & limit for pagination. */
    public static List<ModrinthProject> searchPage(ProjectType type, String mcVersion,
                                                    int offset, int limit) throws Exception {
        String modrinthType = switch (type) {
            case MOD          -> "mod";
            case RESOURCEPACK -> "resourcepack";
            case SHADER       -> "shader";
            case MODPACK      -> "modpack";
        };
        StringBuilder facets = new StringBuilder("[[\"project_type:" + modrinthType + "\"]");
        if (mcVersion != null && !mcVersion.isEmpty())
            facets.append(",[\"versions:").append(mcVersion).append("\"]");
        facets.append("]");
        String url = BASE + "/search?limit=" + Math.min(limit, 100)
            + "&offset=" + offset
            + "&index=downloads"
            + "&facets=" + enc(facets.toString());
        return parseHits(fetchObject(url));
    }

    /** Query-based search with pagination. */
    public static List<ModrinthProject> searchPage(ProjectType type, String mcVersion,
                                                    String query, int offset, int limit) throws Exception {
        String modrinthType = switch (type) {
            case MOD          -> "mod";
            case RESOURCEPACK -> "resourcepack";
            case SHADER       -> "shader";
            case MODPACK      -> "modpack";
        };
        StringBuilder facets = new StringBuilder("[[\"project_type:" + modrinthType + "\"]");
        if (mcVersion != null && !mcVersion.isEmpty())
            facets.append(",[\"versions:").append(mcVersion).append("\"]");
        facets.append("]");
        String url = BASE + "/search?query=" + enc(query)
            + "&limit=" + Math.min(limit, 100)
            + "&offset=" + offset
            + "&index=relevance"
            + "&facets=" + enc(facets.toString());
        return parseHits(fetchObject(url));
    }

    private static List<ModrinthProject> parseHits(JsonObject result) {
        List<ModrinthProject> out = new ArrayList<>();
        for (JsonElement el : result.getAsJsonArray("hits")) {
            JsonObject h = el.getAsJsonObject();
            out.add(new ModrinthProject(
                getString(h, "project_id"),
                getString(h, "slug"),
                getString(h, "title"),
                getString(h, "description"),
                getString(h, "icon_url"),
                h.has("downloads") ? h.get("downloads").getAsLong() : 0,
                getString(h, "project_type")
            ));
        }
        return out;
    }

    public static List<ModrinthProject> search(String query, ProjectType type,
                                               String mcVersion, int limit) throws Exception {
        StringBuilder url = new StringBuilder(BASE + "/search?");
        url.append("query=").append(enc(query));
        url.append("&limit=").append(Math.min(limit, 100));

        String modrinthType = switch (type) {
            case MOD          -> "mod";
            case RESOURCEPACK -> "resourcepack";
            case SHADER       -> "shader";
            case MODPACK      -> "modpack";
        };
        StringBuilder facets = new StringBuilder("[[\"project_type:" + modrinthType + "\"]");
        if (mcVersion != null && !mcVersion.isEmpty())
            facets.append(",[\"versions:").append(mcVersion).append("\"]");
        facets.append("]");
        url.append("&facets=").append(enc(facets.toString()));

        JsonObject result = fetchObject(url.toString());
        List<ModrinthProject> out = new ArrayList<>();
        for (JsonElement el : result.getAsJsonArray("hits")) {
            JsonObject h = el.getAsJsonObject();
            out.add(new ModrinthProject(
                getString(h, "project_id"),
                getString(h, "slug"),
                getString(h, "title"),
                getString(h, "description"),
                getString(h, "icon_url"),
                h.has("downloads") ? h.get("downloads").getAsLong() : 0,
                getString(h, "project_type")
            ));
        }
        return out;
    }

    public static List<ModrinthVersion> getVersions(String projectId,
                                                    String mcVersion) throws Exception {
        StringBuilder url = new StringBuilder(BASE + "/project/" + projectId + "/version?");
        if (mcVersion != null && !mcVersion.isEmpty())
            url.append("game_versions=[%22").append(mcVersion).append("%22]");

        JsonArray arr = fetchArray(url.toString());
        List<ModrinthVersion> out = new ArrayList<>();
        for (JsonElement el : arr) {
            JsonObject v = el.getAsJsonObject();
            if (!v.has("files")) continue;
            JsonArray files = v.getAsJsonArray("files");
            if (files.isEmpty()) continue;

            JsonObject primaryFile = files.get(0).getAsJsonObject();
            for (JsonElement fe : files) {
                JsonObject fobj = fe.getAsJsonObject();
                if (fobj.has("primary") && fobj.get("primary").getAsBoolean()) {
                    primaryFile = fobj; break;
                }
            }

            out.add(new ModrinthVersion(
                getString(v, "id"),
                getString(v, "version_number"),
                getString(v, "name"),
                getString(primaryFile, "filename"),
                getString(primaryFile.getAsJsonObject("url") == null
                    ? primaryFile : primaryFile, "url"),
                primaryFile.has("size") ? primaryFile.get("size").getAsLong() : 0
            ));
        }
        return out;
    }

    public interface ProgressCallback {
        void onProgress(String msg, int percent);
    }

    public static File download(ModrinthVersion version, String targetDir,
                                ProgressCallback cb) throws Exception {
        File dir = new File(targetDir);
        dir.mkdirs();
        File dest = new File(dir, version.filename);

        if (dest.exists() && dest.length() == version.size) {
            if (cb != null) cb.onProgress("Already downloaded: " + version.filename, 100);
            return dest;
        }

        if (cb != null) cb.onProgress("Downloading " + version.filename + "…", 0);

        HttpURLConnection conn = open(version.downloadUrl);
        long total = version.size > 0 ? version.size : conn.getContentLengthLong();
        long done  = 0;

        try (InputStream in  = conn.getInputStream();
             FileOutputStream fo = new FileOutputStream(dest)) {
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) > 0) {
                fo.write(buf, 0, r);
                done += r;
                if (cb != null && total > 0) {
                    cb.onProgress(version.filename, (int)(done * 100 / total));
                }
            }
        }
        if (cb != null) cb.onProgress("Done: " + version.filename, 100);
        return dest;
    }

    private static JsonObject fetchObject(String urlStr) throws Exception {
        try (InputStreamReader r = new InputStreamReader(open(urlStr).getInputStream())) {
            return JsonParser.parseReader(r).getAsJsonObject();
        }
    }

    private static JsonArray fetchArray(String urlStr) throws Exception {
        try (InputStreamReader r = new InputStreamReader(open(urlStr).getInputStream())) {
            return JsonParser.parseReader(r).getAsJsonArray();
        }
    }

    private static HttpURLConnection open(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setRequestProperty("User-Agent", "OnyxLauncher/1.0 (github.com/amir)");
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        return c;
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String getString(JsonObject o, String key) {
        return (o != null && o.has(key) && !o.get(key).isJsonNull())
            ? o.get(key).getAsString() : "";
    }
}
