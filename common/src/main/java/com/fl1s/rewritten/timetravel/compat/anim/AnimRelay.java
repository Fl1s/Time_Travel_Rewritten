package com.fl1s.rewritten.timetravel.compat.anim;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.data.PlayerData;
import com.fl1s.rewritten.timetravel.journal.AnimJournal;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Supplier;

public final class AnimRelay {
    private AnimRelay() {
    }

    public static void init() {
        registerIfPresent("net.combat_roll.network.Packets", CombatRollRelay::new);
        registerIfPresent("net.bettercombat.network.Packets$AttackAnimation", BetterCombatRelay::new);
        registerIfPresent("io.github.kosmx.emotes.common.network.EmotePacket", EmotecraftRelay::new);
        registerIfPresent("io.redspace.ironsspellbooks.network.casting.OnCastStartedPacket", () -> CastRelay.create(true));
        registerIfPresent("io.redspace.ironsspellbooks.network.casting.OnCastFinishedPacket", () -> CastRelay.create(false));
    }

    private static void registerIfPresent(String className, Supplier<RelayAdapter> supplier) {
        try {
            Class.forName(className);
            RelayAdapter adapter = supplier.get();
            if (adapter != null) RelayRegistry.register(adapter);
        } catch (Throwable t) {
            TimeTravel.TIMELINE_LOGGER.debug("Relay not registered for {}", className);
        }
    }

    public static void onIncoming(CustomPacketPayload payload, ServerPlayer player) {
        if (!TimeTravel.isLooping || TimeTravel.timelineSceneManager == null) return;
        if (player == null || Clones.is(player)) return;
        PlayerData pd = TimeTravel.timelineSceneManager.getRecordingPlayer(player.getName().getString());
        if (pd == null || !pd.getActive() || pd.getCurrentVisit() == null) return;

        ResourceLocation id = payload.type().id();
        RelayAdapter adapter = RelayRegistry.get(id);
        if (adapter == null) return;

        String data = adapter.serialize(payload);
        if (data == null) return;

        AnimJournal.record(TimeTravel.timelineTick, pd.getCurrentVisit().id, id, data);
    }
}
