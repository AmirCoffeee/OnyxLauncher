package me.amir;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

public class Settings {
    private static final File FILE = new File("settings.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private String       username        = "";
    private List<String> usernameHistory = new ArrayList<>();
    private String activeProfileId = "";
    private String customMcPath = "";
    private String gpuMode = "auto";
    private boolean showAdvancedVersions = false;

    public static Settings load() {
        if (FILE.exists()) {
            try (FileReader r = new FileReader(FILE)) {
                Settings s = GSON.fromJson(r, Settings.class);
                if (s != null) {
                    if (s.usernameHistory == null) s.usernameHistory = new ArrayList<>();
                    return s;
                }
            } catch (IOException ignored) {}
        }
        return new Settings();
    }

    public void save() {
        try (FileWriter w = new FileWriter(FILE)) {
            GSON.toJson(this, w);
        } catch (IOException ignored) {}
    }

    public String getUsername()                { return username; }
    public void   setUsername(String u)        { this.username = u; }
    public List<String> getUsernameHistory()   { return usernameHistory; }
    public void addUsernameToHistory(String n) {
        if (!usernameHistory.contains(n)) usernameHistory.add(n);
    }
    public String getActiveProfileId()              { return activeProfileId; }
    public void   setActiveProfileId(String id)     { this.activeProfileId = id; }
    public String getCustomMcPath()                 { return customMcPath != null ? customMcPath : ""; }
    public void   setCustomMcPath(String p)         { this.customMcPath = p; }
    public String getGpuMode()                      { return gpuMode != null ? gpuMode : "auto"; }
    public void   setGpuMode(String m)              { this.gpuMode = m; }
    public boolean isShowAdvancedVersions()         { return showAdvancedVersions; }
    public void    setShowAdvancedVersions(boolean v){ this.showAdvancedVersions = v; }

    @Deprecated public boolean isFullscreen()       { return false; }
    @Deprecated public int     getRamAllocation()   { return 2048; }
    @Deprecated public String  getJvmArgs()         { return ""; }
    @Deprecated public int     getResWidth()        { return 1280; }
    @Deprecated public int     getResHeight()       { return 720; }
    @Deprecated public String  getLastVersion()     { return ""; }
    @Deprecated public void    setLastVersion(String v) {}
    @Deprecated public void    setFullscreen(boolean f) {}
    @Deprecated public void    setRamAllocation(int r)  {}
    @Deprecated public void    setJvmArgs(String a)     {}
    @Deprecated public void    setResWidth(int w)       {}
    @Deprecated public void    setResHeight(int h)      {}
}
