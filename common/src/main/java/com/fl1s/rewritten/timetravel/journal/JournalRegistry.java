package com.fl1s.rewritten.timetravel.journal;

import com.fl1s.rewritten.timetravel.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Central registry for every timeline journal.
 */
public final class JournalRegistry {

    private record Entry(String name, Runnable load, Runnable save, Runnable saveIfDirty, Runnable clear,
                         Runnable rebuildUpcoming, Runnable tick, BiConsumer<Long, Long> onJump,
                         Consumer<Set<Integer>> eraseVisits) {
    }

    private final List<Entry> journals = new ArrayList<>();

    public JournalRegistry() {
        register("blocks", BlockJournal::load, BlockJournal::save, BlockJournal::saveIfDirty, BlockJournal::clear, BlockJournal::rebuildUpcoming, BlockJournal::tick, BlockJournal::onJump, BlockJournal::eraseVisits);
        register("block_entities", BlockEntityJournal::load, BlockEntityJournal::save, BlockEntityJournal::saveIfDirty, BlockEntityJournal::clear, BlockEntityJournal::rebuildUpcoming, BlockEntityJournal::tick, BlockEntityJournal::onJump, BlockEntityJournal::eraseVisits);
        register("entities", EntityJournal::load, EntityJournal::save, EntityJournal::saveIfDirty, EntityJournal::clear, EntityJournal::rebuildUpcoming, EntityJournal::tick, EntityJournal::onJump, EntityJournal::eraseVisits);
        register("entity_states", EntityStateJournal::load, EntityStateJournal::save, EntityStateJournal::saveIfDirty, EntityStateJournal::clear, EntityStateJournal::rebuildUpcoming, EntityStateJournal::tick, EntityStateJournal::onJump, EntityStateJournal::eraseVisits);
        register("item_flow", ItemFlowJournal::load, ItemFlowJournal::save, ItemFlowJournal::saveIfDirty, ItemFlowJournal::clear, ItemFlowJournal::rebuildUpcoming, ItemFlowJournal::tick, ItemFlowJournal::onJump, ItemFlowJournal::eraseVisits);
        register("mobs", MobJournal::load, MobJournal::save, MobJournal::saveIfDirty, MobJournal::clear, MobJournal::rebuildUpcoming, MobJournal::tick, MobJournal::onJump, MobJournal::eraseVisits);
        register("vehicles", VehicleSpawnJournal::load, VehicleSpawnJournal::save, VehicleSpawnJournal::saveIfDirty, VehicleSpawnJournal::clear, VehicleSpawnJournal::rebuildUpcoming, VehicleJournal::tick, VehicleJournal::onJump, VehicleSpawnJournal::eraseVisits);
        register("entity_containers", EntityContainerJournal::load, EntityContainerJournal::save, EntityContainerJournal::saveIfDirty, EntityContainerJournal::clear, EntityContainerJournal::rebuildUpcoming, EntityContainerJournal::tick, EntityContainerJournal::onJump, EntityContainerJournal::eraseVisits);
        register("vehicle_containers", VehicleContainerJournal::load, VehicleContainerJournal::save, VehicleContainerJournal::saveIfDirty, VehicleContainerJournal::clear, VehicleContainerJournal::rebuildUpcoming, VehicleContainerJournal::tick, VehicleContainerJournal::onJump, VehicleContainerJournal::eraseVisits);
        register("processes", ProcessJournal::load, ProcessJournal::save, ProcessJournal::saveIfDirty, ProcessJournal::clear, ProcessJournal::rebuildUpcoming, ProcessJournal::tick, ProcessJournal::onJump, ProcessJournal::eraseVisits);
        register("chat", ChatTimeline::load, ChatTimeline::save, ChatTimeline::saveIfDirty, ChatTimeline::clear, ChatTimeline::rebuildUpcoming, ChatTimeline::tick, ChatTimeline::onJump, ChatTimeline::eraseVisits);
        register("advancements", AdvancementTimeline::load, AdvancementTimeline::save, AdvancementTimeline::saveIfDirty, AdvancementTimeline::clear, AdvancementTimeline::rebuildUpcoming, AdvancementTimeline::tick, AdvancementTimeline::onJump, AdvancementTimeline::eraseVisits);
        register("effects", EffectJournal::load, EffectJournal::save, EffectJournal::saveIfDirty, EffectJournal::clear, EffectJournal::rebuildUpcoming, EffectJournal::tick, EffectJournal::onJump, EffectJournal::eraseVisits);
    }

    public void register(String name, Runnable load, Runnable save, Runnable saveIfDirty, Runnable clear, Runnable rebuildUpcoming, Runnable tick, BiConsumer<Long, Long> onJump, Consumer<Set<Integer>> eraseVisits) {
        journals.add(new Entry(name, load, save, saveIfDirty, clear, rebuildUpcoming, tick, onJump, eraseVisits));
    }

    public void loadAll() {
        for (Entry e : journals) e.load.run();
    }

    public void saveAll() {
        for (Entry e : journals) e.save.run();
    }

    public void saveIfDirtyAll() {
        for (Entry e : journals) e.saveIfDirty.run();
    }

    public void clearAll() {
        for (Entry e : journals) e.clear.run();
    }

    public void rebuildUpcomingAll() {
        for (Entry e : journals) e.rebuildUpcoming.run();
    }

    public void tickAll() {
        for (Entry e : journals) e.tick.run();
    }

    public void onJumpAll(long from, long to) {
        for (Entry e : journals) e.onJump.accept(from, to);
    }

    public void eraseVisitsAll(Set<Integer> visitIds) {
        for (Entry e : journals) e.eraseVisits.accept(visitIds);
    }
}
