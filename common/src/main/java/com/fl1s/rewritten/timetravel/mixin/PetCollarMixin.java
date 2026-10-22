package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.mob.MobStateJournal;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.item.DyeColor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({Wolf.class, Cat.class})
public abstract class PetCollarMixin {
    @Inject(method = "setCollarColor", at = @At("HEAD"), require = 0)
    private void tt$before(DyeColor color, CallbackInfo ci) {
        Mob self = (Mob) (Object) this;
        if (tt$inWorld(self)) MobStateJournal.beforeChange(self);
    }

    @Inject(method = "setCollarColor", at = @At("RETURN"), require = 0)
    private void tt$after(DyeColor color, CallbackInfo ci) {
        Mob self = (Mob) (Object) this;
        if (tt$inWorld(self)) MobStateJournal.onChanged(self);
    }

    @Unique
    private static boolean tt$inWorld(Mob self) {
        return self.level() instanceof ServerLevel sl && sl.getEntity(self.getUUID()) == self;
    }
}
