package com.fl1s.rewritten.timetravel.net;

import com.fl1s.rewritten.timetravel.TimeTravel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record CloneVanishPayload(int entityId, int remainingTicks) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<CloneVanishPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(TimeTravel.MOD_ID, "clone_vanish"));

    public static final StreamCodec<FriendlyByteBuf, CloneVanishPayload> CODEC = new StreamCodec<>() {
        @Override
        public CloneVanishPayload decode(FriendlyByteBuf buf) {
            return new CloneVanishPayload(buf.readVarInt(), buf.readVarInt());
        }

        @Override
        public void encode(FriendlyByteBuf buf, CloneVanishPayload payload) {
            buf.writeVarInt(payload.entityId());
            buf.writeVarInt(payload.remainingTicks());
        }
    };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
