package me.amir;

import java.util.List;
import java.util.stream.Collectors;

public class OptiFineProvider implements LoaderProvider {

    @Override public String id()           { return "optifine"; }
    @Override public String displayName()  { return "OptiFine"; }
    @Override public String accentColor()  { return "#3b9ddd"; }

    @Override
    public List<String> supportedMcVersions() {
        return OptiFineInstaller.supportedMcVersions();
    }

    @Override
    public List<String> loaderVersions(String mcVersion) {
        return OptiFineInstaller.versionsForMc(mcVersion)
            .stream()
            .map(v -> v.ofEdition)
            .collect(Collectors.toList());
    }

    @Override
    public String install(String mcVersion, String loaderVersion, ProgressCallback cb) throws Exception {
        return OptiFineInstaller.installOptiFine(mcVersion, loaderVersion, cb::onProgress);
    }
}
