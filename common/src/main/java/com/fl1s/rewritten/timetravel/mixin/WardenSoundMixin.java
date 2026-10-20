package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.mob.WardenEventJournal;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.warden.Warden;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class WardenSoundMixin {

    @Inject(method = "playSound(Lnet/minecraft/sounds/SoundEvent;FF)V", at = @At("HEAD"))
    private void tt$wardenSoundStart(SoundEvent sound, float volume, float pitch, CallbackInfo ci) {
        if ((Object) this instanceof Warden warden) WardenEventJournal.onSound(warden, sound, volume, pitch);
    }

    @Inject(method = "playSound(Lnet/minecraft/sounds/SoundEvent;FF)V", at = @At("RETURN"))
    private void tt$wardenSoundEnd(SoundEvent sound, float volume, float pitch, CallbackInfo ci) {
        if ((Object) this instanceof Warden) WardenEventJournal.endSound();
    }
}
