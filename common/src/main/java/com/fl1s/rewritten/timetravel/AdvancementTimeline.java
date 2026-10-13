package com.fl1s.rewritten.timetravel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.advancements.Advancement;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class AdvancementTimeline {
    private AdvancementTimeline() {
    }

    private static final Gson GSON = new GsonBuilder().create();
    private static final Type LIST_TYPE = new TypeToken<ArrayList<Entry>>() {
    }.getType();

    public static final class Entry {
        long tick;
        long seq;
        int visit = -1;
        String player;
        String advancement;
    }

    private static final List<Entry> entries = new ArrayList<>();
    private static final PriorityQueue<Entry> upcoming = new PriorityQueue<>(Comparator.comparingLong((Entry e) -> e.tick).thenComparingLong(e -> e.seq));
    private static long nextSeq = 0;
    private static boolean dirty = false;

    public static boolean isGhost(ServerPlayer p) {
        return p.getClass().getName().contains("FakePlayer");
    }

    public static void onAwarded(ServerPlayer player, AdvancementHolder holder) {
        if (!TimeTravel.isLooping || !TimeTravel.trackChat || TimeTravel.loopSceneManager == null) return;
        if (isGhost(player)) return;
        DisplayInfo display = holder.value().display().orElse(null);
        if (display == null || !display.shouldAnnounceChat()) return;
        PlayerData pd = TimeTravel.loopSceneManager.getRecordingPlayer(player.getName().getString());
        if (pd == null || !pd.getActive() || pd.getCurrentVisit() == null) return;

        Entry e = new Entry();
        e.tick = TimeTravel.timelineTick;
        e.seq = nextSeq++;
        e.visit = pd.getCurrentVisit().id;
        e.player = pd.getNickname();
        e.advancement = holder.id().toString();
        entries.add(e);
        dirty = true;
    }

    private static Component component(Entry e) {
        AdvancementHolder holder = TimeTravel.server.getAdvancements().get(ResourceLocation.parse(e.advancement));
        if (holder == null) return null;
        DisplayInfo display = holder.value().display().orElse(null);
        if (display == null) return null;
        return Component.translatable("chat.type.advancement." + display.getType().getSerializedName(), Component.literal(e.player), Advancement.name(holder));
    }

    public static void tick() {
        while (!upcoming.isEmpty() && upcoming.peek().tick <= TimeTravel.timelineTick) {
            Entry e = upcoming.poll();
            if (!TimeTravel.trackChat) continue;
            Component c = component(e);
            if (c != null) TimeTravel.server.getPlayerList().broadcastSystemMessage(c, false);
        }
    }

    public static void onJump() {
        rebuildUpcoming();
    }

    static void collect(long upTo, List<ChatTimeline.Line> out) {
        if (!TimeTravel.trackChat) return;
        for (Entry e : entries) {
            if (e.tick > upTo) continue;
            Component c = component(e);
            if (c != null) out.add(new ChatTimeline.Line(e.tick, e.seq, c));
        }
    }

    public static void rebuildUpcoming() {
        upcoming.clear();
        for (Entry e : entries) if (e.tick > TimeTravel.timelineTick) upcoming.add(e);
    }

    public static void eraseVisits(Set<Integer> visitIds) {
        entries.removeIf(e -> visitIds.contains(e.visit));
        upcoming.removeIf(e -> visitIds.contains(e.visit));
        dirty = true;
    }

    private static Path file() {
        return TimeTravel.worldFolder.resolve("timetravel_rewritten_advancements.json");
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
                TimeTravel.LOOP_LOGGER.error("Failed to load advancement timeline", ex);
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
            TimeTravel.LOOP_LOGGER.error("Failed to save advancement timeline", ex);
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