package com.fl1s.rewritten.timetravel.net;

import com.fl1s.rewritten.timetravel.TimeTravel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record WardenAnimPayload(int entityId, int anim, int elapsed) implements CustomPacketPayload {
    public static final int ATTACK = 0;
    public static final int SONIC_BOOM = 1;
    public static final int EMERGE = 2;
    public static final int DIG = 3;
    public static final int ROAR = 4;
    public static final int SNIFF = 5;

    public static final CustomPacketPayload.Type<WardenAnimPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(TimeTravel.MOD_ID, "warden_anim"));

    public static final StreamCodec<FriendlyByteBuf, WardenAnimPayload> CODEC = new StreamCodec<>() {
        @Override
        public WardenAnimPayload decode(FriendlyByteBuf buf) {
            return new WardenAnimPayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        }

        @Override
        public void encode(FriendlyByteBuf buf, WardenAnimPayload payload) {
            buf.writeVarInt(payload.entityId());
            buf.writeVarInt(payload.anim());
            buf.writeVarInt(payload.elapsed());
        }
    };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
