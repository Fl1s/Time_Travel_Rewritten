package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.clone.HeadTracked;
import com.fl1s.rewritten.timetravel.clone.HeadVisibility;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Set;

@Mixin(targets = "net/minecraft/server/level/ChunkMap$TrackedEntity")
public abstract class TrackedEntityMixin implements HeadTracked {

    @Shadow
    @Final
    Entity entity;

    @Shadow
    @Final
    private Set<ServerPlayerConnection> seenBy;

    @Shadow
    public abstract void removePlayer(ServerPlayer player);

    @Shadow
    public abstract void updatePlayers(List<ServerPlayer> viewers);

    @Inject(method = "updatePlayer", at = @At("HEAD"), cancellable = true)
    private void tt$filterByHead(ServerPlayer viewer, CallbackInfo ci) {
        if (HeadVisibility.visibleTo(entity, viewer)) return;
        if (seenBy.contains(viewer.connection)) removePlayer(viewer);
        ci.cancel();
    }

    @Override
    public void tt$refreshTracking(List<ServerPlayer> viewers) {
        updatePlayers(viewers);
    }
}
