package com.fl1s.rewritten.timetravel.net;

import com.fl1s.rewritten.timetravel.TimeTravel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ClearChatPayload() implements CustomPacketPayload {
    public static final ClearChatPayload INSTANCE = new ClearChatPayload();

    public static final CustomPacketPayload.Type<ClearChatPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(TimeTravel.MOD_ID, "clear_chat"));

    public static final StreamCodec<FriendlyByteBuf, ClearChatPayload> CODEC = new StreamCodec<>() {
        @Override
        public ClearChatPayload decode(FriendlyByteBuf buf) {
            return INSTANCE;
        }

        @Override
        public void encode(FriendlyByteBuf buf, ClearChatPayload payload) {
        }
    };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
