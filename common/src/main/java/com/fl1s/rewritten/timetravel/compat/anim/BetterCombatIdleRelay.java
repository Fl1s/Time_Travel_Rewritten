package com.fl1s.rewritten.timetravel.compat.anim;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.data.Clones;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class BetterCombatIdleRelay {
    private BetterCombatIdleRelay() {
    }

    private static boolean initialized = false;
    private static EntityDataAccessor<String> mainAccessor;
    private static EntityDataAccessor<String> offAccessor;
    private static Method poseForPlayer;
    private static Method poseBase;
    private static Method poseOffHand;
    private static boolean ready;

    private static final Map<ServerPlayer, PoseSnapshot> CACHE = new WeakHashMap<>();

    private record PoseSnapshot(String base, String offHand) {
    }

    @SuppressWarnings("unchecked")
    public static void init() {
        try {
            Class.forName("net.bettercombat.logic.PlayerAttackHelper");

            Field mainF = Class.forName("net.minecraft.world.entity.player.Player").getDeclaredField("BETTER_COMBAT_MAIN_IDLE_ANIMATION");
            Field offF = Class.forName("net.minecraft.world.entity.player.Player").getDeclaredField("BETTER_COMBAT_OFF_IDLE_ANIMATION");
            mainF.setAccessible(true);
            offF.setAccessible(true);
            mainAccessor = (EntityDataAccessor<String>) mainF.get(null);
            offAccessor = (EntityDataAccessor<String>) offF.get(null);

            Class<?> helper = Class.forName("net.bettercombat.logic.PlayerAttackHelper");
            poseForPlayer = helper.getMethod("poseForPlayer", net.minecraft.world.entity.player.Player.class);

            Class<?> poseClass = Class.forName("net.bettercombat.logic.Pose");
            poseBase = poseClass.getMethod("base");
            poseOffHand = poseClass.getMethod("offHand");

            ready = mainAccessor != null && offAccessor != null;
        } catch (Throwable t) {
            ready = false;
        }
    }

    public static void apply(ServerPlayer clone) {
        if (!initialized) {
            initialized = true;
            init();
        }
        if (!ready) return;
        try {
            Object pose = poseForPlayer.invoke(null, clone);
            String base = (String) poseBase.invoke(pose);
            String offHand = (String) poseOffHand.invoke(pose);
            if (base == null) base = "";
            if (offHand == null) offHand = "";

            PoseSnapshot prev = CACHE.get(clone);
            if (prev != null && prev.base().equals(base) && prev.offHand().equals(offHand)) return;
            CACHE.put(clone, new PoseSnapshot(base, offHand));

            clone.getEntityData().set(mainAccessor, base);
            clone.getEntityData().set(offAccessor, offHand);

            if (!(clone.level() instanceof ServerLevel level)) return;

            List<SynchedEntityData.DataValue<?>> values = new ArrayList<>();
            values.add(SynchedEntityData.DataValue.create(mainAccessor, base));
            values.add(SynchedEntityData.DataValue.create(offAccessor, offHand));
            ClientboundSetEntityDataPacket packet = new ClientboundSetEntityDataPacket(clone.getId(), values);

            for (ServerPlayer viewer : level.players()) {
                if (Clones.is(viewer)) continue;
                viewer.connection.send(packet);
            }
        } catch (Throwable t) {
            TimeTravel.TIMELINE_LOGGER.error("Failed to apply Better Combat idle pose", t);
        }
    }
}
