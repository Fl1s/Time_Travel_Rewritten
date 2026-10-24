package com.fl1s.rewritten.timetravel.net;

import com.fl1s.rewritten.timetravel.TimeTravel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ExemptBlocksPayload(String dim, boolean reset, long[] added, long[] removed) implements CustomPacketPayload {
    public static final Type<ExemptBlocksPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TimeTravel.MOD_ID, "exempt_blocks"));

    public static final StreamCodec<FriendlyByteBuf, ExemptBlocksPayload> CODEC = new StreamCodec<>() {
        @Override
        public ExemptBlocksPayload decode(FriendlyByteBuf buf) {
            return new ExemptBlocksPayload(buf.readUtf(), buf.readBoolean(), buf.readLongArray(), buf.readLongArray());
        }

        @Override
        public void encode(FriendlyByteBuf buf, ExemptBlocksPayload payload) {
            buf.writeUtf(payload.dim());
            buf.writeBoolean(payload.reset());
            buf.writeLongArray(payload.added());
            buf.writeLongArray(payload.removed());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
