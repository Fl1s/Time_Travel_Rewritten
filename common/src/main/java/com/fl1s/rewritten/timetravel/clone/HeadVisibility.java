package com.fl1s.rewritten.timetravel.clone;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.journal.HeadItemView;
import com.fl1s.rewritten.timetravel.mob.HeadMobView;
import com.fl1s.rewritten.timetravel.vehicle.HeadVehicleView;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

public final class HeadVisibility {

    private HeadVisibility() {
    }

    public static boolean visibleTo(Entity entity, ServerPlayer viewer) {
        if (!TimeTravel.individualTime || !TimeTravel.isLooping) return true;
        if (entity == null || viewer == null || entity == viewer) return true;
        if (Clones.is(viewer)) return true;

        long carried = HeadCarry.headOf(entity);
        if (carried != HeadCarry.NONE) {
            return carried == TimeTravel.headOffsetOf(viewer) || TravelFx.fading(entity, viewer);
        }

        if (HeadMobView.hidden(entity, viewer)) return false;
        if (!HeadItemView.visibleTo(entity, viewer)) return false;
        if (!HeadVehicleView.visibleTo(entity, viewer)) return false;

        long head = HeadInteraction.headOf(entity);
        if (head == HeadInteraction.UNBOUND) return true;
        if (head == TimeTravel.headOffsetOf(viewer)) return true;

        return TravelFx.fading(entity, viewer);
    }
}
