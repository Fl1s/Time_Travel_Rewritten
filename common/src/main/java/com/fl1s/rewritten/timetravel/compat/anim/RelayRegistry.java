package com.fl1s.rewritten.timetravel.compat.anim;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class RelayRegistry {
    private RelayRegistry() {
    }

    private static final Map<ResourceLocation, RelayAdapter> ADAPTERS = new ConcurrentHashMap<>();

    public static void register(RelayAdapter adapter) {
        if (adapter != null && adapter.recordId() != null) {
            ADAPTERS.put(adapter.recordId(), adapter);
        }
    }

    public static RelayAdapter get(ResourceLocation id) {
        return ADAPTERS.get(id);
    }
}
