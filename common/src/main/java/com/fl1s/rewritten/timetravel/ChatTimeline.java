package com.fl1s.rewritten.timetravel;

import com.fl1s.rewritten.timetravel.journal.Journal;
import com.fl1s.rewritten.timetravel.net.ClearChatPayload;
import com.fl1s.rewritten.timetravel.net.TimelineNet;
import com.fl1s.rewritten.timetravel.util.Ghosts;
import com.google.gson.reflect.TypeToken;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public final class ChatTimeline {
    private ChatTimeline() {
    }

    private static final int RESEND_LIMIT = 100;

    public static final class Entry {
        long tick;
        long seq;
        int visit = -1;
        String player;
        String text;
    }

    private static final Journal<Entry> STORE = new Journal<>("timetravel_rewritten_chat.json", new TypeToken<ArrayList<Entry>>() {
    }.getType(), e -> e.tick, e -> e.seq, e -> e.visit);

    record Line(long tick, long seq, Component text) {
    }

    public static void onPlayerChat(ServerPlayer player, String text) {
        if (!TimeTravel.isLooping || !TimeTravel.trackChat || TimeTravel.loopSceneManager == null) return;
        if (Ghosts.is(player)) return;
        PlayerData pd = TimeTravel.loopSceneManager.getRecordingPlayer(player.getName().getString());
        if (pd == null || !pd.getActive()) return;
        Entry e = new Entry();
        e.tick = TimeTravel.timelineTick;
        e.seq = STORE.nextSeq();
        e.visit = pd.getCurrentVisit() != null ? pd.getCurrentVisit().id : -1;
        e.player = player.getName().getString();
        e.text = text;
        STORE.entries().add(e);
        STORE.markDirty();
    }

    private static Component component(Entry e) {
        return Component.translatable("chat.type.text", Component.literal(e.player), Component.literal(e.text));
    }

    public static void tick() {
        STORE.tick(e -> {
            if (TimeTravel.trackChat) {
                TimeTravel.server.getPlayerList().broadcastSystemMessage(component(e), false);
            }
        });
    }

    public static void onJump(long to) {
        STORE.rebuildUpcoming();
        resend(to);
    }

    public static void rebuildUpcoming() {
        STORE.rebuildUpcoming();
    }

    public static void resend(long upTo) {
        if (!TimeTravel.trackChat || TimeTravel.server == null) return;

        List<Line> history = new ArrayList<>();
        for (Entry e : STORE.entries()) if (e.tick <= upTo) history.add(new Line(e.tick, e.seq, component(e)));
        AdvancementTimeline.collect(upTo, history);
        history.sort(Comparator.comparingLong(Line::tick).thenComparingLong(Line::seq));
        if (history.size() > RESEND_LIMIT) history = history.subList(history.size() - RESEND_LIMIT, history.size());

        for (ServerPlayer player : new ArrayList<>(TimeTravel.server.getPlayerList().getPlayers())) {
            if (Ghosts.is(player)) continue;
            TimelineNet.sendToPlayer(player, ClearChatPayload.INSTANCE);
            for (Line l : history) player.sendSystemMessage(l.text());
        }
    }

    public static void eraseVisits(Set<Integer> visitIds) {
        STORE.eraseVisits(visitIds);
    }

    public static void load() {
        STORE.load();
    }

    public static void saveIfDirty() {
        STORE.saveIfDirty();
    }

    public static void save() {
        STORE.save();
    }

    public static void clear() {
        STORE.clear();
    }
}
