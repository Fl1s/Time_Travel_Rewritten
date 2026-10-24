package com.fl1s.rewritten.timetravel.data;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.ContainerEntity;

public final class Removal {
    private Removal() {
    }
    private static int silent;
    public static boolean isSilent() { return silent > 0; }

    public static void discardWithoutDrops(Entity entity) {
        silent++;
        try {
            Exempt.eject(entity);
            if (entity instanceof ContainerEntity container) container.clearContent();
            entity.discard();
        } finally {
            silent--;
        }
    }
}
