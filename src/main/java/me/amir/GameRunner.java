package me.amir;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class GameRunner {

    // -------------------------------------------------------------------------
    // Public entry points
    // -------------------------------------------------------------------------

    public static Process launchGame(String username, Profile profile) throws Exception {
        String version = profile.getVersion();
        if (version == null || version.isBlank())
            throw new IllegalArgumentException(
                "No Minecraft version set in profile '" + profile.getName() + "'");

        String mcDir  = profile.effectiveGameDir();
        int    ramMB  = profile.getRamMB();
        String extra  = profile.getJvmArgs();

        String javaPath;
        if (!profile.getJavaPath().isEmpty()) {
            javaPath = profile.getJavaPath();
        } else {
            int javaVer = getRequiredJavaVersion(mcDir, AppConfig.getMinecraftDir(), version);
            javaPath = getJavaPath(javaVer);
        }

        return launch(username, version, ramMB, extra, mcDir,
                profile.isFullscreen(), profile.getResWidth(), profile.getResHeight(),
                javaPath);
    }

    public static Process launchGame(String username, String version,
                                     int ramMB, String extraArgs) throws Exception {
        String mcDir    = AppConfig.getMinecraftDir();
        int    javaVer  = getRequiredJavaVersion(mcDir, mcDir, version);
        String javaPath = getJavaPath(javaVer);
        Settings s      = Settings.load();
        return launch(username, version, ramMB, extraArgs, mcDir,
                s.isFullscreen(), s.getResWidth(), s.getResHeight(), javaPath);
    }

    // -------------------------------------------------------------------------
    // Core launch
    // -------------------------------------------------------------------------

    private static Process launch(String username, String version, int ramMB,
                                  String extraArgs, String mcDir,
                                  boolean fullscreen, int resW, int resH,
                                  String javaPath) throws Exception {

        String globalDir = AppConfig.getMinecraftDir();

        // Search list: profile dir first, then global .minecraft
        List<String> dirs = new ArrayList<>();
        dirs.add(mcDir);
        if (!mcDir.equals(globalDir)) dirs.add(globalDir);

        String verDir    = mcDir + "/versions/" + version;
        String mainClass = getMainClass(dirs, version);
        if (mainClass == null) mainClass = "net.minecraft.client.main.Main";
        String assetIdx  = getAssetIndex(dirs, version);
        if (assetIdx  == null) assetIdx  = "1.8";

        System.out.println("[GameRunner] version=" + version
            + "  java=" + javaPath + "  gameDir=" + mcDir);

        // Java version check
        try {
            Process vp = new ProcessBuilder(javaPath, "-version")
                .redirectErrorStream(true).start();
            String vl = new BufferedReader(
                new InputStreamReader(vp.getInputStream())).readLine();
            if (vl != null) System.out.println("[GameRunner] " + vl);
        } catch (Exception ignored) {}

        // Classpath
        List<String> libs = new ArrayList<>();
        for (String dir : dirs) collectLibraries(dir, dirs, version, libs);
        for (String dir : dirs) {
            File vd = new File(dir, "versions/" + version);
            File[] jars = vd.listFiles(f -> f.getName().endsWith(".jar"));
            if (jars != null)
                for (File j : jars)
                    if (!libs.contains(j.getAbsolutePath())) libs.add(j.getAbsolutePath());
        }

        if (libs.isEmpty())
            throw new IllegalStateException(
                "Classpath is empty for '" + version + "'.\nSearched: " + dirs);

        System.out.println("[GameRunner] classpath entries: " + libs.size());
        String cp = String.join(File.pathSeparator, libs);

        // Natives
        String nativesDir = verDir + "/natives";
        for (String dir : dirs) extractNatives(dir, nativesDir);

        // Assets: prefer profile dir, fallback to global
        String assetsDir = mcDir + "/assets";
        if (!new File(assetsDir, "indexes").isDirectory())
            assetsDir = globalDir + "/assets";

        String os       = System.getProperty("os.name").toLowerCase();
        boolean isLinux = os.contains("nix") || os.contains("nux");
        if (isLinux) patchOptions(mcDir);

        // Build command
        List<String> cmd = new ArrayList<>();
        cmd.add(javaPath);
        cmd.add("-Xms512M");
        cmd.add("-Xmx" + ramMB + "M");
        cmd.addAll(Arrays.asList(
            "-XX:+UseG1GC", "-XX:+ParallelRefProcEnabled",
            "-XX:MaxGCPauseMillis=200", "-XX:+UnlockExperimentalVMOptions",
            "-XX:+DisableExplicitGC", "-XX:G1NewSizePercent=30",
            "-XX:G1MaxNewSizePercent=40", "-XX:G1HeapRegionSize=8M",
            "-XX:G1ReservePercent=20", "-XX:G1HeapWastePercent=5",
            "-XX:G1MixedGCCountTarget=4", "-XX:InitiatingHeapOccupancyPercent=15",
            "-XX:G1MixedGCLiveThresholdPercent=90",
            "-XX:G1RSetUpdatingPauseTimePercent=5",
            "-XX:SurvivorRatio=32", "-XX:+PerfDisableSharedMem",
            "-XX:MaxTenuringThreshold=1"
        ));

        if (extraArgs != null && !extraArgs.isBlank())
            Collections.addAll(cmd, extraArgs.split("\\s+"));

        cmd.add("-Djava.library.path=" + nativesDir);
        cmd.add("-Dfile.encoding=UTF-8");
        if (isLinux) {
            cmd.add("-Dorg.lwjgl.util.Debug=false");
            cmd.add("-Dorg.lwjgl.system.SharedLibraryExtractPath=" + nativesDir);
        }
        cmd.add("-cp");
        cmd.add(cp);
        cmd.add(mainClass);

        cmd.addAll(Arrays.asList(
            "--username",       username,
            "--version",        version,
            "--gameDir",        mcDir,
            "--assetsDir",      assetsDir,
            "--assetIndex",     assetIdx,
            "--uuid",           "00000000-0000-0000-0000-000000000000",
            "--accessToken",    "0",
            "--userType",       "legacy",
            "--userProperties", "{}"
        ));

        if (fullscreen) {
            if (isLinux) {
                java.awt.Dimension screen = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
                cmd.add("--width");  cmd.add(String.valueOf(screen.width));
                cmd.add("--height"); cmd.add(String.valueOf(screen.height));
            } else {
                cmd.add("--fullscreen");
            }
        } else {
            cmd.add("--width");  cmd.add(String.valueOf(resW));
            cmd.add("--height"); cmd.add(String.valueOf(resH));
        }

        System.out.println("[GameRunner] " + String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new File(mcDir));
        pb.redirectErrorStream(true);
        if (isLinux) {
            pb.environment().put("_JAVA_AWT_WM_NONREPARENTING", "1");
            pb.environment().put("GDK_BACKEND", "x11");
            pb.environment().put("XLIB_SKIP_ARGB_VISUALS", "1");
        }
        return pb.start();
    }

    // -------------------------------------------------------------------------
    // Java discovery
    // -------------------------------------------------------------------------

    public static int getRequiredJavaVersion(String mcDir, String globalDir, String version) {
        List<String> dirs = new ArrayList<>();
        dirs.add(mcDir);
        if (!mcDir.equals(globalDir)) dirs.add(globalDir);
        try {
            File f = findJson(dirs, version); if (f == null) return 8;
            JsonObject o = JsonParser.parseReader(new FileReader(f)).getAsJsonObject();
            if (o.has("javaVersion"))
                return o.getAsJsonObject("javaVersion").get("majorVersion").getAsInt();
            if (o.has("inheritsFrom"))
                return getRequiredJavaVersion(mcDir, globalDir, o.get("inheritsFrom").getAsString());
        } catch (Exception ignored) {}
        return 8;
    }

    public static String getJavaPath(int required) {
        return System.getProperty("os.name").toLowerCase().contains("win")
            ? findJavaWindows(required) : findJavaUnix(required);
    }

    private static String findJavaUnix(int required) {
        File root = new File("/usr/lib/jvm");
        if (root.isDirectory()) {
            File[] dirs = root.listFiles();
            if (dirs != null) {
                Arrays.sort(dirs, Comparator.comparing(File::getName).reversed());
                for (File d : dirs) {
                    if (matchesVersion(d.getName(), required)) {
                        File j = new File(d, "bin/java");
                        if (j.canExecute()) return j.getAbsolutePath();
                    }
                }
                for (File d : dirs) {
                    if (extractVer(d.getName()) >= required) {
                        File j = new File(d, "bin/java");
                        if (j.canExecute()) return j.getAbsolutePath();
                    }
                }
            }
        }
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"update-alternatives","--list","java"});
            List<String> cands = new ArrayList<>();
            new BufferedReader(new InputStreamReader(p.getInputStream()))
                .lines().map(String::trim).forEach(cands::add);
            for (String c : cands) if (c.contains("java-"+required)) return c;
            cands.sort((a,b)->extractVer(b)-extractVer(a));
            if (!cands.isEmpty()) return cands.get(0);
        } catch (Exception ignored) {}
        String jh = System.getenv("JAVA_HOME");
        if (jh != null) { File j = new File(jh,"bin/java"); if (j.canExecute()) return j.getAbsolutePath(); }
        return "java";
    }

    private static String findJavaWindows(int required) {
        for (String base : new String[]{
            System.getenv("ProgramFiles")+"\\Eclipse Adoptium",
            System.getenv("ProgramFiles")+"\\Java",
            System.getenv("ProgramFiles")+"\\Microsoft",
            System.getenv("ProgramFiles")+"\\BellSoft"}) {
            File d = new File(base); if (!d.isDirectory()) continue;
            File[] subs = d.listFiles(); if (subs==null) continue;
            Arrays.sort(subs, Comparator.comparing(File::getName).reversed());
            for (File s : subs) {
                if (extractVer(s.getName()) >= required) {
                    File j = new File(s,"bin\\java.exe"); if (j.exists()) return j.getAbsolutePath();
                }
            }
        }
        return "java";
    }

    private static boolean matchesVersion(String name, int req) {
        if (req==8) return name.contains("java-8")||name.contains("java-1.8")||name.contains("jdk-8")||name.contains("jdk8");
        return name.contains("java-"+req)||name.contains("jdk-"+req)||name.contains("temurin-"+req)||name.contains("zulu-"+req);
    }

    private static int extractVer(String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("(?:java|jdk|jre|temurin|zulu)[\\-_]?(\\d+)").matcher(name.toLowerCase());
        if (m.find()) try { return Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
        return 0;
    }

    // -------------------------------------------------------------------------
    // JSON helpers – all multi-dir aware
    // -------------------------------------------------------------------------

    private static File findJson(List<String> dirs, String version) {
        for (String d : dirs) {
            File f = new File(d, "versions/"+version+"/"+version+".json");
            if (f.exists()) return f;
        }
        return null;
    }

    private static String getMainClass(List<String> dirs, String version) {
        try {
            File f = findJson(dirs, version); if (f==null) return null;
            JsonObject o = JsonParser.parseReader(new FileReader(f)).getAsJsonObject();
            if (o.has("mainClass")) return o.get("mainClass").getAsString();
            if (o.has("inheritsFrom")) return getMainClass(dirs, o.get("inheritsFrom").getAsString());
        } catch (Exception ignored) {}
        return null;
    }

    private static String getAssetIndex(List<String> dirs, String version) {
        try {
            File f = findJson(dirs, version); if (f==null) return null;
            JsonObject o = JsonParser.parseReader(new FileReader(f)).getAsJsonObject();
            if (o.has("assetIndex") && o.get("assetIndex").isJsonObject())
                return o.getAsJsonObject("assetIndex").get("id").getAsString();
            if (o.has("assets")) return o.get("assets").getAsString();
            if (o.has("inheritsFrom")) return getAssetIndex(dirs, o.get("inheritsFrom").getAsString());
        } catch (Exception ignored) {}
        return null;
    }

    /** Collect libraries, searching all dirs for each file. */
    private static void collectLibraries(String jsonDir, List<String> allDirs,
                                         String version, List<String> out) {
        try {
            File f = new File(jsonDir, "versions/"+version+"/"+version+".json");
            if (!f.exists()) return;
            JsonObject o = JsonParser.parseReader(new FileReader(f)).getAsJsonObject();

            if (o.has("libraries")) {
                for (JsonElement el : o.getAsJsonArray("libraries")) {
                    JsonObject lib = el.getAsJsonObject();
                    if (!lib.has("name")) continue;
                    if (lib.has("rules") && !isAllowed(lib)) continue;

                    if (lib.has("downloads")) {
                        JsonObject dl = lib.getAsJsonObject("downloads");
                        if (dl.has("artifact")) {
                            JsonObject art = dl.getAsJsonObject("artifact");
                            if (art.has("path")) {
                                String rel = art.get("path").getAsString();
                                for (String dir : allDirs) {
                                    File lf = new File(dir, "libraries/" + rel);
                                    if (lf.exists() && !out.contains(lf.getAbsolutePath())) {
                                        out.add(lf.getAbsolutePath()); break;
                                    }
                                }
                                continue;
                            }
                        }
                    }
                    // Maven coordinate fallback
                    String[] p = lib.get("name").getAsString().split(":");
                    if (p.length >= 3) {
                        String rel = p[0].replace('.',File.separatorChar)
                            +File.separator+p[1]+File.separator+p[2]
                            +File.separator+p[1]+"-"+p[2]+".jar";
                        for (String dir : allDirs) {
                            File lf = new File(dir, "libraries/" + rel);
                            if (lf.exists() && !out.contains(lf.getAbsolutePath())) {
                                out.add(lf.getAbsolutePath()); break;
                            }
                        }
                    }
                }
            }

            if (o.has("inheritsFrom")) {
                String parent = o.get("inheritsFrom").getAsString();
                // recurse into all dirs for the parent JSON
                for (String dir : allDirs) collectLibraries(dir, allDirs, parent, out);
                // parent JAR
                for (String dir : allDirs) {
                    File pj = new File(dir, "versions/"+parent+"/"+parent+".jar");
                    if (pj.exists() && !out.contains(pj.getAbsolutePath())) {
                        out.add(pj.getAbsolutePath()); break;
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private static boolean isAllowed(JsonObject lib) {
        String os = System.getProperty("os.name").toLowerCase();
        String osName = os.contains("win")?"windows":os.contains("mac")?"osx":"linux";
        boolean allowed = false;
        for (JsonElement r : lib.getAsJsonArray("rules")) {
            JsonObject rule = r.getAsJsonObject();
            String action = rule.get("action").getAsString();
            if (rule.has("os")) {
                if (rule.getAsJsonObject("os").get("name").getAsString().equals(osName))
                    allowed = action.equals("allow");
            } else allowed = action.equals("allow");
        }
        return allowed;
    }

    // -------------------------------------------------------------------------
    // Natives
    // -------------------------------------------------------------------------

    private static void extractNatives(String mcDir, String nativesDir) {
        File nd = new File(nativesDir); nd.mkdirs();
        String os = System.getProperty("os.name").toLowerCase();
        List<File> jars = new ArrayList<>();
        findNativeJars(new File(mcDir, "libraries"), os, jars);
        for (File jar : jars) {
            try (ZipFile z = new ZipFile(jar)) {
                Enumeration<? extends ZipEntry> en = z.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement(); String n = e.getName();
                    boolean ex = !e.isDirectory() && (
                        (os.contains("win") && n.endsWith(".dll")) ||
                        ((os.contains("nix")||os.contains("nux")) && n.endsWith(".so")) ||
                        (os.contains("mac") && (n.endsWith(".dylib")||n.endsWith(".jnilib"))));
                    if (!ex) continue;
                    File out = new File(nd, new File(n).getName());
                    try (InputStream in=z.getInputStream(e); FileOutputStream fo=new FileOutputStream(out)) {
                        byte[] buf=new byte[8192]; int r; while((r=in.read(buf))>0) fo.write(buf,0,r);
                    }
                    if (!os.contains("win")) { out.setExecutable(true,false); out.setReadable(true,false); }
                }
            } catch (Exception ignored) {}
        }
    }

    private static void findNativeJars(File dir, String os, List<File> res) {
        File[] files = dir.listFiles(); if (files==null) return;
        for (File f:files) {
            if (f.isDirectory()) { findNativeJars(f,os,res); continue; }
            String n = f.getName().toLowerCase(); if (!n.endsWith(".jar")) continue;
            if ((os.contains("win")&&n.contains("natives-windows"))||
                ((os.contains("nix")||os.contains("nux"))&&n.contains("natives-linux"))||
                (os.contains("mac")&&(n.contains("natives-macos")||n.contains("natives-osx")))) res.add(f);
        }
    }

    private static void patchOptions(String mcDir) {
        try {
            File opt = new File(mcDir,"options.txt"); List<String> lines=new ArrayList<>(); boolean has=false;
            if (opt.exists()) { try(BufferedReader r=new BufferedReader(new FileReader(opt))){String l;
                while((l=r.readLine())!=null){if(l.startsWith("pauseOnLostFocus:")){lines.add("pauseOnLostFocus:false");has=true;}else lines.add(l);}} }
            if (!has) lines.add("pauseOnLostFocus:false");
            try(FileWriter w=new FileWriter(opt)){for(String l:lines)w.write(l+"\n");}
        } catch (Exception ignored) {}
    }
}
