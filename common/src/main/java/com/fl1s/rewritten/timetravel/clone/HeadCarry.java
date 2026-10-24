package com.fl1s.rewritten.timetravel.clone;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.data.Exempt;
import com.fl1s.rewritten.timetravel.time.TimeHeads;
import com.fl1s.rewritten.timetravel.vehicle.HeadVehicleView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class HeadCarry {

    private HeadCarry() {
    }

    public static final long NONE = Long.MIN_VALUE;
    private static final String CARRY_PREFIX = "tt_carry/";
    private static final String TRIP_PREFIX = "tt_trip/";

    private record Carry(long head, ServerLevel level) {}

    private static final Map<UUID, Carry> carried = new HashMap<>();
    private static final Map<UUID, Carry> trips = new HashMap<>();
    private static final Map<UUID, UUID> tripIds = new HashMap<>();

    private static boolean enabled() {
        return TimeTravel.individualTime && TimeTravel.isLooping;
    }

    public static boolean isCarried(UUID id) {
        return carried.containsKey(id);
    }

    public static void shiftHeads(long delta) {
        carried.replaceAll((id, carry) -> new Carry(TimeHeads.shifted(carry.head(), delta), carry.level()));
        trips.replaceAll((id, carry) -> new Carry(TimeHeads.shifted(carry.head(), delta), carry.level()));
    }

    public static long headOf(Entity entity) {
        if (entity == null || carried.isEmpty()) return NONE;
        Carry carry = carried.get(entity.getUUID());
        return carry == null ? NONE : carry.head();
    }

    public static Set<Long> heldHeads() {
        Set<Long> out = new HashSet<>();
        if (!TimeTravel.individualTime || TimeTravel.timelineSceneManager == null) return out;
        TimeTravel.timelineSceneManager.forEachRecordingPlayer(pd -> {
            if (pd.getTimeOffset() > 0) out.add(pd.getTimeOffset());
        });
        return out;
    }

    private static String tagOf(Entity entity, String prefix) {
        for (String tag : entity.getTags()) {
            if (tag.startsWith(prefix)) return tag;
        }
        return null;
    }

    private static void retag(Entity entity, String prefix, Object value) {
        String old = tagOf(entity, prefix);
        if (old != null) entity.removeTag(old);
        if (value != null) entity.addTag(prefix + value);
    }

    public static UUID rideIdOf(Entity vehicle) {
        UUID trip = tripIds.get(vehicle.getUUID());
        return trip != null ? trip : HeadVehicleView.originalOf(vehicle);
    }

    public static boolean isTripIn(UUID trip, long head) {
        for (Map.Entry<UUID, UUID> entry : tripIds.entrySet()) {
            if (!entry.getValue().equals(trip)) continue;
            Carry carry = trips.get(entry.getKey());
            return carry != null && carry.head() == head;
        }
        return false;
    }

    public static void follow(Entity entity, long head) {
        if (head <= 0 || !(entity.level() instanceof ServerLevel level)) {
            unfollow(entity.getUUID());
            retag(entity, TRIP_PREFIX, null);
            return;
        }
        UUID trip = UUID.randomUUID();
        trips.put(entity.getUUID(), new Carry(head, level));
        tripIds.put(entity.getUUID(), trip);
        retag(entity, TRIP_PREFIX, trip);
        CarryTrailJournal.open(trip, entity);
    }

    public static void forget(Entity entity) {
        if (carried.remove(entity.getUUID()) != null) HeadTracking.markDirty();
        unfollow(entity.getUUID());
        retag(entity, CARRY_PREFIX, null);
        retag(entity, TRIP_PREFIX, null);
    }

    public static void unfollow(UUID entity) {
        trips.remove(entity);
        tripIds.remove(entity);
    }

    public static void onTravel(ServerPlayer player, long fromOffset, long toOffset) {
        if (!enabled() || player == null || fromOffset == toOffset) return;
        Entity mount = player.getVehicle();
        if (mount == null || Exempt.is(mount)) return;
        if (headOf(mount) == toOffset || HeadVehicleView.headOf(mount) == toOffset) return;
        if (HeadVehicleView.reseat(player, mount, toOffset)) return;
        if (HeadVehicleView.isCopy(mount)) {
            HeadVehicleView.rehome(mount, toOffset);
            return;
        }
        carry(mount, toOffset);
    }

    private static void carry(Entity entity, long head) {
        if (head <= 0) carried.remove(entity.getUUID());
        else if (entity.level() instanceof ServerLevel level) carried.put(entity.getUUID(), new Carry(head, level));
        retag(entity, CARRY_PREFIX, head > 0 ? TimeHeads.toTag(head) : null);
        follow(entity, head);
        HeadTracking.markDirty();
    }

    public static void tick() {
        if (enabled() && TimeTravel.server != null && TimeTravel.server.getTickCount() % 40 == 0) restore();
        if (carried.isEmpty() && trips.isEmpty()) return;
        if (!enabled()) {
            clear();
            return;
        }
        recordTrips();
        Set<Long> live = heldHeads();

        boolean changed = false;
        for (Iterator<Map.Entry<UUID, Carry>> it = carried.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Carry> entry = it.next();
            Carry carry = entry.getValue();
            Entity entity = carry.level().getEntity(entry.getKey());
            if (entity == null || entity.isRemoved()) {
                it.remove();
                changed = true;
                continue;
            }
            if (!live.contains(carry.head())) {
                it.remove();
                unfollow(entry.getKey());
                retag(entity, CARRY_PREFIX, null);
                retag(entity, TRIP_PREFIX, null);
                changed = true;
                continue;
            }
            List<Entity> strangers = new ArrayList<>();
            for (Entity passenger : entity.getPassengers()) {
                if (passenger instanceof ServerPlayer sp && !Clones.is(sp)
                        && TimeTravel.headOffsetOf(sp) != carry.head()) strangers.add(passenger);
            }
            for (Entity stranger : strangers) stranger.stopRiding();
        }
        if (changed) HeadTracking.markDirty();
    }

    private static void restore() {
        Set<Long> held = heldHeads();
        boolean changed = false;
        for (ServerLevel level : TimeTravel.server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                String carryTag = tagOf(entity, CARRY_PREFIX);
                if (carryTag != null && !carried.containsKey(entity.getUUID())) {
                    long head = parseHead(carryTag.substring(CARRY_PREFIX.length()));
                    if (held.contains(head)) {
                        carried.put(entity.getUUID(), new Carry(head, level));
                        changed = true;
                    } else {
                        entity.removeTag(carryTag);
                    }
                }
                String tripTag = tagOf(entity, TRIP_PREFIX);
                if (tripTag != null && !trips.containsKey(entity.getUUID())) {
                    UUID trip = parseTrip(tripTag.substring(TRIP_PREFIX.length()));
                    long head = headOf(entity);
                    if (head == NONE) head = HeadVehicleView.headOf(entity);
                    if (trip != null && head != NONE && head > 0 && CarryTrailJournal.has(trip)) {
                        trips.put(entity.getUUID(), new Carry(head, level));
                        tripIds.put(entity.getUUID(), trip);
                    } else {
                        entity.removeTag(tripTag);
                    }
                }
            }
        }
        if (changed) HeadTracking.markDirty();
    }

    private static long parseHead(String text) {
        try {
            return TimeHeads.fromTag(Long.parseLong(text));
        } catch (NumberFormatException ex) {
            return NONE;
        }
    }

    private static UUID parseTrip(String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static void recordTrips() {
        for (Iterator<Map.Entry<UUID, Carry>> it = trips.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Carry> entry = it.next();
            Carry carry = entry.getValue();
            Entity entity = carry.level().getEntity(entry.getKey());
            if (entity == null || entity.isRemoved()) {
                it.remove();
                tripIds.remove(entry.getKey());
                continue;
            }
            CarryTrailJournal.record(tripIds.get(entry.getKey()), entity,
                    Math.max(0, TimeTravel.presentTick - carry.head()));
        }
    }

    public static void clear() {
        trips.clear();
        tripIds.clear();
        if (carried.isEmpty()) return;
        carried.clear();
        HeadTracking.markDirty();
    }
}
