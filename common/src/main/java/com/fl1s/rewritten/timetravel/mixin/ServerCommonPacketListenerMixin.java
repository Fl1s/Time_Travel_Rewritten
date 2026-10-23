package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.compat.anim.AnimRelay;
import com.fl1s.rewritten.timetravel.compat.anim.AttackRelay;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerMixin {
    @Inject(method = "handleCustomPayload", at = @At("HEAD"), require = 0)
    private void tt$handleCustomPayload(ServerboundCustomPayloadPacket packet, CallbackInfo ci) {
        try {
            if ((Object) this instanceof ServerGamePacketListenerImpl game) {
                AnimRelay.onIncoming(packet.payload(), game.player);
                AttackRelay.onIncoming(packet.payload(), game.player);
            }
        } catch (Throwable ignored) {
        }
    }
}
