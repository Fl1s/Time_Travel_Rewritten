package com.fl1s.rewritten.timetravel.clone;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.data.Clones;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

public final class HeadVisibility {

    private HeadVisibility() {
    }

    public static boolean visibleTo(Entity entity, ServerPlayer viewer) {
        if (!TimeTravel.individualTime || !TimeTravel.isLooping) return true;
        if (entity == null || viewer == null || entity == viewer) return true;
        if (Clones.is(viewer)) return true;

        long head = HeadInteraction.headOf(entity);
        if (head == HeadInteraction.UNBOUND) return true;
        if (head == TimeTravel.headOffsetOf(viewer)) return true;

        return TravelFx.fading(entity, viewer);
    }
}
