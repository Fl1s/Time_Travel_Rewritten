package com.fl1s.rewritten.timetravel.clone;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.data.Clones;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

public final class CloneVisibility {

    private CloneVisibility() {
    }

    public static boolean visibleTo(Entity entity, ServerPlayer viewer) {
        if (!TimeTravel.internalTime || !TimeTravel.isLooping) return true;
        if (entity == null || viewer == null) return true;
        if (!Clones.is(entity)) return true;

        long cloneHead = TimeTravel.cloneHeadOf(entity);
        if (cloneHead == CloneManager.NO_HEAD) return true;

        return cloneHead == TimeTravel.headOffsetOf(viewer);
    }
}
