package com.fl1s.rewritten.timetravel.client;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.net.CloneAppearPayload;
import com.fl1s.rewritten.timetravel.net.CloneVanishPayload;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class CloneVanishFx {
    private CloneVanishFx() {
    }

    private static final ResourceLocation GEARS_TEXTURE = ResourceLocation.fromNamespaceAndPath(TimeTravel.MOD_ID, "textures/effect/time_gears.png");
    private static final ResourceLocation CLOCK_TEXTURE = ResourceLocation.fromNamespaceAndPath(TimeTravel.MOD_ID, "textures/effect/time_clock.png");

    private static final int GEAR_FRAMES = 9;
    private static final int CLOCK_FRAMES = 14;
    private static final float FRAME_MS = 45.0F;
    private static final float CLOCK_LAST_FRAME_MS = 1000.0F;
    private static float shadowFactor = 1.0F;
    private static final float SHADOW_FADE_MS = 120.0F;
    private static final float SPRITE_TOTAL_MS = (CLOCK_FRAMES - 1) * FRAME_MS + CLOCK_LAST_FRAME_MS;
    private static final float SPRITE_FADE_MS = 1000.0F;
    private static final float SPRITE_GROW_MS = 50.0F;

    private static final float FLASH_START_MS = 600.0F;
    private static final float FLASH_MS = 250.0F;
    private static final float FADE_START_MS = FLASH_START_MS + FLASH_MS;
    private static final float END_MS = FLASH_START_MS + SPRITE_TOTAL_MS;
    private static final float CLEANUP_MS = 1000.0F;

    private static final float PRE_FLASH_GB = 0.5F;
    private static final float DEEP_RED_GB = 0.04F;

    private static final float SIZE = 1.7F;
    private static final float CLOCK_SCALE_GAIN = 0.15F;
    private static final float CLOCK_TILT_DEGREES = 14.0F;
    private static final float GEARS_DEPTH = 0.02F;
    private static final float FRONT_OFFSET = 0.9F;
    private static final float CHEST_FRACTION = 0.72F;
    private static final float MIN_CENTER_HEIGHT = 1.25F;
    private static final float MS_PER_TICK = 50.0F;

    private static final float APPEAR_MS = 450.0F;
    private static final float APPEAR_FLASH_MS = 250.0F;
    private static final float APPEAR_SPRITE_SPAN = (CLOCK_FRAMES - 1) * FRAME_MS;
    private static final Map<Integer, Effect> appears = new HashMap<>();

    private static final class Effect {
        final int entityId;
        final float startTick;
        Vec3 chest;
        Vec3 anchor;

        Effect(int entityId, float startTick) {
            this.entityId = entityId;
            this.startTick = startTick;
        }
    }

    private static final Map<Integer, Effect> effects = new HashMap<>();
    private static ClientLevel lastLevel;

    private static boolean active;
    private static float bodyAlpha;
    private static float tintGb;
    private static float flash;

    public static void handle(CloneVanishPayload payload) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        if (level != lastLevel) {
            effects.clear();
            lastLevel = level;
        }
        Effect effect = new Effect(payload.entityId(), level.getGameTime() + payload.remainingTicks() - FADE_START_MS / MS_PER_TICK);
        Entity entity = level.getEntity(payload.entityId());
        if (entity != null) effect.chest = chestOf(entity, 1.0F);
        effects.put(payload.entityId(), effect);
    }

    public static void clear() {
        effects.clear();
        appears.clear();
        active = false;
    }

    public static RenderType translucent(RenderType type) {
        if (type == Sheets.solidBlockSheet()
                || type == Sheets.cutoutBlockSheet()
                || type == Sheets.translucentCullBlockSheet()) {
            return Sheets.translucentItemSheet();
        }
        return type;
    }

    public static void handleAppear(CloneAppearPayload payload) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        if (level != lastLevel) {
            effects.clear();
            appears.clear();
            lastLevel = level;
        }
        appears.put(payload.entityId(), new Effect(payload.entityId(), level.getGameTime() + payload.delayTicks()));
    }

    public static boolean active() {
        return active;
    }

    private static float smooth(float x) {
        x = Mth.clamp(x, 0.0F, 1.0F);
        return x * x * (3.0F - 2.0F * x);
    }

    public static void beginShadow(Entity entity, float partialTick) {
        shadowFactor = 1.0F;
        if (effects.isEmpty()) return;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || level != lastLevel) return;
        Effect effect = effects.get(entity.getId());
        if (effect == null) return;
        float ms = (level.getGameTime() + partialTick - effect.startTick) * MS_PER_TICK;
        if (ms < FLASH_START_MS) return;
        shadowFactor = 1.0F - smooth((ms - FLASH_START_MS) / SHADOW_FADE_MS);
    }

    public static float shadow(float original) {
        return original * shadowFactor;
    }

    public static void endShadow() {
        shadowFactor = 1.0F;
    }

    private static Vec3 chestOf(Entity entity, float partialTick) {
        float height = Math.max(entity.getBbHeight() * CHEST_FRACTION, MIN_CENTER_HEIGHT);
        return entity.getPosition(partialTick).add(0.0, height, 0.0);
    }

    public static void begin(LivingEntity entity, float partialTick) {
        active = false;
        if (effects.isEmpty() && appears.isEmpty()) return;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || level != lastLevel) return;

        Effect appear = appears.get(entity.getId());
        if (appear != null) {
            float ms = (level.getGameTime() + partialTick - appear.startTick) * MS_PER_TICK;
            if (ms < 0.0F) {
                bodyAlpha = 0.0F;
                tintGb = PRE_FLASH_GB;
                flash = 0.0F;
                active = true;
                return;
            }
            if (ms <= APPEAR_MS) {
                float u = ms / APPEAR_FLASH_MS;
                flash = u < 1.0F ? (u < 0.3F ? u / 0.3F : 1.0F - (u - 0.3F) / 0.7F) : 0.0F;
                bodyAlpha = smooth(u);
                tintGb = Mth.lerp(smooth(ms / APPEAR_MS), PRE_FLASH_GB, 1.0F);
                active = true;
                return;
            }
        }

        Effect effect = effects.get(entity.getId());
        if (effect == null) return;

        float ms = (level.getGameTime() + partialTick - effect.startTick) * MS_PER_TICK;
        if (ms < 0.0F || ms > END_MS) return;

        if (ms < FLASH_START_MS) tintGb = Mth.lerp(smooth(ms / FLASH_START_MS), 1.0F, PRE_FLASH_GB);
        else if (ms < FADE_START_MS) tintGb = PRE_FLASH_GB;
        else tintGb = DEEP_RED_GB;

        float u = (ms - FLASH_START_MS) / FLASH_MS;
        flash = u > 0.0F && u < 1.0F ? (u < 0.3F ? u / 0.3F : 1.0F - (u - 0.3F) / 0.7F) : 0.0F;

        bodyAlpha = ms < FLASH_START_MS ? 1.0F : 1.0F - smooth((ms - FLASH_START_MS) / FLASH_MS);
        active = true;
    }

    public static void end() {
        active = false;
    }

    public static int tint(int original) {
        if (!active) return original;
        int a = (original >>> 24) & 255;
        int r = (original >> 16) & 255;
        int g = (original >> 8) & 255;
        int b = original & 255;
        a = Math.round(a * bodyAlpha);
        if (TimeTravel.knightOfTimeMode) {
            g = Math.round(g * tintGb);
            b = Math.round(b * tintGb);
        } else {
            float black = tintGb * tintGb;
            r = Math.round(r * black);
            g = Math.round(g * black);
            b = Math.round(b * black);
        }
        if (flash > 0.0F) {
            r = Math.min(255, Math.round(r + (255 - r) * flash * 0.7F));
            g = Math.min(255, Math.round(g + (230 - g) * flash * 0.7F));
            b = Math.min(255, Math.round(b + (230 - b) * flash * 0.7F));
        }
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static int overlay(int original) {
        if (!active || flash <= 0.0F) return original;
        return OverlayTexture.pack(OverlayTexture.u(flash), OverlayTexture.v(false));
    }

    public static void renderWorld(PoseStack poseStack, Vec3 camera, float partialTick) {
        if (effects.isEmpty() && appears.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || level != lastLevel) {
            effects.clear();
            return;
        }
        boolean spritesEnabled = TimeTravel.knightOfTimeMode && poseStack != null;
        MultiBufferSource.BufferSource buffer = spritesEnabled ? mc.renderBuffers().bufferSource() : null;
        float now = level.getGameTime() + partialTick;
        boolean drew = false;

        Iterator<Effect> it = effects.values().iterator();
        while (it.hasNext()) {
            Effect effect = it.next();
            float ms = (now - effect.startTick) * MS_PER_TICK;
            if (ms > END_MS + CLEANUP_MS) {
                it.remove();
                continue;
            }
            if (!spritesEnabled) continue;
            if (effect.anchor == null) {
                Entity entity = level.getEntity(effect.entityId);
                if (entity != null) effect.chest = chestOf(entity, partialTick);
                if (ms >= FLASH_START_MS && effect.chest != null) effect.anchor = effect.chest;
            }
            if (effect.anchor == null || ms < FLASH_START_MS) continue;
            drew |= drawSprites(poseStack, buffer, camera, effect.anchor, ms - FLASH_START_MS);
        }

        Iterator<Effect> ait = appears.values().iterator();
        while (ait.hasNext()) {
            Effect a = ait.next();
            float ms = (now - a.startTick) * MS_PER_TICK;
            if (ms > APPEAR_MS + CLEANUP_MS) { ait.remove(); continue; }
            if (!spritesEnabled || ms < 0.0F || ms > APPEAR_MS) continue;
            Entity entity = level.getEntity(a.entityId);
            if (entity == null) continue;
            float t = (1.0F - ms / APPEAR_MS) * APPEAR_SPRITE_SPAN;
            drew |= drawSprites(poseStack, buffer, camera, chestOf(entity, partialTick), t);
        }
        if (drew) buffer.endBatch();
    }

    private static boolean drawSprites(PoseStack poseStack, MultiBufferSource buffer, Vec3 camera, Vec3 anchor, float t) {
        if (t >= SPRITE_TOTAL_MS) return false;
        float fadeStart = SPRITE_TOTAL_MS - SPRITE_FADE_MS;
        int alpha = Math.round((1.0F - smooth((t - fadeStart) / SPRITE_FADE_MS)) * 255.0F);
        if (alpha <= 0) return false;

        float g = Mth.clamp(t / SPRITE_GROW_MS, 0.0F, 1.0F);
        float grow = 1.0F - (1.0F - g) * (1.0F - g) * (1.0F - g);
        if (grow <= 0.0F) return false;

        int step = (int) (t / FRAME_MS);
        int clockFrame = Math.min(CLOCK_FRAMES - 1, step);
        int gearFrame = step % GEAR_FRAMES;
        float progress = smooth(t / SPRITE_TOTAL_MS);
        float front = Math.min(FRONT_OFFSET, (float) anchor.distanceTo(camera) * 0.5F);

        poseStack.pushPose();
        poseStack.translate(anchor.x - camera.x, anchor.y - camera.y, anchor.z - camera.z);
        poseStack.mulPose(Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation());
        poseStack.translate(0.0F, 0.0F, front);

        poseStack.pushPose();
        float clockScale = grow * (1.0F + CLOCK_SCALE_GAIN * progress);
        poseStack.scale(clockScale, clockScale, 1.0F);
        poseStack.mulPose(Axis.ZP.rotationDegrees(-CLOCK_TILT_DEGREES * progress));
        quad(poseStack, buffer, CLOCK_TEXTURE, clockFrame, CLOCK_FRAMES, 0.0F, alpha);
        poseStack.popPose();

        poseStack.pushPose();
        poseStack.scale(grow, grow, 1.0F);
        quad(poseStack, buffer, GEARS_TEXTURE, gearFrame, GEAR_FRAMES, GEARS_DEPTH, alpha);
        poseStack.popPose();

        poseStack.popPose();
        return true;
    }

    private static void quad(PoseStack poseStack, MultiBufferSource buffer, ResourceLocation texture, int frame, int frames, float z, int alpha) {
        VertexConsumer vc = buffer.getBuffer(RenderType.entityTranslucentEmissive(texture));
        Matrix4f m = poseStack.last().pose();
        float h = SIZE * 0.5F;
        float v0 = (float) frame / frames;
        float v1 = (float) (frame + 1) / frames;
        vertex(vc, m, -h, h, z, 0.0F, v0, alpha);
        vertex(vc, m, -h, -h, z, 0.0F, v1, alpha);
        vertex(vc, m, h, -h, z, 1.0F, v1, alpha);
        vertex(vc, m, h, h, z, 1.0F, v0, alpha);
    }

    private static void vertex(VertexConsumer vc, Matrix4f m, float x, float y, float z, float u, float v, int alpha) {
        vc.addVertex(m, x, y, z).setColor(255, 255, 255, alpha).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(0.0F, 0.0F, 1.0F);
    }
}
