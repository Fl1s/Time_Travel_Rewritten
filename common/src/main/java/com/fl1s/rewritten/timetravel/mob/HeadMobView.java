package com.fl1s.rewritten.timetravel.mob;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.clone.CloneManager;
import com.fl1s.rewritten.timetravel.clone.CarryTrailJournal;
import com.fl1s.rewritten.timetravel.clone.HeadCarry;
import com.fl1s.rewritten.timetravel.data.Levels;
import net.minecraft.world.entity.EntityType;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.time.TimeHead;
import com.fl1s.rewritten.timetravel.time.TimeHeads;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class HeadMobView {

    private HeadMobView() {
    }

    private static final double PUPPET_RANGE_SQ = 80.0 * 80.0;
    private static final double MAX_DELTA = 7.5;
    private static final int CARRIED_PUPPET_BASE = -1_000_000;
    private static final Map<UUID, Integer> tripPuppets = new HashMap<>();
    private static int nextTripPuppet = -2_000_000;

    private static final Map<UUID, Map<Integer, Vec3>> shown = new HashMap<>();
    private static final Map<UUID, Map<Integer, Float>> healths = new HashMap<>();

    private record Ride(long head, UUID mob) {}

    private static final Map<Integer, Ride> rides = new HashMap<>();
    private static final Map<UUID, Map<Integer, Integer>> seated = new HashMap<>();

    public static void clear() {
        shown.clear();
        healths.clear();
        rides.clear();
        seated.clear();
    }

    public static boolean rideVisually(ServerPlayer clone, UUID mob) {
        if (!enabled() || !(MobTrailJournal.tracked(mob) || CarryTrailJournal.has(mob))) return false;
        long head = TimeTravel.cloneHeadOf(clone);
        if (head == CloneManager.NO_HEAD || head <= 0) return false;
        rides.put(clone.getId(), new Ride(head, mob));
        return true;
    }

    public static void unride(ServerPlayer clone) {
        rides.remove(clone.getId());
    }

    private static boolean enabled() {
        return TimeTravel.individualTime && TimeTravel.isLooping;
    }

    public static boolean hidden(Entity entity, ServerPlayer viewer) {
        if (!enabled()) return false;
        if (!(entity instanceof Mob) || Clones.is(entity)) return false;
        if (TimeTravel.headOffsetOf(viewer) <= 0) return false;
        return MobTrailJournal.tracked(entity.getUUID());
    }

    public static void tick() {
        if (!enabled()) {
            if (!shown.isEmpty()) releaseAll();
            return;
        }
        TimeHeads heads = TimeTravel.heads();
        if (heads == null || TimeTravel.server == null) return;

        Set<UUID> live = new HashSet<>();
        for (TimeHead head : heads.occupied()) {
            if (head.offset() <= 0 || head.isEmpty()) continue;
            for (ServerPlayer viewer : viewersOf(head.offset())) {
                live.add(viewer.getUUID());
                stream(viewer, head.offset(), head.position());
                seat(viewer, head.offset());
            }
        }
        shown.keySet().removeIf(id -> !live.contains(id));
        seated.keySet().removeIf(id -> !live.contains(id));
    }

    private static void stream(ServerPlayer viewer, long head, long position) {
        if (viewer.connection == null) return;
        ServerLevel level = viewer.serverLevel();
        Map<Integer, Vec3> active = shown.computeIfAbsent(viewer.getUUID(), k -> new HashMap<>());
        Set<Integer> seen = new HashSet<>();

        for (UUID id : MobTrailJournal.trackedIds()) {
            MobTrailJournal.Sample sample = MobTrailJournal.sampleOf(id, position);
            if (sample == null) continue;
            if (viewer.distanceToSqr(sample.x, sample.y, sample.z) > PUPPET_RANGE_SQ) continue;

            Entity entity = level.getEntity(id);
            if (!(entity instanceof Mob mob) || Clones.is(mob)) continue;

            int puppet = puppetId(mob);
            seen.add(puppet);
            Vec3 target = new Vec3(sample.x, sample.y, sample.z);
            Vec3 previous = active.get(puppet);

            if (previous == null || far(previous, target)) {
                if (previous != null) viewer.connection.send(new ClientboundRemoveEntitiesPacket(puppet));
                viewer.connection.send(spawn(mob, puppet, sample));
                List<SynchedEntityData.DataValue<?>> looks = mob.getEntityData().getNonDefaultValues();
                if (looks != null) viewer.connection.send(new ClientboundSetEntityDataPacket(puppet, looks));
            } else {
                viewer.connection.send(move(puppet, previous, target, sample));
            }
            viewer.connection.send(rotateHead(puppet, degrees(sample.yHeadRot)));
            active.put(puppet, target);
            flashIfHurt(viewer, level, puppet, sample.health);
        }

        String dim = Levels.canonicalId(level);
        for (Map.Entry<UUID, CarryTrailJournal.Trail> entry : CarryTrailJournal.trails().entrySet()) {
            CarryTrailJournal.Trail trail = entry.getValue();
            if (!dim.equals(trail.dim) || HeadCarry.isTripIn(entry.getKey(), head)) continue;
            CarryTrailJournal.Sample sample = CarryTrailJournal.sampleAt(trail, position);
            if (sample == null || viewer.distanceToSqr(sample.x, sample.y, sample.z) > PUPPET_RANGE_SQ) continue;
            EntityType<?> type = EntityType.byString(trail.type).orElse(null);
            if (type == null) continue;

            int puppet = tripPuppetId(entry.getKey());
            seen.add(puppet);
            Vec3 target = new Vec3(sample.x, sample.y, sample.z);
            Vec3 previous = active.get(puppet);
            if (previous == null || far(previous, target)) {
                if (previous != null) viewer.connection.send(new ClientboundRemoveEntitiesPacket(puppet));
                viewer.connection.send(new ClientboundAddEntityPacket(puppet, entry.getKey(),
                        sample.x, sample.y, sample.z, sample.xRot, sample.yRot, type, 0, Vec3.ZERO, sample.yHeadRot));
                Entity source = level.getEntity(trail.source);
                List<SynchedEntityData.DataValue<?>> looks = source == null ? null : source.getEntityData().getNonDefaultValues();
                if (looks != null) viewer.connection.send(new ClientboundSetEntityDataPacket(puppet, looks));
            } else {
                viewer.connection.send(new ClientboundMoveEntityPacket.PosRot(puppet,
                        delta(target.x, previous.x), delta(target.y, previous.y), delta(target.z, previous.z),
                        degrees(sample.yRot), degrees(sample.xRot), true));
            }
            viewer.connection.send(rotateHead(puppet, degrees(sample.yHeadRot)));
            active.put(puppet, target);
        }

        Map<Integer, Float> lastHealth = healths.get(viewer.getUUID());
        for (Iterator<Map.Entry<Integer, Vec3>> it = active.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Vec3> entry = it.next();
            if (seen.contains(entry.getKey())) continue;
            viewer.connection.send(new ClientboundRemoveEntitiesPacket(entry.getKey()));
            if (lastHealth != null) lastHealth.remove(entry.getKey());
            it.remove();
        }
    }

    private static void seat(ServerPlayer viewer, long head) {
        if (viewer.connection == null) return;
        ServerLevel level = viewer.serverLevel();
        Map<Integer, Vec3> active = shown.getOrDefault(viewer.getUUID(), Map.of());
        Map<Integer, Integer> mine = seated.computeIfAbsent(viewer.getUUID(), k -> new HashMap<>());
        Map<Integer, Integer> wanted = new HashMap<>();

        rides.entrySet().removeIf(entry -> !(level.getEntity(entry.getKey()) instanceof ServerPlayer clone)
                || clone.isRemoved() || !Clones.is(clone));
        for (Map.Entry<Integer, Ride> entry : rides.entrySet()) {
            Ride ride = entry.getValue();
            if (ride.head() != head) continue;
            int puppet;
            if (CarryTrailJournal.has(ride.mob())) {
                puppet = tripPuppetId(ride.mob());
            } else {
                Entity mob = level.getEntity(ride.mob());
                if (mob == null) continue;
                puppet = puppetId(mob);
            }
            if (!active.containsKey(puppet)) continue;
            wanted.put(puppet, entry.getKey());
        }

        for (Map.Entry<Integer, Integer> entry : wanted.entrySet()) {
            viewer.connection.send(passengers(entry.getKey(), entry.getValue()));
        }
        for (Integer mobId : mine.keySet()) {
            if (!wanted.containsKey(mobId) && active.containsKey(mobId)) viewer.connection.send(passengers(mobId));
        }
        mine.clear();
        mine.putAll(wanted);
    }

    private static ClientboundSetPassengersPacket passengers(int vehicle, int... riders) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(vehicle);
        buf.writeVarIntArray(riders);
        try {
            return ClientboundSetPassengersPacket.STREAM_CODEC.decode(buf);
        } finally {
            buf.release();
        }
    }

    private static boolean far(Vec3 from, Vec3 to) {
        return Math.abs(to.x - from.x) > MAX_DELTA
                || Math.abs(to.y - from.y) > MAX_DELTA
                || Math.abs(to.z - from.z) > MAX_DELTA;
    }

    private static void flashIfHurt(ServerPlayer viewer, ServerLevel level, int puppet, float health) {
        Float before = healths.computeIfAbsent(viewer.getUUID(), k -> new HashMap<>()).put(puppet, health);
        if (before == null || health <= 0.0F || health >= before - 0.01F) return;
        viewer.connection.send(new ClientboundDamageEventPacket(puppet,
                level.damageSources().generic().typeHolder(), -1, -1, Optional.empty()));
    }

    private static int tripPuppetId(UUID trip) {
        return tripPuppets.computeIfAbsent(trip, k -> nextTripPuppet--);
    }

    private static int puppetId(Entity mob) {
        return HeadCarry.isCarried(mob.getUUID()) ? CARRIED_PUPPET_BASE - mob.getId() : mob.getId();
    }

    private static ClientboundRotateHeadPacket rotateHead(int id, byte yHeadRot) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(id);
        buf.writeByte(yHeadRot);
        try {
            return ClientboundRotateHeadPacket.STREAM_CODEC.decode(buf);
        } finally {
            buf.release();
        }
    }

    private static ClientboundAddEntityPacket spawn(Mob mob, int puppet, MobTrailJournal.Sample sample) {
        UUID uuid = puppet == mob.getId() ? mob.getUUID()
                : UUID.nameUUIDFromBytes(("puppet/" + mob.getUUID()).getBytes(StandardCharsets.UTF_8));
        return new ClientboundAddEntityPacket(puppet, uuid,
                sample.x, sample.y, sample.z, sample.xRot, sample.yRot,
                mob.getType(), 0, Vec3.ZERO, sample.yHeadRot);
    }

    private static ClientboundMoveEntityPacket move(int puppet, Vec3 from, Vec3 to,
                                                    MobTrailJournal.Sample sample) {
        return new ClientboundMoveEntityPacket.PosRot(puppet,
                delta(to.x, from.x), delta(to.y, from.y), delta(to.z, from.z),
                degrees(sample.yRot), degrees(sample.xRot), true);
    }

    private static short delta(double to, double from) {
        return (short) Mth.clamp((long) ((to - from) * 4096.0), Short.MIN_VALUE, Short.MAX_VALUE);
    }

    private static byte degrees(float value) {
        return (byte) Mth.floor(value * 256.0F / 360.0F);
    }

    public static void onTravel(ServerPlayer player) {
        if (player == null) return;
        release(player);
    }

    private static void releaseAll() {
        if (TimeTravel.server == null) {
            shown.clear();
            return;
        }
        for (ServerPlayer player : TimeTravel.server.getPlayerList().getPlayers()) release(player);
        shown.clear();
    }

    private static void release(ServerPlayer player) {
        seated.remove(player.getUUID());
        healths.remove(player.getUUID());
        Map<Integer, Vec3> active = shown.remove(player.getUUID());
        if (active == null || active.isEmpty() || player.connection == null) return;
        int[] ids = new int[active.size()];
        int i = 0;
        for (Integer id : active.keySet()) ids[i++] = id;
        player.connection.send(new ClientboundRemoveEntitiesPacket(ids));
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
