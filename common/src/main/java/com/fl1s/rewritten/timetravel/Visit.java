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
    public transient boolean wasDead;
    public transient boolean doomed;

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
}
