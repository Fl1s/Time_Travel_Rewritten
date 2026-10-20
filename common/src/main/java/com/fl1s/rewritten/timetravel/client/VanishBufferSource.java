package com.fl1s.rewritten.timetravel.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

public final class VanishBufferSource implements MultiBufferSource {
    private final MultiBufferSource inner;

    public VanishBufferSource(MultiBufferSource inner) {
        this.inner = inner;
    }

    @Override
    public VertexConsumer getBuffer(RenderType type) {
        return new Consumer(inner.getBuffer(type));
    }

    private record Consumer(VertexConsumer d) implements VertexConsumer {
        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            d.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            int c = CloneVanishFx.tint((a << 24) | (r << 16) | (g << 8) | b);
            d.setColor((c >> 16) & 255, (c >> 8) & 255, c & 255, (c >>> 24) & 255);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            d.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            int o = CloneVanishFx.overlay(u | (v << 16));
            d.setUv1(o & 0xFFFF, o >>> 16);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            d.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            d.setNormal(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setOverlay(int packed) {
            d.setOverlay(CloneVanishFx.overlay(packed));
            return this;
        }

        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {
            d.addVertex(x, y, z, CloneVanishFx.tint(color), u, v, CloneVanishFx.overlay(overlay), light, nx, ny, nz);
        }
    }
}
