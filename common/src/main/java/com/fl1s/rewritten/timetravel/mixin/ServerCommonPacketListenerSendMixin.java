package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.compat.anim.CastRelay;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerSendMixin {
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), require = 0)
    private void tt$onSend(Packet<?> packet, CallbackInfo ci) {
        try {
            if (!((Object) this instanceof ServerGamePacketListenerImpl game)) return;
            if (packet instanceof ClientboundCustomPayloadPacket custom) {
                CastRelay.onOutgoing(game.player, custom.payload());
            }
        } catch (Throwable ignored) {
        }
    }
}
