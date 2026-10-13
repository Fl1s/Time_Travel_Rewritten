package com.fl1s.rewritten.timetravel.journal;

import com.fl1s.rewritten.timetravel.TimeTravel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.end.EndDragonFight;

import java.util.Set;

public final class EnderDragonFightJournal extends PersistentDataJournal {

    private static final String ID = "dragon";
    private static final EnderDragonFightJournal INSTANCE = new EnderDragonFightJournal();

    private EnderDragonFightJournal() {
        super("timetravel_rewritten_dragon.json");
    }

    public static EnderDragonFightJournal get() {
        return INSTANCE;
    }

    @Override
    protected Set<String> objectIds() {
        return Set.of(ID);
    }

    @Override
    protected CompoundTag capture(String id) {
        ServerLevel end = TimeTravel.server == null ? null : TimeTravel.server.getLevel(Level.END);
        if (end == null) return null;
        EndDragonFight fight = end.getDragonFight();
        if (fight == null) return null;
        return (CompoundTag) EndDragonFight.Data.CODEC.encodeStart(NbtOps.INSTANCE, fight.saveData()).getOrThrow();
    }

    @Override
    protected void apply(String id, CompoundTag nbt) {
        ServerLevel end = TimeTravel.server == null ? null : TimeTravel.server.getLevel(Level.END);
        if (end == null) return;
        EndDragonFight.Data data = EndDragonFight.Data.CODEC.parse(NbtOps.INSTANCE, nbt).getOrThrow();
        EndDragonFight restored = new EndDragonFight(end, end.getSeed(), data);
        end.setDragonFight(restored);
    }
}
