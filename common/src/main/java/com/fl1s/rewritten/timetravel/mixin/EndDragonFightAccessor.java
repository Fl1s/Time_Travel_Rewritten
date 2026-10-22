package com.fl1s.rewritten.timetravel.mixin;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.level.dimension.end.DragonRespawnAnimation;
import net.minecraft.world.level.dimension.end.EndDragonFight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;
import java.util.UUID;

@Mixin(EndDragonFight.class)
public interface EndDragonFightAccessor {
    @Accessor("needsStateScanning")
    void tt$setNeedsStateScanning(boolean v);

    @Accessor("dragonKilled")
    void tt$setDragonKilled(boolean v);

    @Accessor("previouslyKilled")
    void tt$setPreviouslyKilled(boolean v);

    @Accessor("dragonUUID")
    UUID tt$getDragonUUID();

    @Accessor("dragonUUID")
    void tt$setDragonUUID(UUID v);

    @Accessor("portalLocation")
    void tt$setPortalLocation(BlockPos v);

    @Accessor("gateways")
    ObjectArrayList<Integer> tt$getGateways();

    @Accessor("respawnStage")
    DragonRespawnAnimation tt$getRespawnStage();

    @Accessor("respawnStage")
    void tt$setRespawnStage(DragonRespawnAnimation v);

    @Accessor("respawnCrystals")
    void tt$setRespawnCrystals(List<EndCrystal> v);

    @Accessor("respawnTime")
    void tt$setRespawnTime(int v);

    @Accessor("ticksSinceDragonSeen")
    void tt$setTicksSinceDragonSeen(int v);

    @Accessor("dragonEvent")
    ServerBossEvent tt$getDragonEvent();

    @Invoker("updateCrystalCount")
    void tt$updateCrystalCount();
}
