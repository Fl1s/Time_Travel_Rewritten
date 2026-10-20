package com.fl1s.rewritten.timetravel.net;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.AnimationState;
import net.minecraft.world.entity.monster.warden.Warden;

public final class WardenAnimClient {
    private WardenAnimClient() {}

    public static void handle(WardenAnimPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.level == null) return;
            if (!(mc.level.getEntity(payload.entityId()) instanceof Warden warden)) return;
            AnimationState state = switch (payload.anim()) {
                case WardenAnimPayload.ATTACK -> warden.attackAnimationState;
                case WardenAnimPayload.SONIC_BOOM -> warden.sonicBoomAnimationState;
                case WardenAnimPayload.EMERGE -> warden.emergeAnimationState;
                case WardenAnimPayload.DIG -> warden.diggingAnimationState;
                case WardenAnimPayload.ROAR -> warden.roarAnimationState;
                case WardenAnimPayload.SNIFF -> warden.sniffAnimationState;
                default -> null;
            };
            if (state == null) return;
            if (payload.anim() == WardenAnimPayload.ATTACK) warden.roarAnimationState.stop();
            state.start(warden.tickCount);
            state.fastForward(payload.elapsed(), 1.0F);
        });
    }
}
