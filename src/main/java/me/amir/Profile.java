package me.amir;
import java.io.File;
import java.util.UUID;

public class Profile {
    private String id;
    private String name;
    private String color;
    private String  version;
    private String  gameDir;
    private int     ramMB;
    private String  jvmArgs;
    private boolean fullscreen;
    private int     resWidth;
    private int     resHeight;
    private String modsPath;
    private String resourcePacksPath;
    private String shadersPath;
    private String javaPath;

    public static Profile createDefault(String name) {
        Profile p  = new Profile();
        p.id       = UUID.randomUUID().toString().substring(0, 8);
        p.name     = name;
        p.color    = "#4CAF50";
        p.version  = "";
        p.gameDir  = "";
        p.ramMB    = 2048;
        p.jvmArgs  = "";
        p.fullscreen = false;
        p.resWidth = 1280;
        p.resHeight= 720;
        p.modsPath           = "";
        p.resourcePacksPath  = "";
        p.shadersPath        = "";
        return p;
    }

    public String effectiveGameDir() {
        if (gameDir != null && !gameDir.trim().isEmpty()) {
            File f = new File(gameDir);
            if (f.exists() || f.mkdirs()) return f.getAbsolutePath();
        }
        return AppConfig.getMinecraftDir();
    }

    public String modsDir() {
        if (modsPath != null && !modsPath.trim().isEmpty()) return modsPath.trim();
        return effectiveGameDir() + File.separator + "mods";
    }

    public String resourcePacksDir() {
        if (resourcePacksPath != null && !resourcePacksPath.trim().isEmpty()) return resourcePacksPath.trim();
        return effectiveGameDir() + File.separator + "resourcepacks";
    }

    public String shadersDir() {
        if (shadersPath != null && !shadersPath.trim().isEmpty()) return shadersPath.trim();
        return effectiveGameDir() + File.separator + "shaderpacks";
    }

    public String getId()   { return id; }
    public String getName() { return name; }
    public void setName(String n) { this.name = n; }
    public String getColor() { return color != null ? color : "#4CAF50"; }
    public void setColor(String c) { this.color = c; }
    public String getVersion() { return version != null ? version : ""; }
    public void setVersion(String v) { this.version = v; }
    public String getGameDir() { return gameDir != null ? gameDir : ""; }
    public void setGameDir(String d) { this.gameDir = d; }
    public int getRamMB() { return ramMB > 0 ? ramMB : 2048; }
    public void setRamMB(int r) { this.ramMB = r; }
    public String getJvmArgs() { return jvmArgs != null ? jvmArgs : ""; }
    public void setJvmArgs(String a) { this.jvmArgs = a; }
    public boolean isFullscreen() { return fullscreen; }
    public void setFullscreen(boolean f) { this.fullscreen = f; }
    public int getResWidth()  { return resWidth  > 0 ? resWidth  : 1280; }
    public void setResWidth(int w)  { this.resWidth  = w; }
    public int getResHeight() { return resHeight > 0 ? resHeight : 720; }
    public void setResHeight(int h) { this.resHeight = h; }
    public String getModsPath()          { return modsPath          != null ? modsPath          : ""; }
    public void   setModsPath(String p)  { this.modsPath            = p; }
    public String getResourcePacksPath()         { return resourcePacksPath != null ? resourcePacksPath : ""; }
    public void   setResourcePacksPath(String p) { this.resourcePacksPath   = p; }
    public String getShadersPath()         { return shadersPath != null ? shadersPath : ""; }
    public void   setShadersPath(String p) { this.shadersPath   = p; }
    public String getJavaPath()            { return javaPath != null ? javaPath : ""; }
    public void   setJavaPath(String p)    { this.javaPath = p; }

    @Override public String toString() { return name; }
}
