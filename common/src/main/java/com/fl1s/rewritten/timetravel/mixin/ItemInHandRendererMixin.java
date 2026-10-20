package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.client.CloneVanishFx;
import com.fl1s.rewritten.timetravel.client.VanishBufferSource;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {

    @ModifyVariable(method = "renderItem", at = @At("HEAD"), argsOnly = true, ordinal = 0
    )
    private MultiBufferSource tt$vanishHandBuffer(MultiBufferSource buffer) {
        return CloneVanishFx.active() ? new VanishBufferSource(buffer) : buffer;
    }
}
