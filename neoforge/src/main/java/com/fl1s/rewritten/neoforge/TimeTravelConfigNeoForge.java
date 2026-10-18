package com.fl1s.rewritten.neoforge;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.config.ConfigOption;
import com.fl1s.rewritten.timetravel.config.ConfigOptions;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.LinkedHashMap;
import java.util.Map;

@EventBusSubscriber(modid = TimeTravel.MOD_ID)
public final class TimeTravelConfigNeoForge {
    private TimeTravelConfigNeoForge() {
    }

    public static final ModConfigSpec CLIENT_SPEC;
    public static final ModConfigSpec SERVER_SPEC;

    private static final Map<ConfigOption, ModConfigSpec.ConfigValue<?>> CLIENT_VALUES = new LinkedHashMap<>();
    private static final Map<ConfigOption, ModConfigSpec.ConfigValue<?>> SERVER_VALUES = new LinkedHashMap<>();

    static {
        ModConfigSpec.Builder client = new ModConfigSpec.Builder();
        for (ConfigOption option : ConfigOptions.CLIENT) {
            client.push(option.category());
            CLIENT_VALUES.put(option, define(client, option));
            client.pop();
        }
        CLIENT_SPEC = client.build();

        ModConfigSpec.Builder server = new ModConfigSpec.Builder();
        for (ConfigOption option : ConfigOptions.SERVER) {
            server.push(option.category());
            SERVER_VALUES.put(option, define(server, option));
            server.pop();
        }
        SERVER_SPEC = server.build();
    }

    private static ModConfigSpec.ConfigValue<?> define(ModConfigSpec.Builder builder, ConfigOption option) {
        builder.comment(option.comment());
        return switch (option) {
            case ConfigOption.Bool b -> builder.define(b.key(), b.def());
            case ConfigOption.Int i -> builder.defineInRange(i.key(), i.def(), i.min(), i.max());
            case ConfigOption.Dec d -> builder.defineInRange(d.key(), d.def(), d.min(), d.max());
        };
    }

    @SubscribeEvent
    public static void onConfigEvent(ModConfigEvent event) {
        IConfigSpec spec = event.getConfig().getSpec();
        if (spec.equals(CLIENT_SPEC)) {
            applyClient();
        } else if (spec.equals(SERVER_SPEC)) {
            applyServer();
        }
    }

    private static void applyClient() {
        for (Map.Entry<ConfigOption, ModConfigSpec.ConfigValue<?>> entry : CLIENT_VALUES.entrySet()) {
            entry.getKey().accept(entry.getValue().get());
        }
    }

    private static void applyServer() {
        Runnable task = () -> {
            for (Map.Entry<ConfigOption, ModConfigSpec.ConfigValue<?>> entry : SERVER_VALUES.entrySet()) {
                entry.getKey().accept(entry.getValue().get());
            }
            ConfigOptions.afterApply();
        };

        MinecraftServer server = TimeTravel.server;
        if (server != null && server.isRunning() && !server.isSameThread()) {
            server.execute(task);
        } else {
            task.run();
        }
    }
}
