package com.fl1s.rewritten.timetravel.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Visit {
    public int id;
    public long startTick;
    public long endTick = -1;
    public List<Piece> pieces = new ArrayList<>();
    public int arrivalDir;
    public int departDir;
    public String startDim;
    public double sx, sy, sz;
    public String endDim;
    public double ex, ey, ez;
    public long deathTick = -1;
    public List<Long> deathTicks = new ArrayList<>();
    public List<ModeChange> modes = new ArrayList<>();
    public List<RideChange> rides = new ArrayList<>();
    public List<AttackEvent> attacks = new ArrayList<>();
    public List<Long> totems = new ArrayList<>();
    public List<AttrChange> attrs = new ArrayList<>();
    public List<FishEvent> fishing = new ArrayList<>();
    public List<Long> pearls = new ArrayList<>();
    public transient boolean wasDead;
    public transient boolean doomed;
    public transient int lastMode = -1;
    public transient boolean lastFlying;
    public transient String lastVehicle;
    public transient boolean attrInit;
    public transient Map<String, String> attrCache = new HashMap<>();
    public List<HealthChange> healths = new ArrayList<>();
    public float lastHealth = -1.0f;

    public Visit() {}

    public Visit(int id, long startTick) {
        this.id = id;
        this.startTick = startTick;
    }

    public static class Piece {
        public String recording;
        public long startTick;

        public Piece() {}

        public Piece(String recording, long startTick) {
            this.recording = recording;
            this.startTick = startTick;
        }
    }

    public static final class HealthChange {
        public long tick;
        public float health;

        public HealthChange(long tick, float health) {
            this.tick = tick;
            this.health = health;
        }
    }

    public static class ModeChange {
        public long tick;
        public int mode;
        public boolean flying;

        public ModeChange() {}

        public ModeChange(long tick, int mode, boolean flying) {
            this.tick = tick;
            this.mode = mode;
            this.flying = flying;
        }
    }

    public static class RideChange {
        public long tick;
        public String vehicle;

        public RideChange() {}

        public RideChange(long tick, String vehicle) {
            this.tick = tick;
            this.vehicle = vehicle;
        }
    }

    public static class AttackEvent {
        public long tick;
        public String target;

        public AttackEvent() {}

        public AttackEvent(long tick, String target) {
            this.tick = tick;
            this.target = target;
        }
    }

    public static final class AttrChange {
        public long tick;
        public String id;
        public String nbt;

        public AttrChange() {}

        public AttrChange(long tick, String id, String nbt) {
            this.tick = tick;
            this.id = id;
            this.nbt = nbt;
        }
    }

    public static final class FishEvent {
        public long tick;
        public int type;
        public int result;

        public FishEvent() {}

        public FishEvent(long tick, int type, int result) {
            this.tick = tick;
            this.type = type;
            this.result = result;
        }
    }
}
