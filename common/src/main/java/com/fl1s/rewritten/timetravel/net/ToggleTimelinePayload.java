package com.fl1s.rewritten.timetravel.net;

import com.fl1s.rewritten.timetravel.TimeTravel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ToggleTimelinePayload() implements CustomPacketPayload {
    public static final ToggleTimelinePayload INSTANCE = new ToggleTimelinePayload();

    public static final CustomPacketPayload.Type<ToggleTimelinePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(TimeTravel.MOD_ID, "toggle"));

    public static final StreamCodec<FriendlyByteBuf, ToggleTimelinePayload> CODEC = new StreamCodec<>() {
        @Override
        public ToggleTimelinePayload decode(FriendlyByteBuf buf) {
            return INSTANCE;
        }

        @Override
        public void encode(FriendlyByteBuf buf, ToggleTimelinePayload payload) {
        }
    };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
