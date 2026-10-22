package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.client.TimelineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {

    @Shadow
    private Button disconnectButton;

    private PauseScreenMixin() {
        super(null);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void tt$addReturnToPresent(CallbackInfo ci) {
        if (!((PauseScreen) (Object) this).showsPauseMenu()) return;
        if (disconnectButton == null || !TimelineClient.canReturnToPresent()) return;

        addRenderableWidget(Button.builder(
                        Component.translatable("gui.timetravel_rewritten.return_present"),
                        button -> {
                            TimelineClient.returnToPresent();
                            Minecraft.getInstance().setScreen(null);
                            Minecraft.getInstance().mouseHandler.grabMouse();
                        })
                .bounds(disconnectButton.getX(), disconnectButton.getY() + disconnectButton.getHeight() + 4,
                        disconnectButton.getWidth(), 20)
                .build());
    }
}
