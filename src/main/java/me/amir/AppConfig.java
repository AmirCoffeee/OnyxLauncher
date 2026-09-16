package me.amir;

import java.io.File;

public class AppConfig {
    public static String getMinecraftDir() {
        Settings s = Settings.load();
        if (s.getCustomMcPath() != null && !s.getCustomMcPath().trim().isEmpty()) {
            File customDir = new File(s.getCustomMcPath());
            if (customDir.exists() && customDir.isDirectory()) {
                return customDir.getAbsolutePath();
            }
        }
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            return System.getenv("APPDATA") + File.separator + ".minecraft";
        } else if (os.contains("mac")) {
            return System.getProperty("user.home") + "/Library/Application Support/minecraft";
        } else {
            return System.getProperty("user.home") + "/.minecraft";
        }
    }
}