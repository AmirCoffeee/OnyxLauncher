package me.amir;

import java.util.List;

public class QuiltProvider implements LoaderProvider {

    @Override public String id()           { return "quilt"; }
    @Override public String displayName()  { return "Quilt"; }
    @Override public String accentColor()  { return "#9722d4"; }

    @Override
    public List<String> supportedMcVersions() throws Exception {
        return MinecraftInstaller.fetchReleaseVersions();
    }

    @Override
    public List<String> loaderVersions(String mcVersion) throws Exception {
        return QuiltInstaller.fetchLoaderVersions();
    }

    @Override
    public String install(String mcVersion, String loaderVersion, ProgressCallback cb) throws Exception {
        return QuiltInstaller.installQuilt(mcVersion, loaderVersion, cb::onProgress);
    }
}
