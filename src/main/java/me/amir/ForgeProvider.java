package me.amir;

import java.util.List;

public class ForgeProvider implements LoaderProvider {

    @Override public String id()           { return "forge"; }
    @Override public String displayName()  { return "Forge"; }
    @Override public String accentColor()  { return "#e87500"; }

    @Override
    public List<String> supportedMcVersions() throws Exception {
        return ForgeInstaller.fetchSupportedMcVersions();
    }

    @Override
    public List<String> loaderVersions(String mcVersion) throws Exception {
        return ForgeInstaller.fetchForgeVersions(mcVersion);
    }

    @Override
    public String install(String mcVersion, String loaderVersion, ProgressCallback cb) throws Exception {
        ForgeInstaller.ProgressCallback fc = cb::onProgress;
        return ForgeInstaller.installForge(loaderVersion, fc);
    }
}
