package com.fl1s.rewritten.timetravel.clone;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.vehicle.HeadVehicleView;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

public final class HeadInteraction {

    private HeadInteraction() {
    }

    static final long UNBOUND = Long.MAX_VALUE;

    public static boolean allows(Entity a, Entity b) {
        if (!TimeTravel.individualTime || !TimeTravel.isLooping) return true;
        if (a == null || b == null || a == b) return true;
        boolean copyA = HeadVehicleView.isCopy(a), copyB = HeadVehicleView.isCopy(b);
        if ((copyA && !(b instanceof ServerPlayer)) || (copyB && !(a instanceof ServerPlayer))) return false;
        if (a instanceof ServerPlayer pa && !(b instanceof ServerPlayer)) return HeadVisibility.visibleTo(b, pa);
        if (b instanceof ServerPlayer pb && !(a instanceof ServerPlayer)) return HeadVisibility.visibleTo(a, pb);
        if (!(a instanceof ServerPlayer) || !(b instanceof ServerPlayer)) return true;

        long headA = headOf(a);
        long headB = headOf(b);
        if (headA == UNBOUND || headB == UNBOUND) return true;
        return headA == headB;
    }

    static long headOf(Entity entity) {
        if (Clones.is(entity)) {
            long head = TimeTravel.cloneHeadOf(entity);
            return head == CloneManager.NO_HEAD ? UNBOUND : head;
        }
        if (entity instanceof ServerPlayer player) return TimeTravel.headOffsetOf(player);
        return UNBOUND;
    }
}
