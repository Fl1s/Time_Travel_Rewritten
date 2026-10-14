package com.fl1s.rewritten.timetravel.journal;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.mixin.EnderDragonFightMixin;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.end.EndDragonFight;

import java.util.Set;

public final class EnderDragonFightJournal extends PersistentDataJournal {

    private static final String ID = "dragon";
    private static final EnderDragonFightJournal INSTANCE = new EnderDragonFightJournal();

    private boolean initialized = false;
    private EndDragonFight.Data lastData;

    private EnderDragonFightJournal() {
        super("timetravel_rewritten_dragon.json");
    }

    public static EnderDragonFightJournal get() { return INSTANCE; }

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
        return (CompoundTag) EndDragonFight.Data.CODEC
                .encodeStart(NbtOps.INSTANCE, fight.saveData())
                .getOrThrow();
    }

    @Override
    protected void apply(String id, CompoundTag nbt) {
        ServerLevel end = TimeTravel.server == null ? null : TimeTravel.server.getLevel(Level.END);
        if (end == null) return;
        CompoundTag modified = nbt.copy();
        modified.putBoolean("NeedsStateScanning", false);
        EndDragonFight.Data data = EndDragonFight.Data.CODEC
                .parse(NbtOps.INSTANCE, modified)
                .getOrThrow();

        EndDragonFight existing = end.getDragonFight();
        if (existing != null && existing.saveData().equals(data)) {
            initialized = false;
            return;
        }

        for (Entity e : end.getAllEntities()) {
            if (e instanceof EnderDragon dragon && !dragon.isRemoved()) {
                dragon.discard();
            }
        }

        if (existing != null) {
            ServerBossEvent oldBar = ((EnderDragonFightMixin) existing).tt$getDragonEvent();
            oldBar.setVisible(false);
            oldBar.removeAllPlayers();
        }

        EndDragonFight restored = new EndDragonFight(end, end.getSeed(), data);
        end.setDragonFight(restored);
        initialized = false;
    }

    @Override
    public void tick() {
        if (!TimeTravel.isLooping) return;
        ServerLevel end = TimeTravel.server == null ? null : TimeTravel.server.getLevel(Level.END);
        if (end == null) return;
        EndDragonFight fight = end.getDragonFight();
        if (fight == null) return;
        EndDragonFight.Data current = fight.saveData();
        if (!initialized) {
            initialized = true;
            lastData = current;
            snapshotOne(ID, -1);
            return;
        }
        if (!current.equals(lastData)) {
            lastData = current;
            snapshotOne(ID, -1);
        }
    }
}
