package com.fl1s.rewritten.timetravel.net;

import com.fl1s.rewritten.timetravel.TimeTravel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record StopDragonSoundPayload() implements CustomPacketPayload {
    public static final StopDragonSoundPayload INSTANCE = new StopDragonSoundPayload();
    public static final CustomPacketPayload.Type<StopDragonSoundPayload> TYPE = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(TimeTravel.MOD_ID, "stop_dragon_sound"));
    public static final StreamCodec<FriendlyByteBuf, StopDragonSoundPayload> CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}