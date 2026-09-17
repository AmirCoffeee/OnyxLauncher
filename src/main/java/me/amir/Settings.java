package me.amir;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Global launcher settings (not per-profile).
 * Per-profile settings (version, gameDir, RAM, JVM args) live in Profile.
 */
public class Settings {

    private static final File FILE = new File("settings.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ── Account ───────────────────────────────────────────────────────────────
    private String       username        = "";
    private List<String> usernameHistory = new ArrayList<>();

    // ── Active profile ────────────────────────────────────────────────────────
    private String activeProfileId = "";

    // ── Global .minecraft override (used when profile has no custom gameDir) ──
    private String customMcPath = "";

    // ── GPU mode (global – applies to every launch) ───────────────────────────
    // "auto"     → pick first discrete GPU if available
    // "dgpu|..." → force that specific discrete GPU
    // "igpu|..." → force that specific integrated GPU
    private String gpuMode = "auto";

    // ─────────────────────────────────────────────────────────────────────────
    // Persistence
    // ─────────────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────────────
    // Accessors
    // ─────────────────────────────────────────────────────────────────────────

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

    // ── Back-compat stubs (so AppConfig still compiles unchanged) ─────────────
    /** @deprecated Use Profile.effectiveGameDir() */
    @Deprecated public boolean isFullscreen()       { return false; }
    /** @deprecated Use Profile.getRamAllocation() */
    @Deprecated public int     getRamAllocation()   { return 2048; }
    /** @deprecated Use Profile.getJvmArgs() */
    @Deprecated public String  getJvmArgs()         { return ""; }
    /** @deprecated Use Profile.getResWidth() */
    @Deprecated public int     getResWidth()        { return 1280; }
    /** @deprecated Use Profile.getResHeight() */
    @Deprecated public int     getResHeight()       { return 720; }
    /** @deprecated kept so old callers don't break at compile time */
    @Deprecated public String  getLastVersion()     { return ""; }
    @Deprecated public void    setLastVersion(String v) {}
    @Deprecated public void    setFullscreen(boolean f) {}
    @Deprecated public void    setRamAllocation(int r)  {}
    @Deprecated public void    setJvmArgs(String a)     {}
    @Deprecated public void    setResWidth(int w)       {}
    @Deprecated public void    setResHeight(int h)      {}
}
