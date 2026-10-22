package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.clone.CloneTracks;
import com.fl1s.rewritten.timetravel.data.Clones;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

@Mixin(ServerEntity.class)
public abstract class ServerEntityMixin {
    @Shadow
    @Final
    private Entity entity;

    @Inject(method = "sendDirtyEntityData", at = @At("HEAD"), require = 0)
    private void tt$attributes(CallbackInfo ci) {
        if (!(entity instanceof ServerPlayer sp) || Clones.is(sp)) return;
        Set<AttributeInstance> dirty = sp.getAttributes().getAttributesToSync();
        if (!dirty.isEmpty()) CloneTracks.onAttributesDirty(sp, dirty);
    }
}
