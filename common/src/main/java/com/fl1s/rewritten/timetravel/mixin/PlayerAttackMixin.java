package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.clone.CloneTracks;
import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.time.TimeContext;
import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.data.Exempt;
import com.fl1s.rewritten.timetravel.vehicle.VehicleJournal;
import com.fl1s.rewritten.timetravel.vehicle.VehicleSpawnJournal;
import com.fl1s.rewritten.timetravel.vehicle.VehicleTrailJournal;
import com.fl1s.rewritten.timetravel.util.DoomedFeedback;
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

        if (TimeTravel.isLooping && VehicleJournal.isTracked(target) && !Exempt.is(target)) {
            if (Clones.is(sp)) {
                VehicleJournal.cosmeticHit(target);
                ci.cancel();
                return;
            }
            long now = TimeContext.positionIn(sp.level());
            if (VehicleSpawnJournal.isProtected(target.getUUID(), now) || VehicleTrailJournal.hasFutureSamples(target.getUUID(), now)) {
                if (target.level() instanceof ServerLevel sl) DoomedFeedback.tryShowVehicle(sp, sl);
                ci.cancel();
                return;
            }
        }
        if (!Exempt.is(target)) CloneTracks.onAttack(sp, target);
    }
}