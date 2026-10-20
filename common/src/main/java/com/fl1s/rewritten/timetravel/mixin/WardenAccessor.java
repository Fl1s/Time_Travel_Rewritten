package com.fl1s.rewritten.timetravel.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.monster.warden.Warden;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Warden.class)
public interface WardenAccessor {

    @Accessor("CLIENT_ANGER_LEVEL")
    static EntityDataAccessor<Integer> tt$clientAngerLevel() {
        throw new AssertionError();
    }
}
