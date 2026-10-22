package com.fl1s.rewritten.timetravel.compat.anim;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.data.PlayerData;
import com.fl1s.rewritten.timetravel.journal.AnimJournal;
import com.fl1s.rewritten.timetravel.net.TimelineNet;
import com.google.gson.Gson;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.UUID;

public final class CastRelay implements RelayAdapter {
    private static final Gson GSON = new Gson();
    private static final ResourceLocation STARTED_ID = ResourceLocation.fromNamespaceAndPath("irons_spellbooks", "on_cast_started");
    private static final ResourceLocation FINISHED_ID = ResourceLocation.fromNamespaceAndPath("irons_spellbooks", "on_cast_finished");

    private static boolean initialized = false;
    private static Field casterIdField;
    private static Field spellIdField;
    private static Field levelField;
    private static Field cancelledField;
    private static Constructor<?> startedCtor;
    private static Constructor<?> finishedCtor;

    private final boolean started;

    private CastRelay(boolean started) {
        this.started = started;
    }

    public static RelayAdapter create(boolean started) {
        try {
            ensureInitialized();
            return new CastRelay(started);
        } catch (Throwable t) {
            TimeTravel.TIMELINE_LOGGER.error("Failed to init Iron's Spells cast relay (started={})", started, t);
            return null;
        }
    }

    private static synchronized void ensureInitialized() throws Exception {
        if (initialized) return;
        initialized = true;
        Class<?> startedCls = Class.forName("io.redspace.ironsspellbooks.network.casting.OnCastStartedPacket");
        Class<?> finishedCls = Class.forName("io.redspace.ironsspellbooks.network.casting.OnCastFinishedPacket");
        startedCtor = startedCls.getConstructor(UUID.class, String.class, int.class);
        finishedCtor = finishedCls.getConstructor(UUID.class, String.class, boolean.class);
        casterIdField = startedCls.getDeclaredField("castingEntityId");
        casterIdField.setAccessible(true);
        spellIdField = startedCls.getDeclaredField("spellId");
        spellIdField.setAccessible(true);
        levelField = startedCls.getDeclaredField("spellLevel");
        levelField.setAccessible(true);
        cancelledField = finishedCls.getDeclaredField("cancelled");
        cancelledField.setAccessible(true);
    }

    public static void onOutgoing(ServerPlayer recipient, CustomPacketPayload payload) {
        if (!initialized || casterIdField == null) return;
        if (!TimeTravel.isLooping || TimeTravel.timelineSceneManager == null) return;
        if (recipient == null || Clones.is(recipient)) return;

        ResourceLocation pid = payload.type().id();
        if (!STARTED_ID.equals(pid) && !FINISHED_ID.equals(pid)) return;

        try {
            UUID casterId = (UUID) casterIdField.get(payload);
            if (casterId == null) return;
            if (!casterId.equals(recipient.getUUID())) return;

            PlayerData pd = TimeTravel.timelineSceneManager.getRecordingPlayer(recipient.getName().getString());
            if (pd == null || !pd.getActive() || pd.getCurrentVisit() == null) return;

            String data;
            if (STARTED_ID.equals(pid)) {
                String spellId = (String) spellIdField.get(payload);
                int level = (Integer) levelField.get(payload);
                data = GSON.toJson(new StartedData(spellId, level));
            } else {
                String spellId = (String) spellIdField.get(payload);
                boolean cancelled = (Boolean) cancelledField.get(payload);
                data = GSON.toJson(new FinishedData(spellId, cancelled));
            }

            AnimJournal.record(TimeTravel.timelineTick, pd.getCurrentVisit().id, pid, data);
        } catch (Throwable t) {
            TimeTravel.TIMELINE_LOGGER.error("Failed to record cast packet", t);
        }
    }

    @Override
    public ResourceLocation recordId() {
        return started ? STARTED_ID : FINISHED_ID;
    }

    @Override
    public String serialize(CustomPacketPayload payload) {
        return null;
    }

    @Override
    public void replay(String json, int entityId, ServerLevel level) {
        try {
            Entity e = level.getEntity(entityId);
            if (!(e instanceof ServerPlayer clone)) return;
            UUID cloneUUID = clone.getUUID();

            Object packet;
            if (started) {
                StartedData d = GSON.fromJson(json, StartedData.class);
                packet = startedCtor.newInstance(cloneUUID, d.spellId, d.spellLevel);
            } else {
                FinishedData d = GSON.fromJson(json, FinishedData.class);
                packet = finishedCtor.newInstance(cloneUUID, d.spellId, d.cancelled);
            }

            if (!(packet instanceof CustomPacketPayload cpp)) return;

            for (ServerPlayer viewer : new ArrayList<>(level.players())) {
                if (Clones.is(viewer)) continue;
                TimelineNet.sendToPlayer(viewer, cpp);
            }
        } catch (Throwable t) {
            TimeTravel.TIMELINE_LOGGER.error("Failed to replay cast packet", t);
        }
    }

    private record StartedData(String spellId, int spellLevel) {
    }

    private record FinishedData(String spellId, boolean cancelled) {
    }
}
