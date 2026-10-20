package com.fl1s.rewritten.timetravel.mob;

import com.fl1s.rewritten.timetravel.mixin.WardenAccessor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.warden.Warden;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class WardenSupport {
    private WardenSupport() {
    }

    static int poseOf(Mob mob) {
        return mob instanceof Warden w ? w.getPose().ordinal() : -1;
    }

    static int angerOf(Mob mob) {
        return mob instanceof Warden w ? w.getClientAngerLevel() : -1;
    }

    static void applyState(Mob mob, MobTrailJournal.Sample s) {
        if (!(mob instanceof Warden w)) return;
        Pose[] all = Pose.values();
        if (s.pose >= 0 && s.pose < all.length && w.getPose() != all[s.pose]) w.setPose(all[s.pose]);
        if (s.clientAnger >= 0 && w.getClientAngerLevel() != s.clientAnger) {
            w.getEntityData().set(WardenAccessor.tt$clientAngerLevel(), s.clientAnger);
        }
    }

    static void applyFx(Mob mob) {
        if (!(mob instanceof Warden w) || !(w.level() instanceof ServerLevel sl)) return;
        if ((w.tickCount + w.getId()) % 120 == 0) {
            Warden.applyDarknessAround(sl, w.position(), w, 20);
        }
    }

    static void settle(Warden w) {
        Pose p = w.getPose();
        if (p != Pose.EMERGING && p != Pose.DIGGING && p != Pose.ROARING && p != Pose.SNIFFING) return;
        Brain<Warden> b = w.getBrain();
        b.eraseMemory(MemoryModuleType.IS_EMERGING);
        b.eraseMemory(MemoryModuleType.ROAR_TARGET);
        b.eraseMemory(MemoryModuleType.IS_SNIFFING);
        b.setMemoryWithExpiry(MemoryModuleType.DIG_COOLDOWN, Unit.INSTANCE, 1200L);
        w.setPose(Pose.STANDING);
    }

    static void quantizeAnger(CompoundTag out) {
        if (!out.contains("anger", Tag.TAG_COMPOUND)) return;
        CompoundTag anger = out.getCompound("anger");
        ListTag suspects = anger.getList("suspects", Tag.TAG_COMPOUND);
        List<CompoundTag> list = new ArrayList<>();
        for (int i = 0; i < suspects.size(); i++) {
            CompoundTag c = suspects.getCompound(i).copy();
            int q = c.getInt("anger") / 10 * 10;
            if (q <= 0) continue;
            c.putInt("anger", q);
            list.add(c);
        }
        list.sort(Comparator.comparing(t -> String.valueOf(t.get("uuid"))));
        ListTag sorted = new ListTag();
        sorted.addAll(list);
        anger.put("suspects", sorted);
    }
}
