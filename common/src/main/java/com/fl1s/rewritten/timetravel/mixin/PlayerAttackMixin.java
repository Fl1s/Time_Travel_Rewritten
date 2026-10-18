package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.GhostTracks;
import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.VehicleTrailJournal;
import com.fl1s.rewritten.timetravel.util.DoomedFeedback;
import com.fl1s.rewritten.timetravel.util.Ghosts;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public abstract class PlayerAttackMixin {
    @Inject(method = "attack", at = @At("HEAD"), cancellable = true)
    private void tt$handleAttack(Entity target, CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayer sp)) return;

        boolean vehicle = target instanceof AbstractMinecart || target instanceof Boat;
        boolean protectedVehicle = vehicle
                && TimeTravel.isLooping
                && VehicleTrailJournal.hasFutureSamples(target.getUUID(), TimeTravel.timelineTick);

        if (protectedVehicle) {
            if (!Ghosts.is(sp) && target.level() instanceof ServerLevel sl) {
                DoomedFeedback.tryShowVehicle(sp, sl);
            }
            ci.cancel();
            return;
        }

        GhostTracks.onAttack(sp, target);
    }
}