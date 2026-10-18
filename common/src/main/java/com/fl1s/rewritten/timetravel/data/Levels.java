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

    public static ResourceKey<Level> key(String dim) {
        return CACHE.computeIfAbsent(dim, d -> ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(d)));
    }
}
