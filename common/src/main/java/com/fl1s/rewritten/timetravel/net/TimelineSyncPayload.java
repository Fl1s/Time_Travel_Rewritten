package com.fl1s.rewritten.timetravel.net;

import com.fl1s.rewritten.timetravel.TimeTravel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record TimelineSyncPayload(long tick, long serverTick, boolean running, boolean showTicks,
                                  boolean showHud, boolean individualTime) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<TimelineSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(TimeTravel.MOD_ID, "sync"));

    public static final StreamCodec<FriendlyByteBuf, TimelineSyncPayload> CODEC = new StreamCodec<>() {
        @Override
        public TimelineSyncPayload decode(FriendlyByteBuf buf) {
            return new TimelineSyncPayload(buf.readLong(), buf.readLong(), buf.readBoolean(),
                    buf.readBoolean(), buf.readBoolean(), buf.readBoolean());
        }

        @Override
        public void encode(FriendlyByteBuf buf, TimelineSyncPayload payload) {
            buf.writeLong(payload.tick());
            buf.writeLong(payload.serverTick());
            buf.writeBoolean(payload.running());
            buf.writeBoolean(payload.showTicks());
            buf.writeBoolean(payload.showHud());
            buf.writeBoolean(payload.individualTime());
        }
    };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
