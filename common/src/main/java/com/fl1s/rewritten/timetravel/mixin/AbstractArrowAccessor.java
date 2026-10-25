package com.fl1s.rewritten.timetravel.mixin;

import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractArrow.class)
public interface AbstractArrowAccessor {
    @Accessor("inGround")
    void tt$setInGround(boolean inGround);

    @Accessor("lastState")
    void tt$setLastState(BlockState state);
}
