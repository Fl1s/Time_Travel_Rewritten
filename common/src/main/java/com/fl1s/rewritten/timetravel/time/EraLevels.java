package com.fl1s.rewritten.timetravel.time;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.data.Levels;
import com.fl1s.rewritten.timetravel.mixin.MinecraftServerAccessor;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.ServerLevelData;

import java.util.ArrayList;
import java.util.List;

public final class EraLevels {

    private EraLevels() {
    }

    private static final int PROGRESS_RADIUS = 11;

    public static ResourceKey<Level> keyFor(long offset) {
        return ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath(TimeTravel.MOD_ID, "era_" + offset));
    }

    public static boolean isEra(Level level) {
        return level != null && level.dimension().location().getNamespace().equals(TimeTravel.MOD_ID)
                && level.dimension().location().getPath().startsWith("era_");
    }

    public static ServerLevel getOrCreate(TimeHead head) {
        if (head == null || head.isPresent()) return TimeTravel.serverLevel;
        if (head.hasOwnLevel()) return head.level();

        MinecraftServer server = TimeTravel.server;
        ServerLevel canonical = TimeTravel.serverLevel;
        if (server == null || canonical == null) return null;

        try {
            ResourceKey<Level> key = keyFor(head.offset());
            MinecraftServerAccessor access = (MinecraftServerAccessor) server;

            ServerLevel existing = access.tt$levels().get(key);
            if (existing != null) {
                adopt(head, existing, canonical);
                return existing;
            }

            LevelStem stem = server.registryAccess()
                    .registryOrThrow(Registries.LEVEL_STEM)
                    .get(LevelStem.OVERWORLD);
            if (stem == null) {
                TimeTravel.TIMELINE_LOGGER.error("Cannot create era level: no overworld level stem");
                return null;
            }

            ServerLevelData data = new DerivedLevelData(server.getWorldData(),
                    server.getWorldData().overworldData());

            ServerLevel level = new ServerLevel(
                    server,
                    access.tt$executor(),
                    access.tt$storageSource(),
                    data,
                    key,
                    stem,
                    access.tt$progressListenerFactory().create(PROGRESS_RADIUS),
                    server.getWorldData().isDebugWorld(),
                    BiomeManager.obfuscateSeed(server.getWorldData().worldGenOptions().seed()),
                    List.of(),
                    false,
                    canonical.getRandomSequences());

            level.noSave = true;
            access.tt$levels().put(key, level);
            adopt(head, level, canonical);

            TimeTravel.TIMELINE_LOGGER.info("Created era level {} for offset {} (position {})",
                    key.location(), head.offset(), head.position());
            return level;
        } catch (Throwable t) {
            TimeTravel.TIMELINE_LOGGER.error("Failed to create era level for offset {}", head.offset(), t);
            return null;
        }
    }

    private static void adopt(TimeHead head, ServerLevel level, ServerLevel canonical) {
        head.setLevel(level);
        Levels.registerView(level.dimension(), canonical.dimension());
    }

    public static void release(TimeHead head) {
        if (head == null || !head.hasOwnLevel()) return;
        ServerLevel level = head.level();
        ResourceKey<Level> key = level.dimension();

        try {
            evacuate(level);
            MinecraftServer server = TimeTravel.server;
            if (server != null) ((MinecraftServerAccessor) server).tt$levels().remove(key);
            Levels.unregisterView(key);
            TimeTravel.TIMELINE_LOGGER.info("Released era level {}", key.location());
        } catch (Throwable t) {
            TimeTravel.TIMELINE_LOGGER.error("Failed to release era level {}", key.location(), t);
        } finally {
            head.clearLevel();
        }
    }

    public static void releaseAll() {
        TimeHeads heads = TimeTravel.heads();
        if (heads == null) return;
        for (TimeHead head : new ArrayList<>(heads.all())) release(head);
    }

    public static void sweep() {
        TimeHeads heads = TimeTravel.heads();
        if (heads == null) return;
        for (TimeHead head : new ArrayList<>(heads.all())) {
            if (head.isReleaseRequested()) release(head);
        }
    }

    private static void evacuate(ServerLevel level) {
        ServerLevel canonical = TimeTravel.serverLevel;
        if (canonical == null || TimeTravel.server == null) return;
        for (ServerPlayer player : new ArrayList<>(level.players())) {
            player.teleportTo(canonical, player.getX(), player.getY(), player.getZ(),
                    player.getYRot(), player.getXRot());
        }
    }
}
