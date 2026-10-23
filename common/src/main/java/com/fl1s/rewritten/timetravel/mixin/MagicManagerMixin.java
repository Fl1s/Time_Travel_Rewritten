package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.journal.ParticleJournal;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "io.redspace.ironsspellbooks.capabilities.magic.MagicManager", remap = false)
public abstract class MagicManagerMixin {

    @Inject(method = "spawnParticles(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDDZ)V", at = @At("HEAD"), require = 0)
    private static void tt$captureSpellParticles(Level level, ParticleOptions particle, double x, double y, double z, int count, double dx, double dy, double dz, double speed, boolean force, CallbackInfo ci) {
        if (level instanceof ServerLevel serverLevel) {
            ParticleJournal.onSpellSpawn(serverLevel, particle, x, y, z, count, dx, dy, dz, speed, force);
        }
    }
}
