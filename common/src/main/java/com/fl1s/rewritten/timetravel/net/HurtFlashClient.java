package com.fl1s.rewritten.timetravel.net;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

public final class HurtFlashClient {
    private HurtFlashClient() {
    }

    public static void handle(HurtFlashPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.level == null) return;
            Entity e = mc.level.getEntity(payload.entityId());
            if (e instanceof LivingEntity living) {
                living.hurtTime = payload.duration();
                living.hurtDuration = payload.duration();
            }
        });
    }
}
