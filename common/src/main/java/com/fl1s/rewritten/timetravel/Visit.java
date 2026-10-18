package com.fl1s.rewritten.timetravel;

import java.util.ArrayList;
import java.util.List;

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
    public transient boolean wasDead;
    public transient boolean doomed;
    public transient int lastMode = -1;
    public transient boolean lastFlying;
    public transient String lastVehicle;
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
}
