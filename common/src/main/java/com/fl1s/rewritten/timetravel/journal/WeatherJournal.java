package com.fl1s.rewritten.timetravel.journal;

import com.fl1s.rewritten.timetravel.TimeTravel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

import java.util.Set;

public final class WeatherJournal extends PersistentDataJournal {

    private static final String ID = "overworld";
    private static final WeatherJournal INSTANCE = new WeatherJournal();

    private boolean initialized = false;
    private boolean lastRaining;
    private boolean lastThundering;

    private WeatherJournal() {
        super("timetravel_rewritten_weather.json");
    }

    public static WeatherJournal get() { return INSTANCE; }

    @Override
    protected Set<String> objectIds() {
        return Set.of(ID);
    }

    @Override
    protected CompoundTag capture(String id) {
        ServerLevel level = TimeTravel.server == null ? null : TimeTravel.server.overworld();
        if (level == null) return null;
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("raining", level.isRaining());
        tag.putBoolean("thundering", level.isThundering());
        return tag;
    }

    @Override
    protected void apply(String id, CompoundTag nbt) {
        ServerLevel level = TimeTravel.server == null ? null : TimeTravel.server.overworld();
        if (level == null) return;
        boolean raining = nbt.getBoolean("raining");
        boolean thundering = nbt.getBoolean("thundering");
        level.setWeatherParameters(6000, 6000, raining, thundering);
    }

    @Override
    public void tick() {
        if (!TimeTravel.isLooping) return;
        ServerLevel level = TimeTravel.server == null ? null : TimeTravel.server.overworld();
        if (level == null) return;
        boolean raining = level.isRaining();
        boolean thundering = level.isThundering();
        if (!initialized) {
            initialized = true;
            lastRaining = raining;
            lastThundering = thundering;
            snapshotOne(ID, -1);
            return;
        }
        if (raining != lastRaining || thundering != lastThundering) {
            lastRaining = raining;
            lastThundering = thundering;
            snapshotOne(ID, -1);
        }
    }
}
