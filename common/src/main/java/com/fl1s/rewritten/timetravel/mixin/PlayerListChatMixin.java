package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.timeline.ChatTimeline;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public abstract class PlayerListChatMixin {

    @Inject(method = "broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/chat/ChatType$Bound;)V",
            at = @At("HEAD"))
    private void tt$beginBroadcast(PlayerChatMessage message, ServerPlayer sender,
                                   ChatType.Bound bound, CallbackInfo ci) {
        ChatTimeline.beginBroadcast(sender);
    }

    @Inject(method = "broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/chat/ChatType$Bound;)V",
            at = @At("RETURN"))
    private void tt$endBroadcast(PlayerChatMessage message, ServerPlayer sender,
                                 ChatType.Bound bound, CallbackInfo ci) {
        ChatTimeline.endBroadcast();
    }
}
