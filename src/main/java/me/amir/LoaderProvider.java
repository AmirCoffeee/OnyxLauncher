package me.amir;

import java.util.List;

public interface LoaderProvider {

    String id();

    String displayName();

    String accentColor();

    List<String> supportedMcVersions() throws Exception;

    List<String> loaderVersions(String mcVersion) throws Exception;

    String install(String mcVersion, String loaderVersion,
                   ProgressCallback cb) throws Exception;

    default boolean requiresVanillaFirst() { return true; }

    interface ProgressCallback {
        void onProgress(String message, int percent);
    }
}
