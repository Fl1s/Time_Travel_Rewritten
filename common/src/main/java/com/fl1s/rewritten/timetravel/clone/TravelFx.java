package com.fl1s.rewritten.timetravel.clone;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.net.CloneAppearPayload;
import com.fl1s.rewritten.timetravel.net.CloneVanishPayload;
import com.fl1s.rewritten.timetravel.net.TimelineNet;
import com.fl1s.rewritten.timetravel.time.TimeHeads;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public final class TravelFx {

    private TravelFx() {
    }

    public static final int FADE_TICKS = 17;
    public static final int APPEAR_DELAY_TICKS = 2;

    private record Fade(long fromHead, long until) {}

    private static final Map<Integer, Fade> fading = new HashMap<>();

    public static void clear() {
        fading.clear();
    }

    public static void shiftHeads(long delta) {
        fading.replaceAll((id, fade) -> new Fade(TimeHeads.shifted(fade.fromHead(), delta), fade.until() + delta));
    }

    public static void onTravel(ServerPlayer player, long fromOffset, long toOffset, int dir) {
        if (!TimeTravel.individualTime || player == null) return;
        if (fromOffset == toOffset) return;
        if (!(player.level() instanceof ServerLevel level)) return;

        int id = player.getId();
        fading.put(id, new Fade(fromOffset, TimeTravel.presentTick + FADE_TICKS));

        CloneVanishPayload vanish = new CloneVanishPayload(id, FADE_TICKS);
        CloneAppearPayload appear = new CloneAppearPayload(id, APPEAR_DELAY_TICKS);

        for (ServerPlayer viewer : new ArrayList<>(level.players())) {
            if (Clones.is(viewer)) continue;
            if (viewer == player) {
                TimelineNet.sendToPlayer(viewer, appear);
                continue;
            }
            long head = TimeTravel.headOffsetOf(viewer);
            if (head == fromOffset) TimelineNet.sendToPlayer(viewer, vanish);
            else if (head == toOffset) TimelineNet.sendToPlayer(viewer, appear);
        }

        CloneManager.emitTravel(level, player.getX(), player.getY(), player.getZ(), dir, false,
                listener -> listener == player
                        || TimeTravel.headOffsetOf(listener) == fromOffset
                        || TimeTravel.headOffsetOf(listener) == toOffset);
    }

    public static boolean fading(Entity entity, ServerPlayer viewer) {
        if (fading.isEmpty()) return false;
        Fade fade = fading.get(entity.getId());
        if (fade == null || TimeTravel.presentTick >= fade.until()) return false;
        return TimeTravel.headOffsetOf(viewer) == fade.fromHead();
    }

    public static void tick() {
        if (fading.isEmpty()) return;
        if (fading.values().removeIf(fade -> TimeTravel.presentTick >= fade.until())) {
            HeadTracking.markDirty();
        }
    }
}
