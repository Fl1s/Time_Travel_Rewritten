package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.client.CloneVanishFx;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.inventory.InventoryMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ItemRenderer.class)
public class ItemRendererMixin {

    @ModifyVariable(method = "getFoilBufferDirect", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static RenderType tt$forceEntityShaderForHand(RenderType original) {
        if (CloneVanishFx.active()) {
            String name = original.toString();
            if (name.contains("entity") || name.contains("outline") || name.contains("beacon")) {
                return original;
            }
            return RenderType.entityTranslucent(InventoryMenu.BLOCK_ATLAS);
        }
        return original;
    }
}
