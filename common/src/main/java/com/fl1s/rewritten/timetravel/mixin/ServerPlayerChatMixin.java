package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.timeline.ChatTimeline;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerChatMixin {

    @Inject(method = "sendChatMessage", at = @At("HEAD"), cancellable = true)
    private void tt$filterCrossHeadChat(OutgoingChatMessage message, boolean filtered,
                                        ChatType.Bound bound, CallbackInfo ci) {
        if (ChatTimeline.blocksLive((ServerPlayer) (Object) this)) ci.cancel();
    }
}
