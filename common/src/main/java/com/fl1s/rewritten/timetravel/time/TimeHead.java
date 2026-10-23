package com.fl1s.rewritten.timetravel.time;

import com.fl1s.rewritten.timetravel.TimeTravel;
import net.minecraft.server.level.ServerLevel;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class TimeHead {

    private final long offset;
    private final Set<String> occupants = new LinkedHashSet<>();
    private ServerLevel level;
    private boolean releaseRequested;

    TimeHead(long offset) {
        this.offset = Math.max(0, offset);
    }

    public long offset() {
        return offset;
    }

    public boolean isPresent() {
        return offset == 0;
    }

    public long position() {
        return Math.max(0, TimeTravel.presentTick - offset);
    }

    public ServerLevel level() {
        return level != null ? level : TimeTravel.serverLevel;
    }

    public void setLevel(ServerLevel level) {
        this.level = level;
        this.releaseRequested = false;
    }

    public boolean hasOwnLevel() {
        return level != null;
    }

    public void requestRelease() {
        releaseRequested = true;
    }

    public boolean isReleaseRequested() {
        return releaseRequested;
    }

    public void clearLevel() {
        this.level = null;
        this.releaseRequested = false;
    }

    public Set<String> occupants() {
        return Collections.unmodifiableSet(occupants);
    }

    public boolean isEmpty() {
        return occupants.isEmpty();
    }

    void add(String playerName) {
        occupants.add(playerName);
    }

    void remove(String playerName) {
        occupants.remove(playerName);
    }

    @Override
    public String toString() {
        return "TimeHead[offset=" + offset + ", position=" + position() + ", occupants=" + occupants + "]";
    }
}
