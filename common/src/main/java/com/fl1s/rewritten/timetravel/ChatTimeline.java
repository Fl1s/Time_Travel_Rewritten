package com.fl1s.rewritten.timetravel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.fl1s.rewritten.timetravel.net.ClearChatPayload;
import com.fl1s.rewritten.timetravel.net.TimelineNet;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class ChatTimeline {
    private ChatTimeline() {}

    private static final int RESEND_LIMIT = 100;
    private static final Gson GSON = new GsonBuilder().create();
    private static final Type LIST_TYPE = new TypeToken<ArrayList<Entry>>() {}.getType();

    public static final class Entry {
        long tick;
        long seq;
        int visit = -1;
        String player;
        String text;
    }

    private static final List<Entry> entries = new ArrayList<>();
    private static final PriorityQueue<Entry> upcoming = new PriorityQueue<>(
            Comparator.comparingLong((Entry e) -> e.tick).thenComparingLong(e -> e.seq));
    private static long nextSeq = 0;
    private static boolean dirty = false;

    public static void onPlayerChat(ServerPlayer player, String text) {
        if (!TimeTravel.isLooping || !TimeTravel.trackChat || TimeTravel.loopSceneManager == null) return;
        if (player.getClass().getName().contains("FakePlayer")) return;
        PlayerData pd = TimeTravel.loopSceneManager.getRecordingPlayer(player.getName().getString());
        if (pd == null || !pd.getActive()) return;
        Entry e = new Entry();
        e.tick = TimeTravel.timelineTick;
        e.seq = nextSeq++;
        e.visit = pd.getCurrentVisit() != null ? pd.getCurrentVisit().id : -1;
        e.player = player.getName().getString();
        e.text = text;
        entries.add(e);
        dirty = true;
    }

    private static Component component(Entry e) {
        return Component.translatable("chat.type.text", Component.literal(e.player), Component.literal(e.text));
    }

    public static void tick() {
        while (!upcoming.isEmpty() && upcoming.peek().tick <= TimeTravel.timelineTick) {
            Entry e = upcoming.poll();
            if (TimeTravel.trackChat) {
                TimeTravel.server.getPlayerList().broadcastSystemMessage(component(e), false);
            }
        }
    }

    public static void onJump(long to) {
        rebuildUpcoming();
        resend(to);
    }

    public static void rebuildUpcoming() {
        upcoming.clear();
        for (Entry e : entries) {
            if (e.tick > TimeTravel.timelineTick) upcoming.add(e);
        }
    }

    public static void resend(long upTo) {
        if (!TimeTravel.trackChat || TimeTravel.server == null) return;

        List<Entry> history = new ArrayList<>();
        for (Entry e : entries) if (e.tick <= upTo) history.add(e);
        history.sort(Comparator.comparingLong((Entry e) -> e.tick).thenComparingLong(e -> e.seq));
        if (history.size() > RESEND_LIMIT) history = history.subList(history.size() - RESEND_LIMIT, history.size());

        for (ServerPlayer player : new ArrayList<>(TimeTravel.server.getPlayerList().getPlayers())) {
            if (player.getClass().getName().contains("FakePlayer")) continue;
            TimelineNet.sendToPlayer(player, ClearChatPayload.INSTANCE);
            for (Entry e : history) player.sendSystemMessage(component(e));
        }
    }

    public static void eraseVisits(Set<Integer> visitIds) {
        List<Entry> mine = new ArrayList<>();
        for (Entry e : entries) if (visitIds.contains(e.visit)) mine.add(e);
        entries.removeAll(mine);
        upcoming.removeAll(mine);
        dirty = true;
    }

    private static Path file() {
        return TimeTravel.worldFolder.resolve("timetravel_rewritten_chat.json");
    }

    public static void load() {
        entries.clear();
        upcoming.clear();
        nextSeq = 0;
        Path f = file();
        if (Files.exists(f)) {
            try (Reader r = Files.newBufferedReader(f)) {
                List<Entry> loaded = GSON.fromJson(r, LIST_TYPE);
                if (loaded != null) {
                    entries.addAll(loaded);
                    for (Entry e : loaded) nextSeq = Math.max(nextSeq, e.seq + 1);
                }
            } catch (Exception ex) {
                TimeTravel.LOOP_LOGGER.error("Failed to load chat timeline", ex);
            }
        }
        dirty = false;
        rebuildUpcoming();
    }

    public static void saveIfDirty() {
        if (dirty) save();
    }

    public static void save() {
        if (TimeTravel.worldFolder == null) return;
        try (Writer w = Files.newBufferedWriter(file())) {
            GSON.toJson(entries, LIST_TYPE, w);
            dirty = false;
        } catch (Exception ex) {
            TimeTravel.LOOP_LOGGER.error("Failed to save chat timeline", ex);
        }
    }

    public static void clear() {
        entries.clear();
        upcoming.clear();
        nextSeq = 0;
        dirty = true;
        save();
    }
}
