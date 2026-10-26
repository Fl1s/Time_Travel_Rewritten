package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.journal.PastContainers;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.OptionalInt;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMenuMixin {

    @Inject(method = "openMenu(Lnet/minecraft/world/MenuProvider;)Ljava/util/OptionalInt;", at = @At("RETURN"))
    private void tt$showPastContents(MenuProvider provider, CallbackInfoReturnable<OptionalInt> cir) {
        if (cir.getReturnValue().isEmpty()) return;
        ServerPlayer self = (ServerPlayer) (Object) this;
        try {
            PastContainers.onMenuOpened(self, self.containerMenu);
        } catch (Throwable t) {
            TimeTravel.TIMELINE_LOGGER.error("Failed to show past container contents", t);
        }
    }
}
