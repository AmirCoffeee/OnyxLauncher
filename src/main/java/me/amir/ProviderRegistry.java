package me.amir;

import java.util.*;

public class ProviderRegistry {

    private static final Map<String, LoaderProvider> PROVIDERS = new LinkedHashMap<>();

    static {
        register(new FabricProvider());
        register(new QuiltProvider());
        register(new ForgeProvider());
        register(new NeoForgeProvider());
        register(new OptiFineProvider());
    }

    public static void register(LoaderProvider p) {
        PROVIDERS.put(p.id(), p);
    }

    public static LoaderProvider get(String id) {
        return PROVIDERS.get(id);
    }

    public static Collection<LoaderProvider> all() {
        return Collections.unmodifiableCollection(PROVIDERS.values());
    }
}
