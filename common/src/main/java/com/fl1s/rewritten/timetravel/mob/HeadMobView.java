package com.fl1s.rewritten.timetravel.mob;

import com.fl1s.rewritten.timetravel.TimeTravel;
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

    private static final Map<UUID, Map<Integer, Vec3>> shown = new HashMap<>();

    public static void clear() {
        shown.clear();
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
                stream(viewer, head.position());
            }
        }
        shown.keySet().removeIf(id -> !live.contains(id));
    }

    private static void stream(ServerPlayer viewer, long position) {
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

            seen.add(mob.getId());
            Vec3 target = new Vec3(sample.x, sample.y, sample.z);
            Vec3 previous = active.get(mob.getId());

            if (previous == null || far(previous, target)) {
                if (previous != null) viewer.connection.send(new ClientboundRemoveEntitiesPacket(mob.getId()));
                viewer.connection.send(spawn(mob, sample));
            } else {
                viewer.connection.send(move(mob, previous, target, sample));
            }
            viewer.connection.send(new ClientboundRotateHeadPacket(mob, degrees(sample.yHeadRot)));
            active.put(mob.getId(), target);
        }

        for (Iterator<Map.Entry<Integer, Vec3>> it = active.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Vec3> entry = it.next();
            if (seen.contains(entry.getKey())) continue;
            viewer.connection.send(new ClientboundRemoveEntitiesPacket(entry.getKey()));
            it.remove();
        }
    }

    private static boolean far(Vec3 from, Vec3 to) {
        return Math.abs(to.x - from.x) > MAX_DELTA
                || Math.abs(to.y - from.y) > MAX_DELTA
                || Math.abs(to.z - from.z) > MAX_DELTA;
    }

    private static ClientboundAddEntityPacket spawn(Mob mob, MobTrailJournal.Sample sample) {
        return new ClientboundAddEntityPacket(mob.getId(), mob.getUUID(),
                sample.x, sample.y, sample.z, sample.xRot, sample.yRot,
                mob.getType(), 0, Vec3.ZERO, sample.yHeadRot);
    }

    private static ClientboundMoveEntityPacket move(Mob mob, Vec3 from, Vec3 to,
                                                    MobTrailJournal.Sample sample) {
        return new ClientboundMoveEntityPacket.PosRot(mob.getId(),
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
