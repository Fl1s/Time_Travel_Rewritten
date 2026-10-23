package com.fl1s.rewritten.timetravel.data;

import com.fl1s.rewritten.timetravel.TimeTravel;
import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fast, allocation-free detection of mocap FakePlayers.
 */
public final class Clones {
    private Clones() {
    }

    private static final List<String> FOREIGN = new CopyOnWriteArrayList<>(List.of("com.simibubi.create.", "mekanism.", "appeng.", "cofh.", "blusunrize.", "com.refinedmods."));
    private static final Map<Class<?>, Boolean> CACHE = new ConcurrentHashMap<>();

    public static void excludePrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) return;
        FOREIGN.add(prefix);
        CACHE.clear();
    }

    private static boolean detect(Class<?> type) {
        String name = type.getName();
        if (!name.contains("FakePlayer")) return false;
        for (String prefix : FOREIGN) if (name.startsWith(prefix)) return false;
        TimeTravel.TIMELINE_LOGGER.info("Clone player class detected: {}", name);
        return true;
    }

    public static boolean isVisualOnly(Entity entity) {
        return false;
    }

    public static boolean is(Object o) {
        return o != null && CACHE.computeIfAbsent(o.getClass(), Clones::detect);
    }
}
