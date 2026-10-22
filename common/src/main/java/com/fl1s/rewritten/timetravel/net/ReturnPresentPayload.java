package com.fl1s.rewritten.timetravel.net;

import com.fl1s.rewritten.timetravel.TimeTravel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ReturnPresentPayload() implements CustomPacketPayload {
    public static final ReturnPresentPayload INSTANCE = new ReturnPresentPayload();

    public static final CustomPacketPayload.Type<ReturnPresentPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(TimeTravel.MOD_ID, "return_present"));

    public static final StreamCodec<FriendlyByteBuf, ReturnPresentPayload> CODEC = new StreamCodec<>() {
        @Override
        public ReturnPresentPayload decode(FriendlyByteBuf buf) {
            return INSTANCE;
        }

        @Override
        public void encode(FriendlyByteBuf buf, ReturnPresentPayload payload) {
        }
    };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
