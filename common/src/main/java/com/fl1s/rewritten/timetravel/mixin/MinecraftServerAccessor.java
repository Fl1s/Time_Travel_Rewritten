package com.fl1s.rewritten.timetravel.mixin;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.ChunkProgressListenerFactory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.concurrent.Executor;

@Mixin(MinecraftServer.class)
public interface MinecraftServerAccessor {

    @Accessor("levels")
    Map<ResourceKey<Level>, ServerLevel> tt$levels();

    @Accessor("executor")
    Executor tt$executor();

    @Accessor("progressListenerFactory")
    ChunkProgressListenerFactory tt$progressListenerFactory();

    @Accessor("storageSource")
    LevelStorageSource.LevelStorageAccess tt$storageSource();
}
