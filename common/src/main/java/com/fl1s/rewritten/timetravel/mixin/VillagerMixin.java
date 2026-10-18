package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.MobStateJournal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Villager.class)
public abstract class VillagerMixin {
    @Inject(method = "setVillagerData", at = @At("RETURN"))
    private void tt$afterSetVillagerData(VillagerData data, CallbackInfo ci) {
        MobStateJournal.onChanged((Villager) (Object) this);
    }
}
