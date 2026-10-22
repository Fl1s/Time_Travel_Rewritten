package com.fl1s.rewritten.timetravel.compat.anim;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.net.TimelineNet;
import com.google.gson.Gson;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Constructor;
import java.util.ArrayList;

public final class CombatRollRelay implements RelayAdapter {
    private static final Gson GSON = new Gson();
    private static final ResourceLocation RECORD_ID = ResourceLocation.fromNamespaceAndPath("combat_roll", "publish");

    private final Class<?> particlesClass;
    private final Constructor<?> rollAnimationCtor;
    private final Constructor<?> visualsCtor;

    public CombatRollRelay() {
        try {
            Class<?> rollAnimationClass = Class.forName("net.combat_roll.network.Packets$RollAnimation");
            Class<?> visualsClass = Class.forName("net.combat_roll.client.RollEffect$Visuals");
            this.particlesClass = Class.forName("net.combat_roll.client.RollEffect$Particles");
            this.rollAnimationCtor = rollAnimationClass.getDeclaredConstructor(int.class, visualsClass, Vec3.class);
            this.visualsCtor = visualsClass.getDeclaredConstructor(String.class, particlesClass);
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
            Object visuals = payload.getClass().getMethod("visuals").invoke(payload);
            String animationName = (String) visuals.getClass().getMethod("animationName").invoke(visuals);
            Enum<?> particles = (Enum<?>) visuals.getClass().getMethod("particles").invoke(visuals);
            Vec3 velocity = (Vec3) payload.getClass().getMethod("velocity").invoke(payload);
            return GSON.toJson(new StoredRoll(animationName, particles.name(), velocity.x, velocity.y, velocity.z));
        } catch (Throwable t) {
            TimeTravel.TIMELINE_LOGGER.error("Failed to serialize Combat Roll payload", t);
            return null;
        }
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void replay(String data, int entityId, ServerLevel level) {
        try {
            StoredRoll stored = GSON.fromJson(data, StoredRoll.class);
            if (stored == null) return;
            Object particles = Enum.valueOf((Class<? extends Enum>) particlesClass.asSubclass(Enum.class), stored.particles);
            Object visuals = visualsCtor.newInstance(stored.animationName, particles);
            Object packet = rollAnimationCtor.newInstance(entityId, visuals, new Vec3(stored.vx, stored.vy, stored.vz));
            if (!(packet instanceof CustomPacketPayload payload)) return;

            for (ServerPlayer p : new ArrayList<>(level.players())) {
                if (Clones.is(p)) continue;
                TimelineNet.sendToPlayer(p, payload);
            }
        } catch (Throwable t) {
            TimeTravel.TIMELINE_LOGGER.error("Failed to replay Combat Roll animation", t);
        }
    }

    private record StoredRoll(String animationName, String particles, double vx, double vy, double vz) {
    }
}
