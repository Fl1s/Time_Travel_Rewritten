package com.fl1s.rewritten.timetravel.compat.anim;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.net.TimelineNet;
import com.google.gson.Gson;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;

public final class EmotecraftRelay implements RelayAdapter {
    private static final Gson GSON = new Gson();
    private static final ResourceLocation EMOTE_ID = ResourceLocation.fromNamespaceAndPath("emotecraft", "emote");

    private final Class<?> netDataClass;
    private final Constructor<?> builderNoArg;
    private final Constructor<?> builderWithData;
    private final Constructor<?> payloadCtor;
    private final Method readMethod;
    private final Method writeMethod;
    private final Method buildMethod;
    private final Field emoteChannelField;
    private final Field purposeField;
    private final Field playerField;
    private final Field isForcedField;

    public EmotecraftRelay() {
        try {
            Class<?> packetClass = Class.forName("io.github.kosmx.emotes.common.network.EmotePacket");
            Class<?> builderClass = Class.forName("io.github.kosmx.emotes.common.network.EmotePacket$Builder");
            this.netDataClass = Class.forName("io.github.kosmx.emotes.common.network.objects.NetData");

            this.builderNoArg = builderClass.getConstructor();
            this.builderWithData = builderClass.getConstructor(netDataClass);
            this.readMethod = packetClass.getMethod("read", ByteBuffer.class);
            this.writeMethod = packetClass.getMethod("write");
            this.buildMethod = builderClass.getMethod("build");

            Class<?> payloadClass = Class.forName("io.github.kosmx.emotes.arch.network.EmotePacketPayload");
            this.payloadCtor = payloadClass.getConstructor(CustomPacketPayload.Type.class, ByteBuffer.class);

            Class<?> tools = Class.forName("io.github.kosmx.emotes.arch.network.NetworkPlatformTools");
            this.emoteChannelField = tools.getField("EMOTE_CHANNEL_ID");

            this.purposeField = netDataClass.getField("purpose");
            this.playerField = netDataClass.getField("player");
            this.isForcedField = netDataClass.getField("isForced");
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    @Override
    public ResourceLocation recordId() {
        return EMOTE_ID;
    }

    @Override
    public String serialize(CustomPacketPayload payload) {
        try {
            ByteBuffer bytes = (ByteBuffer) payload.getClass().getMethod("bytes").invoke(payload);
            ByteBuffer dup = bytes.duplicate();
            byte[] raw = new byte[dup.remaining()];
            dup.get(raw);

            Object builder = builderNoArg.newInstance();
            Object packet = buildMethod.invoke(builder);
            Object data = readMethod.invoke(packet, ByteBuffer.wrap(raw));
            if (data == null) return null;

            String purpose = ((Enum<?>) purposeField.get(data)).name();
            if (!purpose.equals("STREAM") && !purpose.equals("STOP")) return null;

            boolean forced = isForcedField.getBoolean(data);
            return GSON.toJson(new Stored(purpose, forced, Base64.getEncoder().encodeToString(raw)));
        } catch (Throwable t) {
            TimeTravel.TIMELINE_LOGGER.error("Failed to serialize Emotecraft payload", t);
            return null;
        }
    }

    @Override
    public void replay(String json, int entityId, ServerLevel level) {
        try {
            Stored stored = GSON.fromJson(json, Stored.class);
            if (stored == null) return;

            Entity e = level.getEntity(entityId);
            if (!(e instanceof ServerPlayer clone)) return;

            byte[] raw = Base64.getDecoder().decode(stored.raw);

            Object builder = builderNoArg.newInstance();
            Object packet = buildMethod.invoke(builder);
            Object data = readMethod.invoke(packet, ByteBuffer.wrap(raw));
            if (data == null) return;

            playerField.set(data, clone.getUUID());
            isForcedField.setBoolean(data, stored.forced);

            Object newBuilder = builderWithData.newInstance(data);
            Object newPacket = buildMethod.invoke(newBuilder);
            ByteBuffer out = (ByteBuffer) writeMethod.invoke(newPacket);

            Object channel = emoteChannelField.get(null);
            Object newPayload = payloadCtor.newInstance(channel, out);
            if (!(newPayload instanceof CustomPacketPayload cpp)) return;

            for (ServerPlayer viewer : new ArrayList<>(level.players())) {
                if (Clones.is(viewer)) continue;
                TimelineNet.sendToPlayer(viewer, cpp);
            }
        } catch (Throwable t) {
            TimeTravel.TIMELINE_LOGGER.error("Failed to replay Emotecraft emote", t);
        }
    }

    private record Stored(String purpose, boolean forced, String raw) {
    }
}
