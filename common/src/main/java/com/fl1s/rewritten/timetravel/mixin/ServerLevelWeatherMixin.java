package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.journal.WeatherJournal;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class ServerLevelWeatherMixin {
    @Inject(method = "advanceWeatherCycle", at = @At("HEAD"))
    private void tt$enforceWeather(CallbackInfo ci) {
        WeatherJournal.enforce((ServerLevel) (Object) this);
    }

    @Inject(method = "advanceWeatherCycle", at = @At("RETURN"))
    private void tt$advanceWeather(CallbackInfo ci) {
        WeatherJournal.onAdvance((ServerLevel) (Object) this);
    }

    @Inject(method = "setWeatherParameters", at = @At("HEAD"), cancellable = true)
    private void tt$blockSetWeather(int clearTime, int rainTime, boolean raining, boolean thundering, CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (WeatherJournal.isProtected(self) && !WeatherJournal.isApplying()) ci.cancel();
    }

    @Inject(method = "setWeatherParameters", at = @At("RETURN"))
    private void tt$setWeather(int clearTime, int rainTime, boolean raining, boolean thundering, CallbackInfo ci) {
        WeatherJournal.onSetWeather((ServerLevel) (Object) this);
    }

    @Inject(method = "resetWeatherCycle", at = @At("HEAD"), cancellable = true)
    private void tt$blockResetWeather(CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (WeatherJournal.isProtected(self) && !WeatherJournal.isApplying()) ci.cancel();
    }
}
