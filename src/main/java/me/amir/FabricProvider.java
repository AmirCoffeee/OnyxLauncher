package me.amir;

import java.util.List;

public class FabricProvider implements LoaderProvider {

    @Override public String id()           { return "fabric"; }
    @Override public String displayName()  { return "Fabric"; }
    @Override public String accentColor()  { return "#6a3fc8"; }

    @Override
    public List<String> supportedMcVersions() throws Exception {
        return MinecraftInstaller.fetchReleaseVersions();
    }

    @Override
    public List<String> loaderVersions(String mcVersion) throws Exception {
        return FabricInstaller.fetchLoaderVersions();
    }

    @Override
    public String install(String mcVersion, String loaderVersion, ProgressCallback cb) throws Exception {
        FabricInstaller.ProgressCallback fc = cb::onProgress;
        return FabricInstaller.installFabric(mcVersion, loaderVersion, fc);
    }
}
