package me.amir;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class GameRunner {

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
                javaPath, Settings.load().getGpuMode());
    }

    public static Process launchGame(String username, String version,
                                     int ramMB, String extraArgs) throws Exception {
        String mcDir    = AppConfig.getMinecraftDir();
        int    javaVer  = getRequiredJavaVersion(mcDir, mcDir, version);
        String javaPath = getJavaPath(javaVer);
        Settings s      = Settings.load();
        return launch(username, version, ramMB, extraArgs, mcDir,
                s.isFullscreen(), s.getResWidth(), s.getResHeight(), javaPath, "auto");
    }

    private static Process launch(String username, String version, int ramMB,
                                  String extraArgs, String mcDir,
                                  boolean fullscreen, int resW, int resH,
                                  String javaPath, String gpuMode) throws Exception {

        String globalDir = AppConfig.getMinecraftDir();

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

        try {
            Process vp = new ProcessBuilder(javaPath, "-version")
                    .redirectErrorStream(true).start();
            String vl = new BufferedReader(
                    new InputStreamReader(vp.getInputStream())).readLine();
            if (vl != null) System.out.println("[GameRunner] " + vl);
        } catch (Exception ignored) {}

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

        String nativesDir = verDir + "/natives";
        for (String dir : dirs) extractNatives(dir, nativesDir);

        String assetsDir = mcDir + "/assets";
        if (!new File(assetsDir, "indexes").isDirectory())
            assetsDir = globalDir + "/assets";

        String os       = System.getProperty("os.name").toLowerCase();
        boolean isLinux = os.contains("nix") || os.contains("nux");
        boolean borderlessFs = fullscreen && isLinux;
        if (isLinux) patchOptions(mcDir, borderlessFs);

        int width = resW;
        int height = resH;
        if (borderlessFs) {
            int[] sz = screenSize();
            width = sz[0];
            height = sz[1];
        }

        List<JsonObject> chain = loadChain(dirs, version);

        Set<String> features = new HashSet<>();
        features.add("has_custom_resolution");

        Map<String, String> vars = new HashMap<>();
        vars.put("auth_player_name", username);
        vars.put("version_name", version);
        vars.put("game_directory", mcDir);
        vars.put("assets_root", assetsDir);
        vars.put("game_assets", assetsDir);
        vars.put("assets_index_name", assetIdx);
        vars.put("auth_uuid", "00000000-0000-0000-0000-000000000000");
        vars.put("auth_access_token", "0");
        vars.put("auth_session", "0");
        vars.put("clientid", "0");
        vars.put("auth_xuid", "0");
        vars.put("user_type", "legacy");
        vars.put("user_properties", "{}");
        vars.put("version_type", "release");
        vars.put("launcher_name", "GameRunner");
        vars.put("launcher_version", "1.0");
        vars.put("resolution_width", String.valueOf(width));
        vars.put("resolution_height", String.valueOf(height));
        vars.put("natives_directory", nativesDir);
        vars.put("classpath", cp);

        List<String> jsonJvm = resolveJvmArgs(chain, features, vars);
        List<String> gameArgs = resolveGameArgs(chain, features, vars);

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
        if (borderlessFs) {
            cmd.add("-Dorg.lwjgl.opengl.Window.undecorated=true");
        }
        cmd.addAll(jsonJvm);
        cmd.add("-cp");
        cmd.add(cp);
        cmd.add(mainClass);
        cmd.addAll(gameArgs);

        if (!gameArgs.contains("--width")) {
            cmd.add("--width");  cmd.add(String.valueOf(width));
            cmd.add("--height"); cmd.add(String.valueOf(height));
        }
        if (fullscreen && !isLinux && !gameArgs.contains("--fullscreen")) {
            cmd.add("--fullscreen");
        }

        System.out.println("[GameRunner] " + String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new File(mcDir));
        pb.redirectErrorStream(true);
        if (isLinux) {
            pb.environment().put("_JAVA_AWT_WM_NONREPARENTING", "1");
            pb.environment().put("GDK_BACKEND", "x11");
            pb.environment().put("XLIB_SKIP_ARGB_VISUALS", "1");

            String gm = (gpuMode == null) ? "auto" : gpuMode.toLowerCase();
            boolean forceDgpu = gm.startsWith("dgpu")
                    || (!gm.startsWith("igpu") && !gm.equals("auto")
                    && (gm.contains("nvidia") || gm.contains("rtx") || gm.contains("gtx")
                    || (gm.contains("radeon") && !gm.contains("intel"))));
            boolean forceIgpu = gm.startsWith("igpu")
                    || (!forceDgpu && (gm.contains("intel") || gm.contains("integrated")));

            if (forceIgpu) {
                pb.environment().put("DRI_PRIME", "0");
                pb.environment().remove("__NV_PRIME_RENDER_OFFLOAD");
                pb.environment().remove("__NV_PRIME_RENDER_OFFLOAD_PROVIDER");
                pb.environment().remove("__GLX_VENDOR_LIBRARY_NAME");
                pb.environment().remove("__VK_LAYER_NV_optimus");
                System.out.println("[GameRunner] GPU mode: iGPU (Integrated)");
            } else {
                pb.environment().put("__NV_PRIME_RENDER_OFFLOAD", "1");
                pb.environment().put("__NV_PRIME_RENDER_OFFLOAD_PROVIDER", "NVIDIA-G0");
                pb.environment().put("__GLX_VENDOR_LIBRARY_NAME", "nvidia");
                pb.environment().put("__VK_LAYER_NV_optimus", "NVIDIA_only");
                pb.environment().put("DRI_PRIME", "1");
                System.out.println("[GameRunner] GPU mode: dGPU (NVIDIA PRIME offload)  [" + gpuMode + "]");
            }
        }
        return pb.start();
    }

    private static int[] screenSize() {
        try {
            java.awt.DisplayMode dm = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDisplayMode();
            return new int[]{dm.getWidth(), dm.getHeight()};
        } catch (Throwable ignored) {}
        try {
            java.awt.Dimension d = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
            return new int[]{d.width, d.height};
        } catch (Throwable ignored) {}
        return new int[]{1920, 1080};
    }

    private static List<JsonObject> loadChain(List<String> dirs, String version) {
        LinkedList<JsonObject> chain = new LinkedList<>();
        Set<String> seen = new HashSet<>();
        String cur = version;
        while (cur != null && seen.add(cur)) {
            File f = findJson(dirs, cur);
            if (f == null) break;
            try (Reader r = new FileReader(f)) {
                JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
                chain.addFirst(o);
                cur = o.has("inheritsFrom") ? o.get("inheritsFrom").getAsString() : null;
            } catch (Exception e) {
                System.out.println("[GameRunner] Failed to parse " + f + ": " + e.getMessage());
                break;
            }
        }
        return chain;
    }

    private static List<String> resolveGameArgs(List<JsonObject> chain,
                                                Set<String> features,
                                                Map<String, String> vars) {
        List<String> raw = new ArrayList<>();
        for (JsonObject o : chain) {
            if (o.has("arguments") && o.getAsJsonObject("arguments").has("game")) {
                raw.addAll(parseArgArray(o.getAsJsonObject("arguments").getAsJsonArray("game"), features));
            } else if (o.has("minecraftArguments")) {
                raw = new ArrayList<>(Arrays.asList(
                        o.get("minecraftArguments").getAsString().trim().split("\\s+")));
            }
        }
        return substitute(raw, vars);
    }

    private static List<String> resolveJvmArgs(List<JsonObject> chain,
                                               Set<String> features,
                                               Map<String, String> vars) {
        List<String> raw = new ArrayList<>();
        for (JsonObject o : chain) {
            if (o.has("arguments") && o.getAsJsonObject("arguments").has("jvm"))
                raw.addAll(parseArgArray(o.getAsJsonObject("arguments").getAsJsonArray("jvm"), features));
        }
        List<String> subbed = substitute(raw, vars);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < subbed.size(); i++) {
            String a = subbed.get(i);
            if (a.equals("-cp") || a.equals("-classpath")) { i++; continue; }
            if (a.startsWith("-Djava.library.path")) continue;
            out.add(a);
        }
        return out;
    }

    private static List<String> parseArgArray(JsonArray arr, Set<String> features) {
        List<String> out = new ArrayList<>();
        for (JsonElement el : arr) {
            if (el.isJsonPrimitive()) {
                out.add(el.getAsString());
            } else if (el.isJsonObject()) {
                JsonObject o = el.getAsJsonObject();
                if (o.has("rules") && !rulesAllow(o.getAsJsonArray("rules"), features)) continue;
                if (!o.has("value")) continue;
                JsonElement v = o.get("value");
                if (v.isJsonArray()) {
                    for (JsonElement x : v.getAsJsonArray()) out.add(x.getAsString());
                } else {
                    out.add(v.getAsString());
                }
            }
        }
        return out;
    }

    private static List<String> substitute(List<String> raw, Map<String, String> vars) {
        List<String> out = new ArrayList<>();
        for (String a : raw) {
            String s = a;
            for (Map.Entry<String, String> e : vars.entrySet())
                s = s.replace("${" + e.getKey() + "}", e.getValue());
            if (s.contains("${")) {
                if (!out.isEmpty() && out.get(out.size() - 1).startsWith("--"))
                    out.remove(out.size() - 1);
                continue;
            }
            out.add(s);
        }
        return out;
    }

    private static boolean rulesAllow(JsonArray rules, Set<String> features) {
        if (rules == null || rules.size() == 0) return true;
        String os = System.getProperty("os.name").toLowerCase();
        String osName = os.contains("win") ? "windows" : os.contains("mac") ? "osx" : "linux";
        boolean allowed = false;
        for (JsonElement r : rules) {
            JsonObject rule = r.getAsJsonObject();
            boolean matches = true;
            if (rule.has("os")) {
                JsonObject o = rule.getAsJsonObject("os");
                if (o.has("name") && !o.get("name").getAsString().equals(osName)) matches = false;
            }
            if (rule.has("features")) {
                for (Map.Entry<String, JsonElement> e : rule.getAsJsonObject("features").entrySet()) {
                    boolean want = e.getValue().getAsBoolean();
                    if (features.contains(e.getKey()) != want) matches = false;
                }
            }
            if (matches) allowed = rule.get("action").getAsString().equals("allow");
        }
        return allowed;
    }

    public static List<String> detectGpus() {
        List<String> gpus = new ArrayList<>();
        try {
            Process p = new ProcessBuilder("lspci").redirectErrorStream(true).start();
            new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))
                    .lines().forEach(line -> {
                        String l = line.toLowerCase();
                        if (!l.contains("vga") && !l.contains("3d controller") && !l.contains("display controller")) return;
                        String desc = line.contains(":") ? line.substring(line.indexOf(':') + 1).trim() : line;
                        if (desc.contains(": ")) desc = desc.substring(desc.indexOf(": ") + 2).trim();
                        String lower = desc.toLowerCase();
                        if (lower.contains("nvidia")) {
                            gpus.add("dgpu|" + desc);
                        } else if (lower.contains("intel") || lower.contains("integrated") || lower.contains("uhd") || lower.contains("iris")) {
                            gpus.add("igpu|" + desc);
                        } else if (lower.contains("amd") || lower.contains("radeon")) {
                            gpus.add("dgpu|" + desc);
                        } else {
                            gpus.add("igpu|" + desc);
                        }
                    });
        } catch (Exception ignored) {}

        if (gpus.isEmpty()) {
            File drm = new File("/sys/class/drm");
            if (drm.isDirectory()) {
                File[] cards = drm.listFiles(f -> f.getName().matches("card\\d+"));
                if (cards != null) {
                    for (File card : cards) {
                        File label = new File(card, "device/product_name");
                        if (!label.exists()) label = new File(card, "device/label");
                        if (label.exists()) {
                            try {
                                String name = new String(java.nio.file.Files.readAllBytes(label.toPath())).trim();
                                String lower = name.toLowerCase();
                                if (lower.contains("nvidia")) gpus.add("dgpu|" + name);
                                else                          gpus.add("igpu|" + name);
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
        }

        boolean hasIgpu = gpus.stream().anyMatch(g -> g.startsWith("igpu|"));
        boolean hasDgpu = gpus.stream().anyMatch(g -> g.startsWith("dgpu|"));
        if (!hasIgpu) gpus.add(0, "igpu|Integrated GPU (Intel/AMD)");
        if (!hasDgpu) gpus.add("dgpu|Discrete GPU (NVIDIA/AMD)");

        return gpus;
    }

    public static int getRequiredJavaVersion(String mcDir, String globalDir, String version) {
        List<String> dirs = new ArrayList<>();
        dirs.add(mcDir);
        if (!mcDir.equals(globalDir)) dirs.add(globalDir);
        try {
            File f = findJson(dirs, version); if (f == null) return 8;
            JsonObject o;
            try (Reader r = new FileReader(f)) {
                o = JsonParser.parseReader(r).getAsJsonObject();
            }
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
            JsonObject o;
            try (Reader r = new FileReader(f)) {
                o = JsonParser.parseReader(r).getAsJsonObject();
            }
            if (o.has("mainClass")) return o.get("mainClass").getAsString();
            if (o.has("inheritsFrom")) return getMainClass(dirs, o.get("inheritsFrom").getAsString());
        } catch (Exception ignored) {}
        return null;
    }

    private static String getAssetIndex(List<String> dirs, String version) {
        try {
            File f = findJson(dirs, version); if (f==null) return null;
            JsonObject o;
            try (Reader r = new FileReader(f)) {
                o = JsonParser.parseReader(r).getAsJsonObject();
            }
            if (o.has("assetIndex") && o.get("assetIndex").isJsonObject())
                return o.getAsJsonObject("assetIndex").get("id").getAsString();
            if (o.has("assets")) return o.get("assets").getAsString();
            if (o.has("inheritsFrom")) return getAssetIndex(dirs, o.get("inheritsFrom").getAsString());
        } catch (Exception ignored) {}
        return null;
    }

    private static void collectLibraries(String jsonDir, List<String> allDirs,
                                         String version, List<String> out) {
        try {
            File f = new File(jsonDir, "versions/"+version+"/"+version+".json");
            if (!f.exists()) return;
            JsonObject o;
            try (Reader r = new FileReader(f)) {
                o = JsonParser.parseReader(r).getAsJsonObject();
            }

            if (o.has("libraries")) {
                for (JsonElement el : o.getAsJsonArray("libraries")) {
                    JsonObject lib = el.getAsJsonObject();
                    if (!lib.has("name")) continue;
                    if (lib.has("rules") && !rulesAllow(lib.getAsJsonArray("rules"), Collections.emptySet())) continue;

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
                    String[] p = lib.get("name").getAsString().split(":");
                    if (p.length >= 3) {
                        String suffix = p.length >= 4 ? "-" + p[3] : "";
                        String rel = p[0].replace('.',File.separatorChar)
                                +File.separator+p[1]+File.separator+p[2]
                                +File.separator+p[1]+"-"+p[2]+suffix+".jar";
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
                for (String dir : allDirs) collectLibraries(dir, allDirs, parent, out);
                for (String dir : allDirs) {
                    File pj = new File(dir, "versions/"+parent+"/"+parent+".jar");
                    if (pj.exists() && !out.contains(pj.getAbsolutePath())) {
                        out.add(pj.getAbsolutePath()); break;
                    }
                }
            }
        } catch (Exception ignored) {}
    }

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

    private static void patchOptions(String mcDir, boolean forceWindowed) {
        try {
            File opt = new File(mcDir, "options.txt");
            List<String> lines = new ArrayList<>();
            boolean hasPause = false;
            boolean hasFs = false;
            if (opt.exists()) {
                try (BufferedReader r = new BufferedReader(new FileReader(opt))) {
                    String l;
                    while ((l = r.readLine()) != null) {
                        if (l.startsWith("pauseOnLostFocus:")) {
                            lines.add("pauseOnLostFocus:false");
                            hasPause = true;
                        } else if (forceWindowed && l.startsWith("fullscreen:")) {
                            lines.add("fullscreen:false");
                            hasFs = true;
                        } else {
                            lines.add(l);
                        }
                    }
                }
            }
            if (!hasPause) lines.add("pauseOnLostFocus:false");
            if (forceWindowed && !hasFs) lines.add("fullscreen:false");
            try (FileWriter w = new FileWriter(opt)) {
                for (String l : lines) w.write(l + "\n");
            }
        } catch (Exception ignored) {}
    }
}