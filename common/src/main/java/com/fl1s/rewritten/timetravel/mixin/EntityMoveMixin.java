package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.time.HeadScope;
import com.fl1s.rewritten.timetravel.time.HeadScopeSupport;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityMoveMixin {

    @Inject(method = "move", at = @At("HEAD"))
    private void tt$enterHead(MoverType type, Vec3 movement, CallbackInfo ci) {
        long head = HeadScopeSupport.headFor((Entity) (Object) this);
        if (head > 0) HeadScope.push(head);
    }

    @Inject(method = "move", at = @At("RETURN"))
    private void tt$exitHead(MoverType type, Vec3 movement, CallbackInfo ci) {
        if (HeadScopeSupport.headFor((Entity) (Object) this) > 0) HeadScope.pop();
    }
}
