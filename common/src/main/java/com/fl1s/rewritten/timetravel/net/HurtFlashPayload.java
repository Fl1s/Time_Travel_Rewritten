package com.fl1s.rewritten.timetravel.net;

import com.fl1s.rewritten.timetravel.TimeTravel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record HurtFlashPayload(int entityId, int duration) implements CustomPacketPayload {
    public static final Type<HurtFlashPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(TimeTravel.MOD_ID, "hurt_flash"));

    public static final StreamCodec<FriendlyByteBuf, HurtFlashPayload> CODEC = new StreamCodec<>() {
        @Override
        public HurtFlashPayload decode(FriendlyByteBuf buf) {
            return new HurtFlashPayload(buf.readVarInt(), buf.readVarInt());
        }

        @Override
        public void encode(FriendlyByteBuf buf, HurtFlashPayload payload) {
            buf.writeVarInt(payload.entityId());
            buf.writeVarInt(payload.duration());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
