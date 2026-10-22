package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.journal.BlockJournal;
import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.time.HeadScope;
import com.fl1s.rewritten.timetravel.time.HeadScopeSupport;
import com.fl1s.rewritten.timetravel.util.DoomedFeedback;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {

    @Shadow
    @Final
    protected ServerPlayer player;

    @Unique
    private boolean tt$enteredDestroy = false;

    @Unique
    private boolean tt$scopedUseItemOn = false;

    @Unique
    private boolean tt$scopedUseItem = false;

    @Unique
    private boolean tt$scopedDestroy = false;

    @Unique
    private boolean tt$openScope() {
        long head = HeadScopeSupport.headFor(player);
        if (head <= 0) return false;
        HeadScope.push(head);
        return true;
    }

    @Unique
    private int tt$lastSequence = 0;

    @Unique
    private boolean tt$isRealPlayer() {
        return player != null && !Clones.is(player);
    }

    @Unique
    private void tt$rejectBreak(BlockPos pos, int sequence) {
        ServerLevel lvl = player.serverLevel();
        player.connection.send(new ClientboundBlockChangedAckPacket(sequence));
        player.connection.send(new ClientboundBlockUpdatePacket(lvl, pos));
        DoomedFeedback.tryShowBlock(player, lvl);

        BlockPos immutable = pos.immutable();
        for (int delay = 1; delay <= 2; delay++) {
            TimeTravel.queueTask(() -> {
                if (player.isRemoved()) return;
                ServerLevel l = player.serverLevel();
                if (l.isLoaded(immutable)) {
                    player.connection.send(new ClientboundBlockUpdatePacket(l, immutable));
                }
            });
        }
    }

    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void tt$enterUseItemOn(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand, BlockHitResult hitResult, CallbackInfoReturnable<InteractionResult> cir) {
        if (tt$isRealPlayer()) BlockJournal.enterAction();
        tt$scopedUseItemOn = tt$openScope();
    }

    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void tt$exitUseItemOn(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand, BlockHitResult hitResult, CallbackInfoReturnable<InteractionResult> cir) {
        if (tt$isRealPlayer()) BlockJournal.exitAction();
        if (tt$scopedUseItemOn) {
            tt$scopedUseItemOn = false;
            HeadScope.pop();
        }
    }

    @Inject(method = "useItem", at = @At("HEAD"))
    private void tt$enterUseItem(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        if (tt$isRealPlayer()) BlockJournal.enterAction();
        tt$scopedUseItem = tt$openScope();
    }

    @Inject(method = "useItem", at = @At("RETURN"))
    private void tt$exitUseItem(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        if (tt$isRealPlayer()) BlockJournal.exitAction();
        if (tt$scopedUseItem) {
            tt$scopedUseItem = false;
            HeadScope.pop();
        }
    }

    @Inject(method = "handleBlockBreakAction", at = @At("HEAD"), cancellable = true)
    private void tt$handleBlockBreakAction(BlockPos pos, ServerboundPlayerActionPacket.Action action, Direction direction, int maxY, int sequence, CallbackInfo ci) {
        if (action != ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) return;
        if (!tt$isRealPlayer()) return;
        tt$lastSequence = sequence;
        if (BlockJournal.isLocked(player.serverLevel(), pos, Blocks.AIR.defaultBlockState())) {
            tt$rejectBreak(pos, sequence);
            ci.cancel();
        }
    }

    @Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true)
    private void tt$enterDestroyBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        tt$enteredDestroy = false;
        if (!tt$isRealPlayer()) return;
        if (BlockJournal.isLocked(player.serverLevel(), pos, Blocks.AIR.defaultBlockState())) {
            tt$rejectBreak(pos, tt$lastSequence);
            cir.setReturnValue(false);
            return;
        }
        BlockJournal.enterAction();
        tt$enteredDestroy = true;
        tt$scopedDestroy = tt$openScope();
    }

    @Inject(method = "destroyBlock", at = @At("RETURN"))
    private void tt$exitDestroyBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (tt$enteredDestroy) {
            tt$enteredDestroy = false;
            BlockJournal.exitAction();
        }
        if (tt$scopedDestroy) {
            tt$scopedDestroy = false;
            HeadScope.pop();
        }
    }
}
