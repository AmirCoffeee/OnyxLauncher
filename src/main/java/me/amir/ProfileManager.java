package me.amir;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads and saves all profiles from/to profiles.json next to settings.json.
 */
public class ProfileManager {

    private static final File FILE = new File("profiles.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<Profile>>() {}.getType();

    private List<Profile> profiles = new ArrayList<>();

    // ── Singleton-ish: one instance per app run ──────────────────────────────
    private static ProfileManager instance;

    public static ProfileManager get() {
        if (instance == null) instance = load();
        return instance;
    }

    // ─────────────────────────────────────────────────────────────────────────

    public static ProfileManager load() {
        ProfileManager pm = new ProfileManager();
        if (FILE.exists()) {
            try (FileReader r = new FileReader(FILE)) {
                List<Profile> list = GSON.fromJson(r, LIST_TYPE);
                if (list != null) pm.profiles = list;
            } catch (Exception ignored) {}
        }
        // Always ensure at least one "Default" profile exists
        if (pm.profiles.isEmpty()) {
            pm.profiles.add(Profile.createDefault("Default"));
        }
        return pm;
    }

    public void save() {
        try (FileWriter w = new FileWriter(FILE)) {
            GSON.toJson(profiles, w);
        } catch (IOException ignored) {}
    }

    // ── CRUD ─────────────────────────────────────────────────────────────────

    public List<Profile> getAll() { return profiles; }

    public Profile getById(String id) {
        if (id == null) return profiles.get(0);
        for (Profile p : profiles) if (p.getId().equals(id)) return p;
        return profiles.isEmpty() ? null : profiles.get(0);
    }

    /** Adds or replaces (by id) and persists. */
    public void upsert(Profile profile) {
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).getId().equals(profile.getId())) {
                profiles.set(i, profile);
                save();
                return;
            }
        }
        profiles.add(profile);
        save();
    }

    /** Removes profile by id. Won't remove the last profile. */
    public void delete(String id) {
        if (profiles.size() <= 1) return;
        profiles.removeIf(p -> p.getId().equals(id));
        save();
    }
}
