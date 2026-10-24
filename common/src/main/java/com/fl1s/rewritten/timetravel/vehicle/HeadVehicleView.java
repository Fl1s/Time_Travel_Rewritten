package com.fl1s.rewritten.timetravel.vehicle;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.clone.CloneManager;
import com.fl1s.rewritten.timetravel.clone.HeadCarry;
import com.fl1s.rewritten.timetravel.clone.HeadTracking;
import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.data.Exempt;
import com.fl1s.rewritten.timetravel.data.Levels;
import com.fl1s.rewritten.timetravel.data.Removal;
import com.fl1s.rewritten.timetravel.mob.MobEventJournal;
import com.fl1s.rewritten.timetravel.time.TimeHead;
import com.fl1s.rewritten.timetravel.time.TimeHeads;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class HeadVehicleView {

    private HeadVehicleView() {
    }

    public static final String TAG = "tt_head_vehicle";
    private static final String COPY_PREFIX = "tt_copy/";
    private static final String DIVERGED = "tt_copy_diverged";
    private static final double RANGE_SQ = 128.0 * 128.0;

    private static final class Copy {
        long head;
        final UUID original;
        final ServerLevel level;
        boolean diverged;
        String contents;

        Copy(long head, UUID original, ServerLevel level) {
            this.head = head;
            this.original = original;
            this.level = level;
        }
    }

    private static final Map<UUID, Copy> copies = new HashMap<>();
    private static final Map<Long, Map<UUID, UUID>> byHead = new HashMap<>();

    private static boolean enabled() {
        return TimeTravel.individualTime && TimeTravel.isLooping;
    }

    public static boolean isCopy(Entity entity) {
        return entity != null && copies.containsKey(entity.getUUID());
    }

    public static boolean isOrphan(Entity entity) {
        return entity.getTags().contains(TAG) && !copies.containsKey(entity.getUUID());
    }

    public static void shiftHeads(long delta) {
        for (Copy copy : copies.values()) copy.head = TimeHeads.shifted(copy.head, delta);
        TimeHeads.shiftKeys(byHead, delta);
    }

    public static boolean visibleTo(Entity entity, ServerPlayer viewer) {
        if (!enabled() || !VehicleJournal.isTracked(entity) || Clones.is(viewer)) return true;
        Copy copy = copies.get(entity.getUUID());
        if (copy != null) return copy.head == TimeTravel.headOffsetOf(viewer);
        if (TimeTravel.headOffsetOf(viewer) <= 0) return true;
        return !VehicleSpawnJournal.isRecorded(entity.getUUID());
    }

    public static UUID originalOf(Entity entity) {
        Copy copy = copies.get(entity.getUUID());
        return copy == null ? entity.getUUID() : copy.original;
    }

    public static Entity vehicleFor(Entity rider, UUID original) {
        long head = TimeTravel.cloneHeadOf(rider);
        if (enabled() && head != CloneManager.NO_HEAD && head > 0) return copyIn(head, original);
        if (!(rider.level() instanceof ServerLevel level)) return null;
        Entity entity = level.getEntity(original);
        return entity == null || entity.isRemoved() ? null : entity;
    }

    public static void diverge(Entity entity) {
        Copy copy = copies.get(entity.getUUID());
        if (copy == null || copy.diverged) return;
        copy.diverged = true;
        entity.addTag(DIVERGED);
        HeadCarry.follow(entity, copy.head);
    }

    public static void tick() {
        if (TimeTravel.server != null && TimeTravel.server.getTickCount() % 40 == 0) sweepOrphans();
        if (!enabled()) {
            if (!copies.isEmpty()) clear();
            return;
        }
        TimeHeads heads = TimeTravel.heads();
        if (heads == null || TimeTravel.server == null) return;

        Set<Long> live = new HashSet<>();
        for (TimeHead head : heads.occupied()) {
            if (head.offset() <= 0 || head.isEmpty()) continue;
            live.add(head.offset());
            sync(head.offset(), head.position());
        }
        Set<Long> held = HeadCarry.heldHeads();
        for (Long head : new ArrayList<>(byHead.keySet())) {
            if (live.contains(head)) continue;
            if (held.contains(head)) dropFollowers(head);
            else release(head);
        }
    }

    private static void dropFollowers(long head) {
        Map<UUID, UUID> mine = byHead.get(head);
        if (mine == null) return;
        for (UUID id : new ArrayList<>(mine.values())) {
            Copy copy = copies.get(id);
            if (copy != null && !copy.diverged) discard(id);
        }
    }

    private static boolean adopt(Entity orphan) {
        if (!enabled() || !orphan.getTags().contains(DIVERGED)) return false;
        if (!(orphan.level() instanceof ServerLevel level)) return false;
        String tag = copyTagOf(orphan);
        if (tag == null) return false;
        String[] parts = tag.substring(COPY_PREFIX.length()).split("/");
        if (parts.length != 2) return false;
        long head;
        UUID original;
        try {
            head = TimeHeads.fromTag(Long.parseLong(parts[0]));
            original = UUID.fromString(parts[1]);
        } catch (IllegalArgumentException ex) {
            return false;
        }
        if (!HeadCarry.heldHeads().contains(head)) {
            Copy ghost = new Copy(head, original, level);
            merge(ghost, orphan, Math.max(0, TimeTravel.presentTick - head));
            return false;
        }
        Map<UUID, UUID> mine = byHead.computeIfAbsent(head, k -> new HashMap<>());
        UUID existing = mine.get(original);
        if (existing != null && !existing.equals(orphan.getUUID())) discard(existing);
        Copy copy = new Copy(head, original, level);
        copy.diverged = true;
        copies.put(orphan.getUUID(), copy);
        mine.put(original, orphan.getUUID());
        HeadTracking.markDirty();
        return true;
    }

    private static String copyTagOf(Entity entity) {
        for (String tag : entity.getTags()) {
            if (tag.startsWith(COPY_PREFIX)) return tag;
        }
        return null;
    }

    private static void retag(Entity entity, long head, UUID original) {
        String old = copyTagOf(entity);
        if (old != null) entity.removeTag(old);
        if (head > 0) entity.addTag(COPY_PREFIX + TimeHeads.toTag(head) + "/" + original);
    }

    private static boolean existsAt(VehicleSpawnJournal.Entry e, long position) {
        if (e.tick > position) return false;
        if (e.endTick >= 0 && e.endTick <= position) return false;
        return !MobEventJournal.hasDeathAtOrBefore(e.uuid, position);
    }

    private static void sync(long head, long position) {
        Map<UUID, UUID> mine = byHead.computeIfAbsent(head, k -> new HashMap<>());
        List<ServerPlayer> viewers = viewersOf(head);

        for (VehicleSpawnJournal.Entry e : VehicleSpawnJournal.recorded()) {
            UUID id = mine.get(e.uuid);
            Copy copy = id == null ? null : copies.get(id);
            Entity entity = copy == null ? null : copy.level.getEntity(id);
            if (copy != null && (entity == null || entity.isRemoved())) {
                forget(id);
                copy = null;
                entity = null;
            }

            if (copy != null && !copy.diverged && hasRider(entity)) diverge(entity);
            if (copy != null && copy.diverged) continue;

            boolean wanted = existsAt(e, position) && near(e, position, viewers);
            if (!wanted) {
                if (entity != null) discard(id);
                continue;
            }
            if (entity == null) entity = spawn(head, e, position);
            if (entity == null) continue;
            follow(entity, e.uuid, position);
            if (entity instanceof Container container && position % 10 == 0) refill(copies.get(entity.getUUID()), container, e.uuid, position);
        }
    }

    private static void refill(Copy copy, Container container, UUID original, long position) {
        if (copy == null) return;
        String items = VehicleContainerJournal.contentsAt(original, position);
        if (items == null || items.equals(copy.contents)) return;
        copy.contents = items;
        VehicleContainerJournal.fill(container, items);
    }

    private static boolean near(VehicleSpawnJournal.Entry e, long position, List<ServerPlayer> viewers) {
        Vec3 at = VehicleTrailJournal.positionAt(e.uuid, position);
        if (at == null) at = new Vec3(e.x, e.y, e.z);
        String dim = e.dim;
        for (ServerPlayer viewer : viewers) {
            if (!Levels.canonicalId(viewer.level()).equals(dim)) continue;
            if (viewer.distanceToSqr(at) <= RANGE_SQ) return true;
        }
        return false;
    }

    private static boolean hasRider(Entity entity) {
        for (Entity passenger : entity.getPassengers()) {
            if (passenger instanceof ServerPlayer && !Clones.is(passenger)) return true;
        }
        return false;
    }

    private static void follow(Entity entity, UUID original, long position) {
        VehicleTrailJournal.Sample s = VehicleTrailJournal.sampleOf(original, position);
        if (s == null) return;
        entity.setDeltaMovement(Vec3.ZERO);
        entity.moveTo(s.x, s.y, s.z, s.yRot, s.xRot);
        entity.setDeltaMovement(s.deltaX, s.deltaY, s.deltaZ);
    }

    private static Entity spawn(long head, VehicleSpawnJournal.Entry e, long position) {
        if (TimeTravel.server == null) return null;
        ServerLevel level = TimeTravel.server.getLevel(Levels.key(e.dim));
        if (level == null) return null;
        Vec3 at = VehicleTrailJournal.positionAt(e.uuid, position);
        if (at == null) at = new Vec3(e.x, e.y, e.z);
        if (!level.hasChunkAt(BlockPos.containing(at))) return null;

        UUID id = UUID.nameUUIDFromBytes(("vehicle/" + TimeHeads.toTag(head) + "/" + e.uuid).getBytes(StandardCharsets.UTF_8));
        Entity stale = level.getEntity(id);
        if (stale != null && isOrphan(stale)) {
            if (adopt(stale)) return null;
            Removal.discardWithoutDrops(stale);
        } else if (stale != null) {
            id = UUID.randomUUID();
        }

        try {
            CompoundTag tag = TagParser.parseTag(e.nbt);
            tag.remove("Passengers");
            Exempt.strip(tag);
            Entity entity = EntityType.loadEntityRecursive(tag, level, en -> en);
            if (entity == null || Exempt.is(entity)) return null;
            entity.setUUID(id);
            entity.addTag(TAG);
            retag(entity, head, e.uuid);
            entity.moveTo(at.x, at.y, at.z, entity.getYRot(), entity.getXRot());
            entity.setDeltaMovement(Vec3.ZERO);

            Copy copy = new Copy(head, e.uuid, level);
            copies.put(id, copy);
            byHead.computeIfAbsent(head, k -> new HashMap<>()).put(e.uuid, id);
            if (entity instanceof Container container) refill(copy, container, e.uuid, position);
            if (!level.addFreshEntity(entity)) {
                forget(id);
                return null;
            }
            return entity;
        } catch (Exception ex) {
            TimeTravel.TIMELINE_LOGGER.error("Failed to copy vehicle {} into head {}", e.uuid, head, ex);
            return null;
        }
    }

    public static long headOf(Entity entity) {
        Copy copy = entity == null ? null : copies.get(entity.getUUID());
        return copy == null ? HeadCarry.NONE : copy.head;
    }

    public static boolean reseat(ServerPlayer player, Entity mount, long toOffset) {
        Copy from = copies.get(mount.getUUID());
        if (from == null && !VehicleSpawnJournal.isRecorded(mount.getUUID())) return false;
        UUID original = from != null ? from.original : mount.getUUID();
        Entity target = toOffset <= 0 ? present(original) : copyIn(toOffset, original);
        if (target == null || target == mount) return false;

        player.stopRiding();
        if (target.level() != mount.level() || !target.getPassengers().isEmpty()) return true;
        target.moveTo(mount.getX(), mount.getY(), mount.getZ(), mount.getYRot(), mount.getXRot());
        target.setDeltaMovement(Vec3.ZERO);
        diverge(target);
        player.startRiding(target, true);
        return true;
    }

    public static void rehome(Entity mount, long toOffset) {
        UUID id = mount.getUUID();
        Copy copy = copies.get(id);
        if (copy == null) return;
        forget(id);
        if (toOffset > 0) {
            copy.head = toOffset;
            copy.diverged = true;
            copies.put(id, copy);
            byHead.computeIfAbsent(toOffset, k -> new HashMap<>()).put(copy.original, id);
            mount.addTag(DIVERGED);
            retag(mount, toOffset, copy.original);
        } else {
            mount.removeTag(TAG);
            mount.removeTag(DIVERGED);
            retag(mount, 0, copy.original);
        }
        HeadCarry.follow(mount, toOffset);
        HeadTracking.markDirty();
    }

    private static Entity present(UUID original) {
        if (TimeTravel.server == null) return null;
        for (ServerLevel level : TimeTravel.server.getAllLevels()) {
            Entity entity = level.getEntity(original);
            if (entity != null && !entity.isRemoved()) return entity;
        }
        return null;
    }

    private static Entity copyIn(long head, UUID original) {
        long position = Math.max(0, TimeTravel.presentTick - head);
        Map<UUID, UUID> mine = byHead.get(head);
        UUID id = mine == null ? null : mine.get(original);
        if (id != null) {
            Copy copy = copies.get(id);
            Entity entity = copy == null ? null : copy.level.getEntity(id);
            if (entity != null && !entity.isRemoved()) return entity;
            forget(id);
        }
        for (VehicleSpawnJournal.Entry e : VehicleSpawnJournal.recorded()) {
            if (!e.uuid.equals(original)) continue;
            return existsAt(e, position) ? spawn(head, e, position) : null;
        }
        return null;
    }

    private static void release(long head) {
        Map<UUID, UUID> mine = byHead.remove(head);
        if (mine == null) return;
        long position = Math.max(0, TimeTravel.presentTick - head);
        for (UUID id : new ArrayList<>(mine.values())) {
            Copy copy = copies.get(id);
            Entity entity = copy == null ? null : copy.level.getEntity(id);
            if (copy != null && copy.diverged && entity != null) merge(copy, entity, position);
            discard(id);
        }
    }

    private static void merge(Copy copy, Entity entity, long position) {
        if (VehicleTrailJournal.hasFutureSamples(copy.original, position)) return;
        Entity real = copy.level.getEntity(copy.original);
        if (real == null || real.isRemoved() || !real.getPassengers().isEmpty()) return;
        real.moveTo(entity.getX(), entity.getY(), entity.getZ(), entity.getYRot(), entity.getXRot());
        real.setDeltaMovement(Vec3.ZERO);
    }

    private static void sweepOrphans() {
        for (ServerLevel level : TimeTravel.server.getAllLevels()) {
            List<Entity> orphans = new ArrayList<>();
            for (Entity entity : level.getAllEntities()) {
                if (VehicleJournal.isTracked(entity) && isOrphan(entity) && !adopt(entity)) orphans.add(entity);
            }
            for (Entity orphan : orphans) {
                orphan.ejectPassengers();
                Removal.discardWithoutDrops(orphan);
            }
        }
    }

    public static void forgetOriginal(UUID original) {
        for (Map.Entry<UUID, Copy> entry : new ArrayList<>(copies.entrySet())) {
            if (entry.getValue().original.equals(original)) discard(entry.getKey());
        }
    }

    public static void clear() {
        for (UUID id : new ArrayList<>(copies.keySet())) discard(id);
        copies.clear();
        byHead.clear();
    }

    private static void discard(UUID id) {
        Copy copy = copies.get(id);
        if (copy == null) return;
        Entity entity = copy.level.getEntity(id);
        forget(id);
        if (entity != null && !entity.isRemoved()) {
            entity.ejectPassengers();
            Removal.discardWithoutDrops(entity);
        }
    }

    private static void forget(UUID id) {
        HeadCarry.unfollow(id);
        Copy copy = copies.remove(id);
        if (copy == null) return;
        Map<UUID, UUID> mine = byHead.get(copy.head);
        if (mine != null) mine.remove(copy.original);
    }

    private static List<ServerPlayer> viewersOf(long head) {
        List<ServerPlayer> out = new ArrayList<>();
        if (TimeTravel.server == null) return out;
        for (ServerPlayer player : TimeTravel.server.getPlayerList().getPlayers()) {
            if (Clones.is(player)) continue;
            if (TimeTravel.headOffsetOf(player) == head) out.add(player);
        }
        return out;
    }
}
