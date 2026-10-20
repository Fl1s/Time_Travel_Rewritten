package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.client.CloneVanishFx;
import net.minecraft.client.model.ElytraModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.ShieldModel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Model.class)
public abstract class ModelMixin {

    @Inject(method = "renderType", at = @At("RETURN"), cancellable = true)
    private void tt$vanishRenderType(ResourceLocation location, CallbackInfoReturnable<RenderType> cir) {
        if (!CloneVanishFx.active()) return;
        Object self = this;
        if (self instanceof ShieldModel || self instanceof ElytraModel) {
            cir.setReturnValue(RenderType.entityTranslucent(location));
        }
    }
}
