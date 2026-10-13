package com.fl1s.rewritten.timetravel.mixin;

import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.level.dimension.end.EndDragonFight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(EndDragonFight.class)
public interface EnderDragonFightMixin {
    @Accessor("dragonEvent")
    ServerBossEvent tt$getDragonEvent();
}