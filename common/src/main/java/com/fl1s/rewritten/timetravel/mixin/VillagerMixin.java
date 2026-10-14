package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.VillagerJournal;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Villager.class)
public abstract class VillagerMixin {
    @Inject(method = "tick", at = @At("RETURN"))
    private void tt$enforceState(CallbackInfo ci) {
        VillagerJournal.enforce((Villager)(Object)this);
    }
}