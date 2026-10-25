package com.fl1s.rewritten.timetravel.mixin;

import net.minecraft.world.entity.monster.Creeper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Creeper.class)
public interface CreeperAccessor {
    @Accessor("swell")
    int tt$getSwell();

    @Accessor("swell")
    void tt$setSwell(int swell);

    @Invoker("explodeCreeper")
    void tt$explode();

    @Accessor("maxSwell")
    int tt$getMaxSwell();

    @Accessor("explosionRadius")
    int tt$getExplosionRadius();
}
