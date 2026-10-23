package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.data.ReplayState;
import com.fl1s.rewritten.timetravel.journal.BlockJournal;
import com.fl1s.rewritten.timetravel.journal.TrackedEntityJournal;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "io.redspace.ironsspellbooks.entity.spells.spectral_hammer.SpectralHammer", remap = false)
public abstract class SpectralHammerMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 0)
    private void tt$wrapTick(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (ReplayState.isReplaying() || TrackedEntityJournal.isPuppeted(self)) {
            ci.cancel();
            return;
        }
        BlockJournal.enterAction();
    }

    @Inject(method = "tick", at = @At("RETURN"), require = 0)
    private void tt$exitTick(CallbackInfo ci) {
        BlockJournal.exitAction();
    }
}
