package com.fl1s.rewritten.timetravel.compat.anim;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.net.TimelineNet;
import com.google.gson.Gson;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Constructor;
import java.util.ArrayList;

public final class BetterCombatRelay implements RelayAdapter {
    private static final Gson GSON = new Gson();
    private static final ResourceLocation RECORD_ID = ResourceLocation.fromNamespaceAndPath("bettercombat", "attack_animation");

    private final Class<?> swingParticlesClass;
    private final Class<?> animatedHandClass;
    private final Constructor<?> attackAnimationCtor;

    public BetterCombatRelay() {
        try {
            Class<?> attackAnimationClass = Class.forName("net.bettercombat.network.Packets$AttackAnimation");
            this.swingParticlesClass = Class.forName("net.bettercombat.network.Packets$SwingParticles");
            this.animatedHandClass = Class.forName("net.bettercombat.logic.AnimatedHand");
            this.attackAnimationCtor = attackAnimationClass.getDeclaredConstructor(int.class, animatedHandClass, String.class, float.class, float.class, float.class, int.class, swingParticlesClass);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    @Override
    public ResourceLocation recordId() {
        return RECORD_ID;
    }

    @Override
    public String serialize(CustomPacketPayload payload) {
        try {
            Class<?> type = payload.getClass();
            Object animatedHand = type.getMethod("animatedHand").invoke(payload);
            String animationName = (String) type.getMethod("animationName").invoke(payload);
            float length = (Float) type.getMethod("length").invoke(payload);
            float upswing = (Float) type.getMethod("upswing").invoke(payload);
            float weaponRange = (Float) type.getMethod("weaponRange").invoke(payload);
            int upswingTicks = (Integer) type.getMethod("upswingTicks").invoke(payload);
            Object particles = type.getMethod("particles").invoke(payload);
            String particlesJson = GSON.toJson(particles);

            return GSON.toJson(new StoredAttack(((Enum<?>) animatedHand).name(), animationName, length, upswing, weaponRange, upswingTicks, particlesJson));
        } catch (Throwable t) {
            TimeTravel.TIMELINE_LOGGER.error("Failed to serialize Better Combat payload", t);
            return null;
        }
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void replay(String data, int entityId, ServerLevel level) {
        try {
            StoredAttack stored = GSON.fromJson(data, StoredAttack.class);
            if (stored == null) return;
            Object animatedHand = Enum.valueOf((Class<? extends Enum>) animatedHandClass.asSubclass(Enum.class), stored.animatedHand);
            Object particles = GSON.fromJson(stored.particlesJson, swingParticlesClass);
            Object packet = attackAnimationCtor.newInstance(entityId, animatedHand, stored.animationName, stored.length, stored.upswing, stored.weaponRange, stored.upswingTicks, particles);
            if (!(packet instanceof CustomPacketPayload payload)) return;

            for (ServerPlayer p : new ArrayList<>(level.players())) {
                if (Clones.is(p)) continue;
                TimelineNet.sendToPlayer(p, payload);
            }
        } catch (Throwable t) {
            TimeTravel.TIMELINE_LOGGER.error("Failed to replay Better Combat animation", t);
        }
    }

    private record StoredAttack(String animatedHand, String animationName, float length, float upswing,
                                float weaponRange, int upswingTicks, String particlesJson) {
    }
}
