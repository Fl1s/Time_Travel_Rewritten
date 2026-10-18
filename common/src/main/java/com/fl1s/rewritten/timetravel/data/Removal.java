package com.fl1s.rewritten.timetravel.data;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.ContainerEntity;

public final class Removal {
    private Removal() {
    }

    public static void discardWithoutDrops(Entity entity) {
        if (entity instanceof ContainerEntity container) container.clearContent();
        entity.discard();
    }
}
