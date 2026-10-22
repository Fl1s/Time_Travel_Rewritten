package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.mob.MobStateJournal;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.TamableAnimal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TamableAnimal.class)
public abstract class TamableAnimalMixin {
    @Inject(method = {"setTame", "setInSittingPose", "setOwnerUUID"}, at = @At("HEAD"), require = 0)
    private void tt$before(CallbackInfo ci) {
        TamableAnimal self = (TamableAnimal) (Object) this;
        if (tt$inWorld(self)) MobStateJournal.beforeChange(self);
    }

    @Inject(method = {"setTame", "setInSittingPose", "setOwnerUUID"}, at = @At("RETURN"), require = 0)
    private void tt$after(CallbackInfo ci) {
        TamableAnimal self = (TamableAnimal) (Object) this;
        if (tt$inWorld(self)) MobStateJournal.onChanged(self);
    }

    @Unique
    private static boolean tt$inWorld(TamableAnimal self) {
        return self.level() instanceof ServerLevel sl && sl.getEntity(self.getUUID()) == self;
    }
}
