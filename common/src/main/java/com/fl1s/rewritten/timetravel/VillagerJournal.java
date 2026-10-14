package com.fl1s.rewritten.timetravel;

import com.fl1s.rewritten.timetravel.mixin.MerchantMenuMixin;
import com.fl1s.rewritten.timetravel.util.Ghosts;
import com.fl1s.rewritten.timetravel.util.JsonStore;
import com.fl1s.rewritten.timetravel.util.Levels;
import com.google.gson.reflect.TypeToken;
import com.mojang.serialization.DynamicOps;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class VillagerJournal {
    private VillagerJournal() {}

    private static final long NAV_TICKS = 400;
    private static final long GLIDE_TICKS = 10;
    private static final double MAX_SPEED_MOD = 3.0;

    public static final class Interaction {
        public long tickStart;
        public long tickEnd = -1;
        public long seq;
        public int visit = -1;
        public String dim;
        public UUID uuid;
        public double x, y, z;
        public float yaw, pitch;
        public String nbtOpen;
        public String nbtClose;
    }

    private static final Type LIST_TYPE = new TypeToken<ArrayList<Interaction>>() {}.getType();

    private static final List<Interaction> interactions = new ArrayList<>();
    private static final Map<UUID, Interaction> openByPlayer = new HashMap<>();
    private static final Map<UUID, Long> frozenSeq = new HashMap<>();
    private static long nextSeq = 0;
    private static boolean dirty = false;

    private static DynamicOps<Tag> ops() {
        return RegistryOps.create(NbtOps.INSTANCE, TimeTravel.server.registryAccess());
    }

    private static Path file() {
        return TimeTravel.worldFolder.resolve("timetravel_rewritten_villager_trades.json");
    }

    /* ===================== TICK ===================== */

    public static void tick() {
        if (!TimeTravel.isLooping || TimeTravel.server == null) return;
        pollTrades();
        steerToUpcoming();
        applyFrozen();
    }

    private static void pollTrades() {
        Iterator<Map.Entry<UUID, Interaction>> it = openByPlayer.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Interaction> en = it.next();
            UUID pu = en.getKey();
            ServerPlayer p = TimeTravel.server.getPlayerList().getPlayer(pu);
            Villager current = null;
            boolean stillOpen = false;
            if (p != null && p.containerMenu instanceof MerchantMenu mm) {
                Merchant trader = ((MerchantMenuMixin) mm).tt$getTrader();
                if (trader instanceof Villager v) {
                    current = v;
                    stillOpen = v.getUUID().equals(en.getValue().uuid);
                }
            }
            if (!stillOpen) {
                finalizeInteraction(en.getValue(), current);
                it.remove();
            }
        }

        for (ServerPlayer p : TimeTravel.server.getPlayerList().getPlayers()) {
            if (Ghosts.is(p)) continue;
            if (openByPlayer.containsKey(p.getUUID())) continue;
            if (!(p.containerMenu instanceof MerchantMenu mm)) continue;
            Merchant trader = ((MerchantMenuMixin) mm).tt$getTrader();
            if (!(trader instanceof Villager v)) continue;

            PlayerData pd = TimeTravel.loopSceneManager.getRecordingPlayer(p.getName().getString());
            if (pd == null || !pd.getActive() || pd.getCurrentVisit() == null) continue;

            Interaction it2 = new Interaction();
            it2.tickStart = TimeTravel.timelineTick;
            it2.seq = nextSeq++;
            it2.visit = pd.getCurrentVisit().id;
            it2.dim = v.level().dimension().location().toString();
            it2.uuid = v.getUUID();
            it2.x = v.getX();
            it2.y = v.getY();
            it2.z = v.getZ();
            it2.yaw = v.getYRot();
            it2.pitch = v.getXRot();
            it2.nbtOpen = buildPayloadString(v);
            interactions.add(it2);
            openByPlayer.put(p.getUUID(), it2);
            dirty = true;
        }
    }

    private static void finalizeInteraction(Interaction it, Villager current) {
        it.tickEnd = TimeTravel.timelineTick;
        if (current != null && current.getUUID().equals(it.uuid)) {
            it.nbtClose = buildPayloadString(current);
        } else if (it.nbtClose == null) {
            it.nbtClose = it.nbtOpen;
        }
        dirty = true;
    }

    private static void steerToUpcoming() {
        long now = TimeTravel.timelineTick;
        Map<UUID, Interaction> nextTrade = new HashMap<>();
        for (Interaction it : interactions) {
            if (it.tickEnd < 0) continue;
            if (it.tickStart <= now) continue;
            Interaction prev = nextTrade.get(it.uuid);
            if (prev == null || it.tickStart < prev.tickStart) nextTrade.put(it.uuid, it);
        }

        for (Interaction it : nextTrade.values()) {
            long remaining = it.tickStart - now;
            if (remaining > NAV_TICKS) continue;

            ServerLevel level = TimeTravel.server.getLevel(Levels.key(it.dim));
            if (level == null) continue;
            Entity e = level.getEntity(it.uuid);
            if (!(e instanceof Villager v) || v.isRemoved()) continue;

            if (isFrozen(v.getUUID())) continue;

            Vec3 pos = v.position();
            Vec3 target = new Vec3(it.x, it.y, it.z);
            double dist = pos.distanceTo(target);
            if (dist < 1.5) continue;

            if (remaining <= GLIDE_TICKS) {
                v.getNavigation().stop();
                Vec3 next = pos.add(target.subtract(pos).scale(1.0 / remaining));
                v.moveTo(next.x, next.y, next.z, v.getYRot(), v.getXRot());
                v.setDeltaMovement(Vec3.ZERO);
                v.hurtMarked = true;
                continue;
            }

            double base = Math.max(0.05, v.getAttributeValue(Attributes.MOVEMENT_SPEED));
            double needed = dist / (remaining - GLIDE_TICKS);
            double modifier = needed / base;
            v.getNavigation().moveTo(it.x, it.y, it.z,
                    Math.min(MAX_SPEED_MOD, Math.max(0.4, modifier)));
        }
    }

    private static boolean isFrozen(UUID uuid) {
        return frozenSeq.containsKey(uuid);
    }

    private static void applyFrozen() {
        long now = TimeTravel.timelineTick;
        Map<UUID, Interaction> activeNow = new HashMap<>();
        for (Interaction it : interactions) {
            if (it.tickEnd < 0) continue;
            if (now >= it.tickStart && now <= it.tickEnd) activeNow.put(it.uuid, it);
        }

        for (Interaction it : activeNow.values()) {
            Long last = frozenSeq.get(it.uuid);
            if (last == null || last != it.seq) {
                applyState(it.uuid, it.dim, it.nbtOpen);
                frozenSeq.put(it.uuid, it.seq);
            }
            holdPosition(it);
        }

        Iterator<Map.Entry<UUID, Long>> iter = frozenSeq.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<UUID, Long> en = iter.next();
            if (activeNow.containsKey(en.getKey())) continue;
            Interaction closed = findBySeq(en.getValue());
            if (closed != null) applyState(closed.uuid, closed.dim, closed.nbtClose);
            iter.remove();
        }
    }

    private static void holdPosition(Interaction it) {
        if (TimeTravel.server == null) return;
        ServerLevel level = TimeTravel.server.getLevel(Levels.key(it.dim));
        if (level == null) return;
        Entity e = level.getEntity(it.uuid);
        if (!(e instanceof Villager v) || v.isRemoved()) return;
        v.setPos(it.x, it.y, it.z);
        v.setYRot(it.yaw);
        v.setXRot(it.pitch);
        v.setYHeadRot(it.yaw);
        v.setYBodyRot(it.yaw);
        v.setDeltaMovement(0, 0, 0);
    }

    private static void applyState(UUID uuid, String dim, String nbt) {
        if (nbt == null || TimeTravel.server == null) return;
        try {
            ServerLevel level = TimeTravel.server.getLevel(Levels.key(dim));
            if (level == null) return;
            Entity e = level.getEntity(uuid);
            if (!(e instanceof Villager v) || v.isRemoved()) return;

            CompoundTag payload = TagParser.parseTag(nbt);
            DynamicOps<Tag> ops = ops();
            VillagerData data = VillagerData.CODEC.parse(ops, payload.get("VillagerData")).getOrThrow();
            v.setVillagerData(data);
            v.setVillagerXp(payload.getInt("Xp"));
            v.setHealth(payload.getFloat("Health"));
            MerchantOffers offers = MerchantOffers.CODEC.parse(ops, payload.get("Offers")).getOrThrow();
            v.overrideOffers(offers);
            refreshMerchantGui(v);
        } catch (Exception ex) {
            TimeTravel.LOOP_LOGGER.error("Failed to apply villager state {}", uuid, ex);
        }
    }

    private static void refreshMerchantGui(Villager villager) {
        if (TimeTravel.server == null) return;
        for (ServerPlayer p : TimeTravel.server.getPlayerList().getPlayers()) {
            if (!(p.containerMenu instanceof MerchantMenu mm)) continue;
            if (((MerchantMenuMixin) mm).tt$getTrader() != villager) continue;
            p.sendMerchantOffers(
                    mm.containerId,
                    villager.getOffers(),
                    villager.getVillagerData().getLevel(),
                    villager.getVillagerXp(),
                    villager.showProgressBar(),
                    villager.canRestock());
        }
    }

    /* ===================== JUMP ===================== */

    public static void onJump(long from, long to) {
        for (Interaction it : new ArrayList<>(openByPlayer.values())) finalizeInteraction(it, null);
        openByPlayer.clear();
        frozenSeq.clear();

        Set<UUID> all = new HashSet<>();
        for (Interaction it : interactions) all.add(it.uuid);

        for (UUID id : all) {
            Interaction activeAt = null;
            Interaction latestPast = null;
            for (Interaction it : interactions) {
                if (!it.uuid.equals(id)) continue;
                if (it.tickEnd < 0) continue;
                if (to >= it.tickStart && to <= it.tickEnd) activeAt = it;
                if (it.tickEnd <= to) {
                    if (latestPast == null || it.tickEnd > latestPast.tickEnd) latestPast = it;
                }
            }
            if (activeAt != null) {
                applyState(activeAt.uuid, activeAt.dim, activeAt.nbtOpen);
                holdPosition(activeAt);
                frozenSeq.put(activeAt.uuid, activeAt.seq);
            } else if (latestPast != null) {
                applyState(latestPast.uuid, latestPast.dim, latestPast.nbtClose);
            }
        }
    }

    /* ===================== PERSISTENCE ===================== */

    public static void load() {
        interactions.clear();
        openByPlayer.clear();
        frozenSeq.clear();
        nextSeq = 0;
        if (TimeTravel.worldFolder != null) {
            List<Interaction> loaded = JsonStore.load(file(), LIST_TYPE);
            if (loaded != null) {
                interactions.addAll(loaded);
                for (Interaction it : loaded) nextSeq = Math.max(nextSeq, it.seq + 1);
            }
        }
        dirty = false;
    }

    public static void save() {
        if (TimeTravel.worldFolder == null) return;
        if (JsonStore.save(file(), interactions, LIST_TYPE)) dirty = false;
    }

    public static void saveIfDirty() { if (dirty) save(); }

    public static void clear() {
        interactions.clear();
        openByPlayer.clear();
        frozenSeq.clear();
        nextSeq = 0;
        dirty = true;
        save();
    }

    public static void eraseVisits(Set<Integer> visitIds) {
        if (interactions.removeIf(it -> visitIds.contains(it.visit))) dirty = true;
    }

    public static void rebuildUpcoming() {
    }

    /* ===================== HELPERS ===================== */

    private static String buildPayloadString(Villager v) {
        CompoundTag tag = new CompoundTag();
        DynamicOps<Tag> ops = ops();
        tag.put("VillagerData", VillagerData.CODEC.encodeStart(ops, v.getVillagerData()).getOrThrow());
        tag.putInt("Xp", v.getVillagerXp());
        tag.putFloat("Health", v.getHealth());
        tag.put("Offers", MerchantOffers.CODEC.encodeStart(ops, v.getOffers()).getOrThrow());
        return tag.toString();
    }

    private static Interaction findBySeq(long seq) {
        for (Interaction it : interactions) if (it.seq == seq) return it;
        return null;
    }
}
