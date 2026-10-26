package com.fl1s.rewritten.timetravel.client;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import java.util.List;

public final class ExemptRenderTypes extends RenderType {

    private ExemptRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize,
                              boolean affectsCrumbling, boolean sortOnUpload, Runnable setup, Runnable clear) {
        super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setup, clear);
    }

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(TimeTravel.MOD_ID, "textures/misc/exempt_glint.png");

    private static final TexturingStateShard TEXTURING = new TexturingStateShard(
            TimeTravel.MOD_ID + "_exempt_glint_texturing", () -> scroll(32.0F), RenderSystem::resetTextureMatrix);

    public static final RenderType ITEM = layer("exempt_glint", new TextureStateShard(TEXTURE, true, false),
            RENDERTYPE_GLINT_SHADER, GLINT_TRANSPARENCY, EQUAL_DEPTH_TEST, NO_CULL, TEXTURING, COLOR_WRITE);

    public static final RenderType ITEM_TRANSLUCENT = layer("exempt_glint_translucent", new TextureStateShard(TEXTURE, true, false),
            RENDERTYPE_GLINT_SHADER, GLINT_TRANSPARENCY, EQUAL_DEPTH_TEST, NO_CULL, TEXTURING, COLOR_WRITE, ITEM_ENTITY_TARGET);

    public static final RenderType BLOCK = layer("exempt_block_glint", new TextureStateShard(TEXTURE, true, false),
            RENDERTYPE_GLINT_SHADER, GLINT_TRANSPARENCY, LEQUAL_DEPTH_TEST, CULL, POLYGON_OFFSET_LAYERING, TEXTURING, COLOR_WRITE);

    private static RenderType layer(String name, RenderStateShard... shards) {
        List<RenderStateShard> states = List.of(shards);
        return new ExemptRenderTypes(TimeTravel.MOD_ID + "_" + name, DefaultVertexFormat.POSITION_TEX,
                VertexFormat.Mode.QUADS, 1536, false, false,
                () -> states.forEach(RenderStateShard::setupRenderState),
                () -> states.forEach(RenderStateShard::clearRenderState));
    }

    public static List<RenderType> all() {
        return List.of(ITEM, ITEM_TRANSLUCENT, BLOCK);
    }

    public static RenderType itemFor(RenderType type) {
        return Minecraft.useShaderTransparency() && type == Sheets.translucentItemSheet() ? ITEM_TRANSLUCENT : ITEM;
    }

    public static boolean isGlint(RenderType type) {
        return type == ITEM || type == ITEM_TRANSLUCENT || type == BLOCK || type == glint() || type == glintTranslucent()
                || type == entityGlint() || type == entityGlintDirect() || type == armorEntityGlint();
    }

    private static void scroll(float scale) {
        long time = (long) (Util.getMillis() * Minecraft.getInstance().options.glintSpeed().get() * 8.0);
        float u = (float) (time % 110000L) / 110000.0F;
        float v = (float) (time % 30000L) / 30000.0F;
        Matrix4f matrix = new Matrix4f().translation(-u, v, 0.0F);
        matrix.rotateZ((float) (Math.PI / 18)).scale(scale);
        RenderSystem.setTextureMatrix(matrix);
    }
}
