package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.client.ExemptRenderTypes;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.SequencedMap;

@Mixin(RenderBuffers.class)
public abstract class RenderBuffersMixin {

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/MultiBufferSource;immediateWithBuffers(Ljava/util/SequencedMap;Lcom/mojang/blaze3d/vertex/ByteBufferBuilder;)Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;"),
            index = 0)
    private SequencedMap<RenderType, ByteBufferBuilder> tt$exemptBuffers(SequencedMap<RenderType, ByteBufferBuilder> buffers) {
        for (RenderType type : ExemptRenderTypes.all()) buffers.putIfAbsent(type, new ByteBufferBuilder(type.bufferSize()));
        return buffers;
    }
}
