package com.fl1s.rewritten.timetravel.mixin;

import net.minecraft.world.entity.monster.warden.WardenSpawnTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(WardenSpawnTracker.class)
public interface WardenSpawnTrackerAccessor {
    @Invoker("copyData")
    void tt$copyData(WardenSpawnTracker other);
}
