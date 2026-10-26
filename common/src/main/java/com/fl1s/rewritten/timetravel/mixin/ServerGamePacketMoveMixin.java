package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.time.HeadScope;
import com.fl1s.rewritten.timetravel.time.HeadScopeSupport;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketMoveMixin {

    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleMovePlayer", at = @At("HEAD"))
    private void tt$enterHead(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        long head = HeadScopeSupport.headFor(player);
        if (head > 0) HeadScope.push(head);
    }

    @Inject(method = "handleMovePlayer", at = @At("RETURN"))
    private void tt$exitHead(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (HeadScopeSupport.headFor(player) > 0) HeadScope.pop();
    }
}
