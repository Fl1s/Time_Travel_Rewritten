package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.client.CloneVanishFx;
import net.minecraft.client.model.AgeableListModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(AgeableListModel.class)
public abstract class AgeableListModelMixin {

    @ModifyVariable(method = "renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V", at = @At("HEAD"), argsOnly = true, index = 4)
    private int tt$vanishOverlay(int overlay) {
        return CloneVanishFx.overlay(overlay);
    }

    @ModifyVariable(method = "renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V", at = @At("HEAD"), argsOnly = true, index = 5)
    private int tt$vanishColor(int color) {
        return CloneVanishFx.tint(color);
    }
}
