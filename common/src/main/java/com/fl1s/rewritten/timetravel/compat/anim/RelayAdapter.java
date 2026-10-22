package com.fl1s.rewritten.timetravel.compat.anim;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

public interface RelayAdapter {
    ResourceLocation recordId();
    String serialize(CustomPacketPayload payload);
    void replay(String data, int entityId, ServerLevel level);
}
