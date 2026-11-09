package me.amir;

import java.util.List;

public class NeoForgeProvider implements LoaderProvider {

    @Override public String id()           { return "neoforge"; }
    @Override public String displayName()  { return "NeoForge"; }
    @Override public String accentColor()  { return "#d4a017"; }

    @Override
    public List<String> supportedMcVersions() throws Exception {
        return NeoForgeInstaller.fetchSupportedMcVersions();
    }

    @Override
    public List<String> loaderVersions(String mcVersion) throws Exception {
        return NeoForgeInstaller.fetchVersionsForMc(mcVersion);
    }

    @Override
    public String install(String mcVersion, String loaderVersion, ProgressCallback cb) throws Exception {
        return NeoForgeInstaller.installNeoForge(loaderVersion, cb::onProgress);
    }
}
