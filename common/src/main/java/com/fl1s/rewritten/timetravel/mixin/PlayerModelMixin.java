package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.client.CloneVanishFx;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PlayerModel.class)
public abstract class PlayerModelMixin {

    @Redirect(method = "renderCloak", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/geom/ModelPart;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;II)V"), remap = false)
    private void tt$vanishCloak(ModelPart part, PoseStack poseStack, VertexConsumer vc, int light, int overlay) {
        part.render(poseStack, vc, light, CloneVanishFx.overlay(overlay), CloneVanishFx.tint(0xFFFFFFFF));
    }
}
