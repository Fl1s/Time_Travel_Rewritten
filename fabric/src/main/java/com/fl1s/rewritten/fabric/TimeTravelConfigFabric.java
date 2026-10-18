package com.fl1s.rewritten.fabric;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.config.ConfigOption;
import com.fl1s.rewritten.timetravel.config.ConfigOptions;
import com.fl1s.rewritten.timetravel.util.JsonStore;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TimeTravelConfigFabric {
    private TimeTravelConfigFabric() {}

    private static final Type NESTED_TYPE =
            new TypeToken<Map<String, Map<String, Object>>>() {}.getType();

    private static Path clientPath() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve("timetravel_rewritten-client.json");
    }

    private static Path serverPath(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT)
                .resolve("serverconfig")
                .resolve("timetravel_rewritten-server.json");
    }

    public static void loadClient() {
        apply(clientPath(), ConfigOptions.CLIENT);
    }

    public static void loadServer(MinecraftServer server) {
        apply(serverPath(server), ConfigOptions.SERVER);
        ConfigOptions.afterApply();
    }

    private static void apply(Path path, List<ConfigOption> options) {
        Map<String, Map<String, Object>> data = JsonStore.load(path, NESTED_TYPE);
        boolean dirty = data == null;
        if (data == null) data = new LinkedHashMap<>();

        for (ConfigOption option : options) {
            Map<String, Object> category = data.computeIfAbsent(
                    option.category(), k -> new LinkedHashMap<>());
            Object value = category.get(option.key());
            if (value == null) {
                category.put(option.key(), option.defaultValue());
                option.accept(option.defaultValue());
                dirty = true;
            } else {
                try {
                    option.accept(value);
                } catch (Exception ex) {
                    TimeTravel.LOOP_LOGGER.warn(
                            "Invalid config value for {}.{}: {}",
                            option.category(), option.key(), value);
                    option.accept(option.defaultValue());
                }
            }
        }

        if (dirty) {
            JsonStore.save(path, data, NESTED_TYPE);
        }
    }
}
