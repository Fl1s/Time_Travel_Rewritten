package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.journal.BlockJournal;
import com.fl1s.rewritten.timetravel.journal.RedstoneJournal;
import com.fl1s.rewritten.timetravel.TimeTravel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelTicks.class)
public abstract class LevelTicksMixin<T> {
    @Inject(method = "schedule", at = @At("HEAD"), cancellable = true)
    private void tt$schedule(ScheduledTick<T> tick, CallbackInfo ci) {
        if (BlockJournal.isApplying()) {
            if (!(tick.type() instanceof Block applied) || !RedstoneJournal.isOutput(applied.defaultBlockState())) ci.cancel();
            return;
        }
        if (!TimeTravel.isLooping) return;
        if (!(tick.type() instanceof Block block)) return;
        ServerLevel level = RedstoneJournal.currentLevel();
        if (level == null) level = TimeTravel.serverLevel;
        if (level == null) return;
        long delay = tick.triggerTick() - level.getGameTime();
        if (delay < 0) delay = 0;
        RedstoneJournal.onSchedule(level, tick.pos(), block, (int) Math.min(Integer.MAX_VALUE, delay));
    }
}