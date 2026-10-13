package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.BlockJournal;
import com.fl1s.rewritten.timetravel.util.Ghosts;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {

    @Shadow
    @Final
    protected ServerPlayer player;

    @Unique
    private boolean tt$isRealPlayer() {
        return player != null && !Ghosts.is(player);
    }

    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void tt$enterUseItemOn(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand, BlockHitResult hitResult, CallbackInfoReturnable<InteractionResult> cir) {
        if (tt$isRealPlayer()) {
            BlockJournal.enterAction();
        }
    }

    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void tt$exitUseItemOn(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand, BlockHitResult hitResult, CallbackInfoReturnable<InteractionResult> cir) {
        if (tt$isRealPlayer()) {
            BlockJournal.exitAction();
        }
    }

    @Inject(method = "useItem", at = @At("HEAD"))
    private void tt$enterUseItem(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        if (tt$isRealPlayer()) {
            BlockJournal.enterAction();
        }
    }

    @Inject(method = "useItem", at = @At("RETURN"))
    private void tt$exitUseItem(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        if (tt$isRealPlayer()) {
            BlockJournal.exitAction();
        }
    }

    @Inject(method = "destroyBlock", at = @At("HEAD"))
    private void tt$enterDestroyBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (tt$isRealPlayer()) {
            BlockJournal.enterAction();
        }
    }

    @Inject(method = "destroyBlock", at = @At("RETURN"))
    private void tt$exitDestroyBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (tt$isRealPlayer()) {
            BlockJournal.exitAction();
        }
    }
}
