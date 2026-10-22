package com.fl1s.rewritten.timetravel.journal;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.data.Levels;
import com.fl1s.rewritten.timetravel.entity.EntityJournal;
import com.fl1s.rewritten.timetravel.timeline.ChatTimeline;
import com.fl1s.rewritten.timetravel.time.HeadScope;
import com.fl1s.rewritten.timetravel.time.TimeHead;
import com.fl1s.rewritten.timetravel.time.TimeHeads;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class HeadWorldView {

    private HeadWorldView() {
    }

    private record Key(String dim, long pos) {}

    private static final Map<Long, Map<Key, BlockState>> views = new HashMap<>();

    public static void clear() {
        views.clear();
    }

    public static boolean hasOverlay() {
        return !views.isEmpty();
    }

    public static BlockState resolve(Level level, BlockPos pos) {
        long head = HeadScope.current();
        if (head <= 0 || level == null) return null;
        return overlayAt(head, Levels.canonicalId(level), pos.asLong());
    }

    public static BlockState overlayAt(long head, String dim, long packedPos) {
        if (views.isEmpty()) return null;
        Map<Key, BlockState> view = views.get(head);
        if (view == null || view.isEmpty()) return null;
        return view.get(new Key(dim, packedPos));
    }

    private static boolean enabled() {
        return TimeTravel.individualTime && TimeTravel.isLooping;
    }

    public static void tick() {
        if (!enabled()) {
            if (!views.isEmpty()) releaseAll();
            return;
        }
        TimeHeads heads = TimeTravel.heads();
        if (heads == null) return;

        Set<Long> live = new HashSet<>();
        for (TimeHead head : heads.occupied()) {
            if (head.offset() <= 0 || head.isEmpty()) continue;
            live.add(head.offset());
            List<ServerPlayer> viewers = viewersOf(head.offset());
            Map<Key, BlockState> view = views.get(head.offset());
            if (view == null) {
                view = open(head.offset(), head.position());
                for (ServerPlayer viewer : viewers) sendAll(view, viewer);
            }
            advance(head.offset(), head.position(), view, viewers);
            EffectJournal.tickHead(head.offset(), head.position(), viewers);
            EntityJournal.tickHead(head.offset(), head.position());
            ChatTimeline.tickHead(head.offset(), head.position(), viewers);
        }

        List<Long> stale = new ArrayList<>();
        for (Long head : views.keySet()) if (!live.contains(head)) stale.add(head);
        for (Long head : stale) {
            views.remove(head);
            BlockJournal.store().dropCursor(head);
            EffectJournal.dropHead(head);
            EntityJournal.dropHead(head);
            ChatTimeline.dropHead(head);
        }
    }

    private static Map<Key, BlockState> open(long head, long position) {
        Map<Key, BlockState> view = build(position);
        views.put(head, view);
        BlockJournal.store().rebuildUpcoming(head, position);
        EffectJournal.openHead(head, position);
        EntityJournal.openHead(head, position);
        ChatTimeline.openHead(head, position);
        return view;
    }

    private static Map<Key, BlockState> build(long position) {
        List<BlockJournal.Entry> entries = new ArrayList<>(BlockJournal.allEntries());
        entries.sort(Comparator.comparingLong((BlockJournal.Entry e) -> e.tick)
                .thenComparingLong(e -> e.seq));

        Map<Key, BlockState> view = new LinkedHashMap<>();
        for (BlockJournal.Entry e : entries) {
            if (e.tick <= position) continue;
            BlockState state = BlockJournal.oldState(e);
            if (state == null) continue;
            view.putIfAbsent(new Key(e.dim, BlockPos.asLong(e.x, e.y, e.z)), state);
        }
        return view;
    }

    private static void advance(long head, long position, Map<Key, BlockState> view,
                                List<ServerPlayer> viewers) {
        BlockJournal.store().tick(head, position, e -> {
            BlockState state = BlockJournal.newState(e);
            if (state == null) return;
            Key key = new Key(e.dim, BlockPos.asLong(e.x, e.y, e.z));
            view.put(key, state);
            for (ServerPlayer viewer : viewers) send(viewer, key, state);
            if (!BlockJournal.hasChangeAfter(key.dim(), key.pos(), position)) view.remove(key);
        });
    }

    public static void onRecord(BlockJournal.Entry entry) {
        if (!enabled() || views.isEmpty()) return;
        TimeHeads heads = TimeTravel.heads();
        if (heads == null) return;

        BlockState old = BlockJournal.oldState(entry);
        if (old == null) return;
        Key key = new Key(entry.dim, BlockPos.asLong(entry.x, entry.y, entry.z));

        for (TimeHead head : heads.occupied()) {
            if (head.offset() <= 0 || head.isEmpty()) continue;
            if (head.position() >= entry.tick) continue;
            Map<Key, BlockState> view = views.get(head.offset());
            if (view == null) continue;

            BlockJournal.store().addUpcoming(head.offset(), entry);
            if (view.containsKey(key)) continue;
            view.put(key, old);
            for (ServerPlayer viewer : viewersOf(head.offset())) send(viewer, key, old);
        }
    }

    public static void onEffect(EffectJournal.Entry entry) {
        if (!enabled() || views.isEmpty()) return;
        TimeHeads heads = TimeTravel.heads();
        if (heads == null) return;

        for (TimeHead head : heads.occupied()) {
            if (head.offset() <= 0 || head.isEmpty()) continue;
            if (head.position() >= EffectJournal.tickOf(entry)) continue;
            if (!views.containsKey(head.offset())) continue;
            EffectJournal.addUpcoming(head.offset(), entry);
        }
    }

    public static void onCascade(String dim, long packedPos) {
        if (!enabled() || views.isEmpty()) return;
        TimeHeads heads = TimeTravel.heads();
        if (heads == null) return;

        Key key = new Key(dim, packedPos);
        for (TimeHead head : heads.occupied()) {
            if (head.offset() <= 0 || head.isEmpty()) continue;
            Map<Key, BlockState> view = views.get(head.offset());
            if (view == null) continue;

            BlockState past = BlockJournal.pastStateAt(dim, packedPos, head.position());
            if (past == null) {
                if (view.remove(key) == null) continue;
                ServerLevel level = TimeTravel.server == null ? null
                        : TimeTravel.server.getLevel(Levels.key(dim));
                if (level == null) continue;
                BlockState present = level.getBlockState(BlockPos.of(packedPos));
                for (ServerPlayer viewer : viewersOf(head.offset())) send(viewer, key, present);
            } else if (!past.equals(view.get(key))) {
                view.put(key, past);
                for (ServerPlayer viewer : viewersOf(head.offset())) send(viewer, key, past);
            }
        }
    }

    public static void onTravel(ServerPlayer player, long fromOffset, long toOffset) {
        if (!enabled() || player == null) return;

        Map<Key, BlockState> leaving = views.get(fromOffset);
        if (leaving != null) restore(leaving, player);
        if (toOffset <= 0) return;

        long position = Math.max(0, TimeTravel.presentTick - toOffset);
        Map<Key, BlockState> view = views.get(toOffset);
        if (view == null) view = open(toOffset, position);
        sendAll(view, player);
    }

    public static void onChunkSent(ServerPlayer viewer, ChunkPos chunk) {
        if (!enabled() || views.isEmpty() || viewer == null) return;
        Map<Key, BlockState> view = views.get(TimeTravel.headOffsetOf(viewer));
        if (view == null) return;

        String dim = Levels.canonicalId(viewer.level());
        for (Map.Entry<Key, BlockState> shown : view.entrySet()) {
            Key key = shown.getKey();
            if (!key.dim().equals(dim)) continue;
            BlockPos pos = BlockPos.of(key.pos());
            if (SectionPos.blockToSectionCoord(pos.getX()) != chunk.x) continue;
            if (SectionPos.blockToSectionCoord(pos.getZ()) != chunk.z) continue;
            viewer.connection.send(new ClientboundBlockUpdatePacket(pos, shown.getValue()));
        }
    }

    private static void releaseAll() {
        for (Map.Entry<Long, Map<Key, BlockState>> entry : views.entrySet()) {
            for (ServerPlayer viewer : viewersOf(entry.getKey())) restore(entry.getValue(), viewer);
        }
        views.clear();
    }

    private static void restore(Map<Key, BlockState> view, ServerPlayer viewer) {
        for (Key key : view.keySet()) {
            ServerLevel level = TimeTravel.server == null ? null
                    : TimeTravel.server.getLevel(Levels.key(key.dim()));
            if (level == null) continue;
            send(viewer, key, level.getBlockState(BlockPos.of(key.pos())));
        }
    }

    private static void sendAll(Map<Key, BlockState> view, ServerPlayer viewer) {
        for (Map.Entry<Key, BlockState> shown : view.entrySet()) {
            send(viewer, shown.getKey(), shown.getValue());
        }
    }

    private static void send(ServerPlayer viewer, Key key, BlockState state) {
        if (state == null || viewer.connection == null) return;
        if (!Levels.canonicalId(viewer.level()).equals(key.dim())) return;
        viewer.connection.send(new ClientboundBlockUpdatePacket(BlockPos.of(key.pos()), state));
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
