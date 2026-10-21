package com.fl1s.rewritten.timetravel.net;

import com.fl1s.rewritten.timetravel.TimeTravel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record CloneAppearPayload(int entityId, int delayTicks) implements CustomPacketPayload {
    public static final Type<CloneAppearPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TimeTravel.MOD_ID, "clone_appear"));

    public static final StreamCodec<FriendlyByteBuf, CloneAppearPayload> CODEC = new StreamCodec<>() {
        @Override
        public CloneAppearPayload decode(FriendlyByteBuf buf) {
            return new CloneAppearPayload(buf.readVarInt(), buf.readVarInt());
        }

        @Override
        public void encode(FriendlyByteBuf buf, CloneAppearPayload payload) {
            buf.writeVarInt(payload.entityId());
            buf.writeVarInt(payload.delayTicks);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
