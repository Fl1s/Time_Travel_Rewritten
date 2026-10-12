package com.fl1s.rewritten.timetravel.net;

import com.fl1s.rewritten.timetravel.TimeTravel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record JumpRequestPayload(long targetTick) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<JumpRequestPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(TimeTravel.MOD_ID, "jump"));

    public static final StreamCodec<FriendlyByteBuf, JumpRequestPayload> CODEC = new StreamCodec<>() {
        @Override
        public JumpRequestPayload decode(FriendlyByteBuf buf) {
            return new JumpRequestPayload(buf.readLong());
        }

        @Override
        public void encode(FriendlyByteBuf buf, JumpRequestPayload payload) {
            buf.writeLong(payload.targetTick());
        }
    };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
