package com.fl1s.rewritten.timetravel.clone;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.data.Clones;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

public final class HeadInteraction {

    private HeadInteraction() {
    }

    public static boolean allows(Entity a, Entity b) {
        if (!TimeTravel.internalTime || !TimeTravel.isLooping) return true;
        if (a == null || b == null || a == b) return true;

        boolean cloneA = Clones.is(a);
        boolean cloneB = Clones.is(b);
        if (!cloneA && !cloneB) return true;

        long headA = headOf(a, cloneA);
        long headB = headOf(b, cloneB);
        if (headA == UNBOUND || headB == UNBOUND) return true;
        return headA == headB;
    }

    private static final long UNBOUND = Long.MAX_VALUE;

    private static long headOf(Entity entity, boolean isClone) {
        if (isClone) {
            long head = TimeTravel.cloneHeadOf(entity);
            return head == CloneManager.NO_HEAD ? UNBOUND : head;
        }
        if (entity instanceof ServerPlayer player) return TimeTravel.headOffsetOf(player);
        return UNBOUND;
    }
}
