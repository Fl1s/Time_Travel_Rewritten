package com.fl1s.rewritten.timetravel.mob;

import com.fl1s.rewritten.timetravel.journal.BlockJournal;
import com.fl1s.rewritten.timetravel.data.PlayerData;
import com.fl1s.rewritten.timetravel.data.ReplayState;
import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.journal.Journal;
import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.data.Levels;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.animal.Fox;
import net.minecraft.world.entity.animal.MushroomCow;
import net.minecraft.world.entity.animal.Panda;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.animal.axolotl.Axolotl;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Strider;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class MobStateJournal {
    private MobStateJournal() {
    }

    private static final String[] SHEEP_KEYS = {"Color", "Sheared"};
    private static final String[] VILLAGER_KEYS = {"VillagerData", "Offers", "Inventory", "Gossips", "Xp", "LastGossipDecay", "LastRestock", "RestocksToday", "FoodLevel"};
    private static final String[] ENDERMAN_KEYS = {"carriedBlockState"};
    private static final String[] INVENTORY_KEYS = {"Inventory", "CannotHunt"};
    private static final String[] FOX_KEYS = {"HandItems", "Type", "Sleeping"};
    private static final String[] HORSE_KEYS = {"Variant", "ChestedHorse", "Items", "SaddleItem", "ArmorItem", "DecorItem", "Tame", "Owner", "Bred", "EatingHaystack", "Love", "Age", "Attributes"};
    private static final String[] PANDA_KEYS = {"MainGene", "HiddenGene", "SneezeCounter", "UnhappyCounter"};
    private static final String[] PET_KEYS = {"variant", "CollarColor", "Tame", "Owner", "Sitting", "Angry"};
    private static final String[] SADDLE_KEYS = {"Saddle"};
    private static final String[] MOOSHROOM_KEYS = {"Type", "StewEffects"};
    private static final String[] AXOLOTL_KEYS = {"Variant", "FromBucket"};
    private static final String[] BEE_KEYS = {"HasNectar", "HasStung", "AngerTime", "HivePos", "FlowerPos"};
    private static final String[] GOAT_KEYS = {"IsScreamingGoat", "HasLeftHorn", "HasRightHorn"};
    private static final String[] FROG_KEYS = {"variant", "FromBucket"};
    private static final String[] WARDEN_KEYS = {"anger"};
    private static final String[] NO_KEYS = {};

    private static final int SAMPLE_STEP = 4;
    private static final int SAMPLE_GROUPS = 5;

    public static final class Entry {
        public String dim;
        public UUID uuid;
        public String nbt;
        public long tick;
        public long seq;
        public int visit;
        transient CompoundTag parsed;
    }

    private static final Journal<Entry> STORE = new Journal<>("timetravel_rewritten_mob_states.json", new TypeToken<ArrayList<Entry>>() {
    }.getType(), e -> e.tick, e -> e.seq, e -> e.visit);

    private static final Map<UUID, List<Entry>> byUuid = new HashMap<>();
    private static final Map<UUID, Long> maxTickByUuid = new HashMap<>();
    private static final Set<UUID> sampledThisTick = new HashSet<>();
    private static boolean applying = false;

    public static boolean isApplying() {
        return applying;
    }

    private static String[] keysFor(Mob mob) {
        if (mob instanceof Warden) return WARDEN_KEYS;
        if (mob instanceof Sheep) return SHEEP_KEYS;
        if (mob instanceof Villager) return VILLAGER_KEYS;
        if (mob instanceof EnderMan) return ENDERMAN_KEYS;
        if (mob instanceof Piglin || mob instanceof Allay) return INVENTORY_KEYS;
        if (mob instanceof Fox) return FOX_KEYS;
        if (mob instanceof AbstractHorse) return HORSE_KEYS;
        if (mob instanceof Panda) return PANDA_KEYS;
        if (mob instanceof Cat || mob instanceof Wolf) return PET_KEYS;
        if (mob instanceof Pig || mob instanceof Strider) return SADDLE_KEYS;
        if (mob instanceof MushroomCow) return MOOSHROOM_KEYS;
        if (mob instanceof Axolotl) return AXOLOTL_KEYS;
        if (mob instanceof Bee) return BEE_KEYS;
        if (mob instanceof Goat) return GOAT_KEYS;
        if (mob instanceof Frog) return FROG_KEYS;
        return NO_KEYS;
    }

    private static ServerLevel levelOf(String dim) {
        return TimeTravel.server.getLevel(Levels.key(dim));
    }

    private static void index(Entry e) {
        byUuid.computeIfAbsent(e.uuid, k -> new ArrayList<>()).add(e);
        Long max = maxTickByUuid.get(e.uuid);
        if (max == null || e.tick > max) maxTickByUuid.put(e.uuid, e.tick);
    }

    private static void rebuildIndex() {
        byUuid.clear();
        maxTickByUuid.clear();
        for (Entry e : STORE.entries()) index(e);
    }

    private static Entry stateAtOf(List<Entry> list, long tick) {
        Entry best = null;
        for (Entry e : list) {
            if (e.tick > tick) continue;
            if (best == null || e.tick > best.tick || (e.tick == best.tick && e.seq > best.seq)) best = e;
        }
        return best;
    }

    private static String signature(Mob mob, String[] keys) {
        CompoundTag full = new CompoundTag();
        mob.saveWithoutId(full);
        CompoundTag out = new CompoundTag();
        for (String k : keys) {
            if (full.contains(k)) out.put(k, full.get(k));
        }
        if (mob instanceof Warden) WardenSupport.quantizeAnger(out);
        return out.toString();
    }

    public static boolean hasFutureChange(UUID id, long now) {
        Long max = maxTickByUuid.get(id);
        if (max == null || max <= now) return false;
        List<Entry> list = byUuid.get(id);
        if (list == null) return false;
        Entry at = stateAtOf(list, now);
        String currentSig = at == null ? null : at.nbt;
        for (Entry e : list) {
            if (e.tick <= now) continue;
            if (currentSig == null || !e.nbt.equals(currentSig)) return true;
        }
        return false;
    }

    private static int visitAt(ServerLevel level, BlockPos pos) {
        PlayerData pd = BlockJournal.nearestRecorder(level, pos);
        return pd != null && pd.getCurrentVisit() != null ? pd.getCurrentVisit().id : -1;
    }

    private static boolean active() {
        if (applying || !TimeTravel.isLooping || ReplayState.SUPPRESS) return false;
        return TimeTravel.timelineSceneManager != null && TimeTravel.server != null;
    }

    private static void record(ServerLevel level, Mob mob, long tick, int visit, String nbt) {
        Entry e = new Entry();
        e.dim = level.dimension().location().toString();
        e.uuid = mob.getUUID();
        e.nbt = nbt;
        e.tick = tick;
        e.seq = STORE.nextSeq();
        e.visit = visit;
        STORE.entries().add(e);
        index(e);
        STORE.markDirty();
    }

    public static void snapshotInitial() {
        if (TimeTravel.server == null) return;
        for (ServerLevel level : TimeTravel.server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof Mob mob) || Clones.is(mob)) continue;
                String[] keys = keysFor(mob);
                if (keys.length == 0 || byUuid.containsKey(mob.getUUID())) continue;
                record(level, mob, -1L, -1, signature(mob, keys));
            }
        }
    }

    public static void onSpawn(ServerLevel level, Entity entity) {
        if (!(entity instanceof Mob mob) || Clones.is(mob)) return;
        if (!active()) return;
        String[] keys = keysFor(mob);
        if (keys.length == 0 || byUuid.containsKey(mob.getUUID())) return;
        long tick = MobSpawnJournal.spawnTick(mob.getUUID());
        record(level, mob, tick, tick < 0 ? -1 : visitAt(level, mob.blockPosition()), signature(mob, keys));
    }

    public static void beforeChange(Mob mob) {
        if (Clones.is(mob) || !active()) return;
        if (!(mob.level() instanceof ServerLevel level)) return;
        if (mob.isRemoved() || mob.isDeadOrDying()) return;
        String[] keys = keysFor(mob);
        if (keys.length == 0 || byUuid.containsKey(mob.getUUID())) return;
        record(level, mob, -1L, -1, signature(mob, keys));
    }

    public static void onChanged(Mob mob) {
        if (Clones.is(mob) || !active()) return;
        if (!(mob.level() instanceof ServerLevel level)) return;
        check(level, mob, TimeTravel.timelineTick);
    }

    private static void check(ServerLevel level, Mob mob, long now) {
        if (mob.isRemoved() || mob.isDeadOrDying()) return;
        String[] keys = keysFor(mob);
        if (keys.length == 0) return;
        UUID id = mob.getUUID();
        String sig = signature(mob, keys);
        List<Entry> list = byUuid.get(id);
        if (list == null) {
            long born = MobSpawnJournal.spawnTick(id);
            record(level, mob, born, born < 0 ? -1 : visitAt(level, mob.blockPosition()), sig);
            return;
        }
        Entry at = stateAtOf(list, now);
        if (at != null && sig.equals(at.nbt)) return;
        record(level, mob, now, visitAt(level, mob.blockPosition()), sig);
    }

    private static void sample() {
        long now = TimeTravel.timelineTick;
        if (now % SAMPLE_STEP != 0 || !active()) return;
        sampledThisTick.clear();
        double r = TimeTravel.entityTrackingDistance;
        double r2 = r * r;

        for (ServerLevel level : TimeTravel.server.getAllLevels()) {
            List<ServerPlayer> viewers = new ArrayList<>(level.players());
            if (viewers.isEmpty()) continue;
            for (ServerPlayer p : viewers) {
                for (Mob mob : level.getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(r))) {
                    if (Clones.is(mob)) continue;
                    if ((mob.getId() + now / SAMPLE_STEP) % SAMPLE_GROUPS != 0) continue;
                    if (keysFor(mob).length == 0) continue;
                    if (!sampledThisTick.add(mob.getUUID())) continue;
                    if (p.distanceToSqr(mob) > r2) continue;
                    check(level, mob, now);
                }
            }
        }
    }

    public static void tick() {
        if (!TimeTravel.isLooping || TimeTravel.server == null) return;
        STORE.tick(MobStateJournal::applyEntry);
        sample();
    }

    private static void applyEntry(Entry e) {
        ServerLevel level = levelOf(e.dim);
        if (level == null) return;
        if (!(level.getEntity(e.uuid) instanceof Mob mob) || mob.isRemoved() || mob.isDeadOrDying()) return;
        String[] keys = keysFor(mob);
        if (keys.length == 0 || signature(mob, keys).equals(e.nbt)) return;
        applying = true;
        try {
            CompoundTag cached = e.parsed;
            if (cached == null) {
                cached = TagParser.parseTag(e.nbt);
                e.parsed = cached;
            }
            CompoundTag tag = new CompoundTag();
            mob.saveWithoutId(tag);
            for (String k : keys) tag.remove(k);
            for (String k : cached.getAllKeys()) tag.put(k, cached.get(k).copy());
            mob.load(tag);
            if (mob instanceof AbstractHorse horse && !cached.contains("SaddleItem")) {
                horse.getSlot(400).set(ItemStack.EMPTY);
            }
            if (mob instanceof TamableAnimal pet && !cached.contains("Owner")) {
                pet.setOwnerUUID(null);
                pet.setTame(false, false);
                pet.setInSittingPose(false);
                pet.setOrderedToSit(false);
            }
        } catch (Exception ex) {
            TimeTravel.TIMELINE_LOGGER.error("Failed to apply mob state entry", ex);
        } finally {
            applying = false;
        }
    }

    public static void onJump(long from, long to) {
        if (TimeTravel.server == null) return;
        long now = TimeTravel.timelineTick;
        for (List<Entry> list : byUuid.values()) {
            if (list.isEmpty()) continue;
            Entry at = stateAtOf(list, now);
            if (at != null) applyEntry(at);
        }
        STORE.rebuildUpcoming();
    }

    public static void eraseVisits(Set<Integer> visitIds) {
        STORE.eraseVisits(visitIds);
        rebuildIndex();
    }

    public static void rebuildUpcoming() {
        STORE.rebuildUpcoming();
    }

    public static void load() {
        STORE.load();
        rebuildIndex();
    }

    public static void save() {
        STORE.save();
    }

    public static void saveIfDirty() {
        STORE.saveIfDirty();
    }

    public static void clear() {
        byUuid.clear();
        maxTickByUuid.clear();
        sampledThisTick.clear();
        STORE.clear();
    }
}
