package com.fl1s.rewritten.timetravel.journal;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.entity.EntityContainerJournal;
import com.fl1s.rewritten.timetravel.entity.EntityJournal;
import com.fl1s.rewritten.timetravel.entity.EntityStateJournal;
import com.fl1s.rewritten.timetravel.mob.MobJournal;
import com.fl1s.rewritten.timetravel.timeline.AdvancementTimeline;
import com.fl1s.rewritten.timetravel.timeline.ChatTimeline;
import com.fl1s.rewritten.timetravel.vehicle.VehicleJournal;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Central registry for every timeline journal.
 */
public final class JournalRegistry {

    private static final int MAX_TICK_FAILURES = 5;
    private static final Runnable NOOP = () -> {
    };

    private static final class Entry {
        final String name;
        final Runnable load, save, saveIfDirty, clear, rebuildUpcoming, tick;
        final BiConsumer<Long, Long> onJump;
        final Consumer<Set<Integer>> eraseVisits;
        int tickFailures;
        boolean tickDisabled;

        Entry(String name, Runnable load, Runnable save, Runnable saveIfDirty, Runnable clear, Runnable rebuildUpcoming, Runnable tick, BiConsumer<Long, Long> onJump, Consumer<Set<Integer>> eraseVisits) {
            this.name = name;
            this.load = load;
            this.save = save;
            this.saveIfDirty = saveIfDirty;
            this.clear = clear;
            this.rebuildUpcoming = rebuildUpcoming;
            this.tick = tick;
            this.onJump = onJump;
            this.eraseVisits = eraseVisits;
        }
    }

    private final List<Entry> journals = new ArrayList<>();

    public JournalRegistry() {
        register("blocks", BlockJournal::load, BlockJournal::save, BlockJournal::saveIfDirty, BlockJournal::clear, BlockJournal::rebuildUpcoming, BlockJournal::tick, BlockJournal::onJump, BlockJournal::eraseVisits);
        register("block_entities", BlockEntityJournal::load, BlockEntityJournal::save, BlockEntityJournal::saveIfDirty, BlockEntityJournal::clear, BlockEntityJournal::rebuildUpcoming, BlockEntityJournal::tick, BlockEntityJournal::onJump, BlockEntityJournal::eraseVisits);
        register("entities", EntityJournal::load, EntityJournal::save, EntityJournal::saveIfDirty, EntityJournal::clear, EntityJournal::rebuildUpcoming, EntityJournal::tick, EntityJournal::onJump, EntityJournal::eraseVisits);
        register("entity_states", EntityStateJournal::load, EntityStateJournal::save, EntityStateJournal::saveIfDirty, EntityStateJournal::clear, EntityStateJournal::rebuildUpcoming, EntityStateJournal::tick, EntityStateJournal::onJump, EntityStateJournal::eraseVisits);
        register("item_flow", ItemFlowJournal::load, ItemFlowJournal::save, ItemFlowJournal::saveIfDirty, ItemFlowJournal::clear, ItemFlowJournal::rebuildUpcoming, ItemFlowJournal::tick, ItemFlowJournal::onJump, ItemFlowJournal::eraseVisits);
        register("mobs", MobJournal::load, MobJournal::save, MobJournal::saveIfDirty, MobJournal::clear, MobJournal::rebuildUpcoming, MobJournal::tick, MobJournal::onJump, MobJournal::eraseVisits);
        register("boss_fights", BossFightJournal::load, BossFightJournal::save, BossFightJournal::saveIfDirty, BossFightJournal::clear, BossFightJournal::rebuildUpcoming, BossFightJournal::tick, BossFightJournal::onJump, BossFightJournal::eraseVisits);
        register("vehicles", VehicleJournal::load, VehicleJournal::save, VehicleJournal::saveIfDirty, VehicleJournal::clear, VehicleJournal::rebuildUpcoming, VehicleJournal::tick, VehicleJournal::onJump, VehicleJournal::eraseVisits);
        register("entity_containers", EntityContainerJournal::load, EntityContainerJournal::save, EntityContainerJournal::saveIfDirty, EntityContainerJournal::clear, EntityContainerJournal::rebuildUpcoming, EntityContainerJournal::tick, EntityContainerJournal::onJump, EntityContainerJournal::eraseVisits);
        register("processes", ProcessJournal::load, ProcessJournal::save, ProcessJournal::saveIfDirty, ProcessJournal::clear, ProcessJournal::rebuildUpcoming, ProcessJournal::tick, ProcessJournal::onJump, ProcessJournal::eraseVisits);
        register("chat", ChatTimeline::load, ChatTimeline::save, ChatTimeline::saveIfDirty, ChatTimeline::clear, ChatTimeline::rebuildUpcoming, ChatTimeline::tick, ChatTimeline::onJump, ChatTimeline::eraseVisits);
        register("advancements", AdvancementTimeline::load, AdvancementTimeline::save, AdvancementTimeline::saveIfDirty, AdvancementTimeline::clear, AdvancementTimeline::rebuildUpcoming, AdvancementTimeline::tick, AdvancementTimeline::onJump, AdvancementTimeline::eraseVisits);
        register("effects", EffectJournal::load, EffectJournal::save, EffectJournal::saveIfDirty, EffectJournal::clear, EffectJournal::rebuildUpcoming, EffectJournal::tick, EffectJournal::onJump, EffectJournal::eraseVisits);
        register("anims", AnimJournal::load, AnimJournal::save, AnimJournal::saveIfDirty, AnimJournal::clear, AnimJournal::rebuildUpcoming, AnimJournal::tick, AnimJournal::onJump, AnimJournal::eraseVisits);
        register("attacks", AttackJournal::load, AttackJournal::save, AttackJournal::saveIfDirty, AttackJournal::clear, AttackJournal::rebuildUpcoming, AttackJournal::tick, AttackJournal::onJump, AttackJournal::eraseVisits);
        register("projectiles", ProjectileJournal::load, ProjectileJournal::save, ProjectileJournal::saveIfDirty, ProjectileJournal::clear, ProjectileJournal::rebuildUpcoming, ProjectileJournal::tick, ProjectileJournal::onJump, ProjectileJournal::eraseVisits);
        register("tracked_entities", TrackedEntityJournal::load, TrackedEntityJournal::save, TrackedEntityJournal::saveIfDirty, TrackedEntityJournal::clear, TrackedEntityJournal::rebuildUpcoming, TrackedEntityJournal::tick, TrackedEntityJournal::onJump, TrackedEntityJournal::eraseVisits);
        register("particles", ParticleJournal::load, ParticleJournal::save, ParticleJournal::saveIfDirty, ParticleJournal::clear, ParticleJournal::rebuildUpcoming, ParticleJournal::tick, ParticleJournal::onJump, ParticleJournal::eraseVisits);
        register("weather", WeatherJournal::load, WeatherJournal::save, WeatherJournal::saveIfDirty, WeatherJournal::clear, WeatherJournal::rebuildUpcoming, WeatherJournal::tick, WeatherJournal::onJump, WeatherJournal::eraseVisits);
        register("client_fx", NOOP, NOOP, NOOP, NOOP, NOOP, NOOP, (from, to) -> TimeTravel.broadcastStopDragonSound(), ids -> {
        });
    }

    public void register(String name, Runnable load, Runnable save, Runnable saveIfDirty, Runnable clear, Runnable rebuildUpcoming, Runnable tick, BiConsumer<Long, Long> onJump, Consumer<Set<Integer>> eraseVisits) {
        journals.add(new Entry(name, load, save, saveIfDirty, clear, rebuildUpcoming, tick, onJump, eraseVisits));
    }

    private static void guarded(Entry e, String phase, Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            TimeTravel.TIMELINE_LOGGER.error("Journal '{}' failed in {}", e.name, phase, t);
        }
    }

    public void loadAll() {
        for (Entry e : journals) guarded(e, "load", e.load);
    }

    public void saveAll() {
        for (Entry e : journals) guarded(e, "save", e.save);
    }

    public void saveIfDirtyAll() {
        for (Entry e : journals) guarded(e, "saveIfDirty", e.saveIfDirty);
    }

    public void clearAll() {
        for (Entry e : journals) guarded(e, "clear", e.clear);
    }

    public void rebuildUpcomingAll() {
        for (Entry e : journals) guarded(e, "rebuildUpcoming", e.rebuildUpcoming);
    }

    public void tickAll() {
        for (Entry e : journals) {
            if (e.tickDisabled) continue;
            try {
                e.tick.run();
                e.tickFailures = 0;
            } catch (Throwable t) {
                TimeTravel.TIMELINE_LOGGER.error("Journal '{}' tick failed", e.name, t);
                if (++e.tickFailures >= MAX_TICK_FAILURES) {
                    e.tickDisabled = true;
                    TimeTravel.TIMELINE_LOGGER.error("Journal '{}' disabled until restart ({} consecutive failures)", e.name, MAX_TICK_FAILURES);
                }
            }
        }
    }

    public void onJumpAll(long from, long to) {
        for (Entry e : journals) guarded(e, "onJump", () -> e.onJump.accept(from, to));
    }

    public void eraseVisitsAll(Set<Integer> visitIds) {
        for (Entry e : journals) guarded(e, "eraseVisits", () -> e.eraseVisits.accept(visitIds));
    }
}
