package com.fl1s.rewritten.timetravel.net;

import com.fl1s.rewritten.timetravel.TimeTravel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record DragonDeathPayload(int entityId, int deathTime) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<DragonDeathPayload> TYPE = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(TimeTravel.MOD_ID, "dragon_death"));

    public static final StreamCodec<FriendlyByteBuf, DragonDeathPayload> CODEC = new StreamCodec<>() {
        @Override
        public DragonDeathPayload decode(FriendlyByteBuf buf) {
            return new DragonDeathPayload(buf.readVarInt(), buf.readVarInt());
        }

        @Override
        public void encode(FriendlyByteBuf buf, DragonDeathPayload p) {
            buf.writeVarInt(p.entityId());
            buf.writeVarInt(p.deathTime());
        }
    };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
