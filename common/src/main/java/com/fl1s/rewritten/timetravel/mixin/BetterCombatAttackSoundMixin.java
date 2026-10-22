package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.journal.EffectJournal;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.bettercombat.network.Packets$AttackSound", remap = false)
public abstract class BetterCombatAttackSoundMixin {
    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void tt$record(double x, double y, double z, String soundId, float volume, float pitch, long seed, CallbackInfo ci) {
        try {
            if (!TimeTravel.isLooping || TimeTravel.server == null) return;
            if (!calledFromPlaySound()) return;

            ServerPlayer best = null;
            ServerLevel bestLevel = null;
            double bestDist = 64.0 * 64.0;
            for (ServerLevel level : TimeTravel.server.getAllLevels()) {
                for (ServerPlayer p : level.players()) {
                    if (Clones.is(p)) continue;
                    double d = p.distanceToSqr(x, y, z);
                    if (d < bestDist) {
                        bestDist = d;
                        best = p;
                        bestLevel = level;
                    }
                }
            }
            if (best == null || bestLevel == null) return;
            EffectJournal.recordCompatSound(bestLevel, best, x, y, z, ResourceLocation.parse(soundId), SoundSource.PLAYERS, volume, pitch);
        } catch (Throwable ignored) {
        }
    }

    private static boolean calledFromPlaySound() {
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        int limit = Math.min(st.length, 12);
        for (int i = 1; i < limit; i++) {
            if ("net.bettercombat.utils.SoundHelper".equals(st[i].getClassName()) && "playSound".equals(st[i].getMethodName())) {
                return true;
            }
        }
        return false;
    }
}