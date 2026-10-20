package com.fl1s.rewritten.timetravel.time;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.data.PlayerData;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TimeHeads {

    private final Map<Long, TimeHead> heads = new LinkedHashMap<>();
    private long sharedOffset;

    public TimeHead present() {
        return of(0);
    }

    public TimeHead of(long offset) {
        return heads.computeIfAbsent(Math.max(0, offset), TimeHead::new);
    }

    public long sharedOffset() {
        return sharedOffset;
    }

    public long sharedPosition() {
        return Math.max(0, TimeTravel.presentTick - sharedOffset);
    }

    public void setSharedOffset(long offset) {
        sharedOffset = Math.max(0, offset);
    }

    public TimeHead owning(Level level) {
        if (level == null) return null;
        for (TimeHead head : heads.values()) {
            if (head.hasOwnLevel() && head.level() == level) return head;
        }
        return null;
    }

    public TimeHead headOf(PlayerData pd) {
        if (pd == null) return of(sharedOffset);
        return of(TimeTravel.internalTime ? pd.getTimeOffset() : sharedOffset);
    }

    public long positionOf(PlayerData pd) {
        return headOf(pd).position();
    }

    public void move(PlayerData pd, long newOffset) {
        if (pd == null) return;
        long clamped = Math.max(0, newOffset);
        of(pd.getTimeOffset()).remove(pd.getName());
        pd.setTimeOffset(clamped);
        of(clamped).add(pd.getName());
        prune();
    }

    public void moveAll(long newOffset) {
        setSharedOffset(newOffset);
        if (TimeTravel.timelineSceneManager == null) return;
        TimeTravel.timelineSceneManager.forEachRecordingPlayer(pd -> move(pd, newOffset));
    }

    public void release(PlayerData pd) {
        if (pd == null) return;
        of(pd.getTimeOffset()).remove(pd.getName());
        prune();
    }

    public List<TimeHead> all() {
        return new ArrayList<>(heads.values());
    }

    public List<TimeHead> occupied() {
        List<TimeHead> out = new ArrayList<>();
        for (TimeHead head : heads.values()) {
            if (head.offset() == sharedOffset || !head.isEmpty()) out.add(head);
        }
        out.sort(Comparator.comparingLong(TimeHead::offset));
        return out;
    }

    public int countTravelling() {
        int count = 0;
        for (TimeHead head : heads.values()) {
            if (head.offset() != sharedOffset && !head.isEmpty()) count++;
        }
        return count;
    }

    public void collapseToShared() {
        moveAll(sharedOffset);
    }

    public void resetAll() {
        heads.clear();
        sharedOffset = 0;
        if (TimeTravel.timelineSceneManager != null) {
            TimeTravel.timelineSceneManager.forEachRecordingPlayer(pd -> pd.setTimeOffset(0));
        }
    }

    private void prune() {
        heads.entrySet().removeIf(e -> {
            TimeHead head = e.getValue();
            if (e.getKey() == sharedOffset || !head.isEmpty()) return false;
            if (head.hasOwnLevel()) {
                head.requestRelease();
                return false;
            }
            return true;
        });
    }
}
