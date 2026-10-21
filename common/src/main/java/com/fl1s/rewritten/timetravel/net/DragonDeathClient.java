package com.fl1s.rewritten.timetravel.net;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;

public final class DragonDeathClient {
    private DragonDeathClient() {}

    public static void handle(DragonDeathPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.level == null) return;
            if (mc.level.getEntity(payload.entityId()) instanceof EnderDragon dragon) {
                dragon.dragonDeathTime = payload.deathTime();
            }
        });
    }
    public static void stopDeathSound() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.getSoundManager().stop(SoundEvents.ENDER_DRAGON_DEATH.getLocation(), null));
    }
}
