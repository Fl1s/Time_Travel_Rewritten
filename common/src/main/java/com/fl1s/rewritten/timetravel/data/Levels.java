package com.fl1s.rewritten.timetravel.data;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache for {@link ResourceKey}{@code <Level>} lookups from dimension id strings.
 */
public final class Levels {
    private Levels() {
    }

    private static final Map<String, ResourceKey<Level>> CACHE = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, ResourceKey<Level>> VIEWS = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, String> IDS = new ConcurrentHashMap<>();

    public static ResourceKey<Level> key(String dim) {
        return CACHE.computeIfAbsent(dim, d -> ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(d)));
    }

    public static void registerView(ResourceKey<Level> view, ResourceKey<Level> canonical) {
        if (view != null && canonical != null) VIEWS.put(view, canonical);
    }

    public static void unregisterView(ResourceKey<Level> view) {
        if (view != null) VIEWS.remove(view);
    }

    public static ResourceKey<Level> canonical(Level level) {
        if (level == null) return null;
        ResourceKey<Level> own = level.dimension();
        ResourceKey<Level> view = VIEWS.get(own);
        return view == null ? own : view;
    }

    public static String canonicalId(Level level) {
        ResourceKey<Level> key = canonical(level);
        if (key == null) return "";
        return IDS.computeIfAbsent(key, k -> k.location().toString().intern());
    }
}
