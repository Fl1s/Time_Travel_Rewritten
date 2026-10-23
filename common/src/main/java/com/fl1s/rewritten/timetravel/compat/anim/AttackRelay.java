package com.fl1s.rewritten.timetravel.compat.anim;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.data.PlayerData;
import com.fl1s.rewritten.timetravel.journal.AttackJournal;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class AttackRelay {
    private static final ResourceLocation RECORD_ID = ResourceLocation.fromNamespaceAndPath("bettercombat", "c2s_request_attack");
    private static AttackRelay INSTANCE;

    private static boolean inBcHandler = false;
    private static long bcFlagTick = -1;

    private final Class<?> c2sClass;
    private final Constructor<?> c2sCtor;
    private final Method handleAttackRequest;
    private final Field useVanillaPacket;

    private AttackRelay() throws Exception {
        this.c2sClass = Class.forName("net.bettercombat.network.Packets$C2S_AttackRequest");
        this.c2sCtor = c2sClass.getDeclaredConstructor(int.class, boolean.class, int.class, int.class, int[].class);
        Class<?> serverNetwork = Class.forName("net.bettercombat.network.ServerNetwork");
        this.handleAttackRequest = serverNetwork.getMethod("handleAttackRequest", c2sClass, MinecraftServer.class, ServerPlayer.class, ServerGamePacketListenerImpl.class);
        this.useVanillaPacket = c2sClass.getField("UseVanillaPacket");
    }

    public static void init() {
        try {
            Class.forName("net.bettercombat.network.ServerNetwork");
            INSTANCE = new AttackRelay();
        } catch (Throwable ignored) {
        }
    }

    public static AttackRelay get() {
        return INSTANCE;
    }

    public static boolean isInsideBcHandler() {
        return inBcHandler && bcFlagTick == TimeTravel.timelineTick;
    }

    public static void onIncoming(CustomPacketPayload payload, ServerPlayer player) {
        if (INSTANCE == null) return;
        if (!payload.type().id().equals(RECORD_ID)) return;
        if (!TimeTravel.isLooping || TimeTravel.timelineSceneManager == null) return;
        if (player == null || Clones.is(player)) return;

        PlayerData pd = TimeTravel.timelineSceneManager.getRecordingPlayer(player.getName().getString());
        if (pd == null || !pd.getActive() || pd.getCurrentVisit() == null) return;

        try {
            Class<?> c = payload.getClass();
            int comboCount = (Integer) c.getMethod("comboCount").invoke(payload);
            boolean isSneaking = (Boolean) c.getMethod("isSneaking").invoke(payload);
            int selectedSlot = (Integer) c.getMethod("selectedSlot").invoke(payload);
            int cursorTarget = (Integer) c.getMethod("cursorTarget").invoke(payload);
            int[] entityIds = (int[]) c.getMethod("entityIds").invoke(payload);

            AttackJournal.record(TimeTravel.timelineTick, pd.getCurrentVisit().id, player.level().dimension().location().toString(), comboCount, isSneaking, selectedSlot, cursorTarget, entityIds);

            inBcHandler = true;
            bcFlagTick = TimeTravel.timelineTick;
        } catch (Throwable t) {
            TimeTravel.TIMELINE_LOGGER.error("Failed to record Better Combat attack", t);
        }
    }

    public void replay(AttackJournal.Entry e, ServerPlayer clone) {
        try {
            Object packet = c2sCtor.newInstance(e.comboCount, e.isSneaking, e.selectedSlot, e.cursorTarget, e.entityIds);
            ServerGamePacketListenerImpl conn = clone.connection;
            MinecraftServer server = clone.getServer();
            if (conn == null || server == null) return;

            boolean old = (boolean) useVanillaPacket.get(null);
            try {
                useVanillaPacket.set(null, false);
                handleAttackRequest.invoke(null, packet, server, clone, conn);
            } finally {
                useVanillaPacket.set(null, old);
            }
        } catch (Throwable t) {
            TimeTravel.TIMELINE_LOGGER.error("Failed to replay Better Combat attack", t);
        }
    }
}
