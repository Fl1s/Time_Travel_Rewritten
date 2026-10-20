package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.client.CloneVanishFx;
import com.fl1s.rewritten.timetravel.client.VanishBufferSource;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CapeLayer.class)
public abstract class CapeLayerMixin extends net.minecraft.client.renderer.entity.layers.RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
    public CapeLayerMixin(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
        super(parent);
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void tt$applyVanishToCape(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, AbstractClientPlayer player, float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo ci) {
        CloneVanishFx.begin(player, partialTick);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void tt$resetVanishAfterCape(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, AbstractClientPlayer player, float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo ci) {
        CloneVanishFx.end();
    }
}
