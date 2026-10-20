package com.fl1s.rewritten.timetravel.timeline;

import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.data.PlayerData;
import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.journal.Journal;
import com.google.gson.reflect.TypeToken;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class AdvancementTimeline {
    private AdvancementTimeline() {
    }

    public static final class Entry {
        long tick;
        long seq;
        int visit = -1;
        String player;
        String advancement;
    }

    private static final Journal<Entry> STORE = new Journal<>("timetravel_rewritten_advancements.json", new TypeToken<ArrayList<Entry>>() {
    }.getType(), e -> e.tick, e -> e.seq, e -> e.visit);

    public static boolean isClone(ServerPlayer p) {
        return Clones.is(p);
    }

    public static void onAwarded(ServerPlayer player, AdvancementHolder holder) {
        if (!TimeTravel.isLooping || !TimeTravel.trackChat || TimeTravel.timelineSceneManager == null) return;
        if (isClone(player)) return;
        DisplayInfo display = holder.value().display().orElse(null);
        if (display == null || !display.shouldAnnounceChat()) return;
        PlayerData pd = TimeTravel.timelineSceneManager.getRecordingPlayer(player.getName().getString());
        if (pd == null || !pd.getActive() || pd.getCurrentVisit() == null) return;

        Entry e = new Entry();
        e.tick = TimeTravel.sharedTick;
        e.seq = STORE.nextSeq();
        e.visit = pd.getCurrentVisit().id;
        e.player = pd.getNickname();
        e.advancement = holder.id().toString();
        STORE.entries().add(e);
        STORE.markDirty();
    }

    private static Component component(Entry e) {
        AdvancementHolder holder = TimeTravel.server.getAdvancements().get(ResourceLocation.parse(e.advancement));
        if (holder == null) return null;
        DisplayInfo display = holder.value().display().orElse(null);
        if (display == null) return null;
        return Component.translatable("chat.type.advancement." + display.getType().getSerializedName(), Component.literal(e.player), Advancement.name(holder));
    }

    public static void tick() {
        STORE.tick(e -> {
            if (!TimeTravel.trackChat) return;
            Component c = component(e);
            if (c != null) TimeTravel.server.getPlayerList().broadcastSystemMessage(c, false);
        });
    }

    public static void onJump() {
        STORE.rebuildUpcoming();
    }

    public static void onJump(long from, long to) {
        onJump();
    }

    static void collect(long upTo, List<ChatTimeline.Line> out) {
        collect(Long.MIN_VALUE, upTo, out);
    }

    static void collect(long after, long upTo, List<ChatTimeline.Line> out) {
        if (!TimeTravel.trackChat) return;
        for (Entry e : STORE.entries()) {
            if (e.tick > upTo || e.tick <= after) continue;
            Component c = component(e);
            if (c != null) out.add(new ChatTimeline.Line(e.tick, e.seq, c));
        }
    }

    public static void rebuildUpcoming() {
        STORE.rebuildUpcoming();
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
