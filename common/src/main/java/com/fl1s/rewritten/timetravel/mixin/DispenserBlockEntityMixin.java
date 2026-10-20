package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.time.TimeContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(DispenserBlockEntity.class)
public class DispenserBlockEntityMixin {
    @ModifyVariable(method = "getRandomSlot", at = @At("HEAD"), argsOnly = true)
    private RandomSource tt$seeded(RandomSource random) {
        BlockEntity self = (BlockEntity) (Object) this;
        if (!TimeTravel.isLooping || !(self.getLevel() instanceof ServerLevel)) return random;
        long seed = self.getBlockPos().asLong() * 31L
                + TimeContext.positionIn(self.getLevel()) * 0x9E3779B97F4A7C15L;
        return RandomSource.create(seed);
    }
}
