package com.fl1s.rewritten.timetravel.commands;

import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.data.Exempt;
import com.fl1s.rewritten.timetravel.entity.ExemptEntities;
import com.fl1s.rewritten.timetravel.journal.ExemptBlocks;
import com.fl1s.rewritten.timetravel.journal.HeadItemView;
import com.fl1s.rewritten.timetravel.vehicle.HeadVehicleView;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.Collection;

public final class ExemptCommands {
    private ExemptCommands() {
    }

    public static void register(LiteralArgumentBuilder<CommandSourceStack> parentBuilder) {
        parentBuilder.then(Commands.literal("exempt")
                .then(Commands.literal("position")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ExemptCommands::checkPosition)
                                .then(Commands.argument("value", BoolArgumentType.bool())
                                        .executes(context -> setPositions(context, "pos", BoolArgumentType.getBool(context, "value"))))
                                .then(Commands.argument("to", BlockPosArgument.blockPos())
                                        .then(Commands.argument("value", BoolArgumentType.bool())
                                                .executes(context -> setPositions(context, "to", BoolArgumentType.getBool(context, "value")))))))
                .then(Commands.literal("hand")
                        .executes(ExemptCommands::checkHand)
                        .then(Commands.argument("value", BoolArgumentType.bool())
                                .executes(context -> setHand(context, BoolArgumentType.getBool(context, "value")))))
                .then(Commands.argument("targets", EntityArgument.entities())
                        .executes(ExemptCommands::check)
                        .then(Commands.argument("value", BoolArgumentType.bool())
                                .executes(context -> set(context, BoolArgumentType.getBool(context, "value"))))));
    }

    private static int checkPosition(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        BlockPos pos = BlockPosArgument.getBlockPos(context, "pos");
        ExemptBlocks.Reason reason = ExemptBlocks.reason(source.getLevel(), pos);
        String state = reason == null ? " is on the timeline" : switch (reason) {
            case POSITION -> " is an exempt position";
            case BLOCK -> " holds an exempt block";
            case TYPE -> " holds a block exempt through the #timetravel_rewritten:exempt block tag";
        };
        source.sendSuccess(() -> Component.literal(pos.toShortString() + state), false);
        return reason == null ? 0 : 1;
    }

    private static int setPositions(CommandContext<CommandSourceStack> context, String to, boolean value) {
        CommandSourceStack source = context.getSource();
        BlockPos from = BlockPosArgument.getBlockPos(context, "pos");
        BlockPos until = BlockPosArgument.getBlockPos(context, to);
        BoundingBox box = BoundingBox.fromCorners(from, until);
        long volume = (long) box.getXSpan() * box.getYSpan() * box.getZSpan();
        if (volume > ExemptBlocks.MAX_REGION) {
            source.sendFailure(Component.literal("That covers " + volume + " positions - the limit is " + ExemptBlocks.MAX_REGION + " per command"));
            return 0;
        }
        int changed = ExemptBlocks.mark(source.getLevel(), from, until, value);
        if (changed == 0) {
            source.sendFailure(Component.literal(value ? "Those positions are already exempt" : "Those positions are already on the timeline"));
            return 0;
        }
        ExemptBlocks.save();
        source.sendSuccess(() -> Component.literal(changed + (value ? " position(s) are now exempt from time"
                : " position(s) are back on the timeline")), true);
        return changed;
    }

    private static ItemStack heldItem(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) {
            source.sendFailure(Component.literal("Hold the item you want to check in your main hand"));
        }
        return stack;
    }

    private static int checkHand(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ItemStack stack = heldItem(source);
        if (stack.isEmpty()) return 0;
        String state = !Exempt.is(stack) ? " is on the timeline"
                : stack.is(Exempt.ITEMS) ? " is exempt from time (by the #timetravel_rewritten:exempt item tag)"
                : " is exempt from time";
        source.sendSuccess(() -> Component.empty().append(stack.getHoverName()).append(state), false);
        return Exempt.is(stack) ? 1 : 0;
    }

    private static int setHand(CommandContext<CommandSourceStack> context, boolean value) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ItemStack stack = heldItem(source);
        if (stack.isEmpty()) return 0;
        if (!value && stack.is(Exempt.ITEMS)) {
            source.sendFailure(Component.empty().append(stack.getHoverName())
                    .append(" is exempt through the #timetravel_rewritten:exempt item tag - remove it from the tag instead"));
            return 0;
        }
        if (Exempt.is(stack) == value) {
            source.sendFailure(Component.empty().append(stack.getHoverName())
                    .append(value ? " is already exempt from time" : " is already on the timeline"));
            return 0;
        }
        Exempt.mark(stack, value);
        source.sendSuccess(() -> Component.empty().append(stack.getHoverName())
                .append(value ? " is now exempt from time" : " is back on the timeline"), true);
        return 1;
    }

    private static int check(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Collection<? extends Entity> targets = EntityArgument.getEntities(context, "targets");
        if (targets.size() == 1) {
            Entity entity = targets.iterator().next();
            String state = !Exempt.is(entity) ? " is on the timeline"
                    : Exempt.byType(entity) ? " is exempt from time (by the #timetravel_rewritten:exempt tag)"
                    : " is exempt from time";
            source.sendSuccess(() -> Component.empty().append(entity.getDisplayName()).append(state), false);
            return Exempt.is(entity) ? 1 : 0;
        }
        int exempt = 0;
        for (Entity entity : targets) {
            if (Exempt.is(entity)) exempt++;
        }
        int count = exempt;
        source.sendSuccess(() -> Component.literal(count + " of " + targets.size() + " targets are exempt from time"), false);
        return count;
    }

    private static int set(CommandContext<CommandSourceStack> context, boolean value) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        int changed = 0, players = 0, copies = 0, byType = 0;
        for (Entity entity : EntityArgument.getEntities(context, "targets")) {
            if (entity instanceof Player || Clones.is(entity)) {
                players++;
            } else if (HeadVehicleView.isCopy(entity) || HeadItemView.isCopy(entity)) {
                copies++;
            } else if (!value && Exempt.byType(entity)) {
                byType++;
            } else if (ExemptEntities.set(entity, value)) {
                changed++;
            }
        }

        StringBuilder notes = new StringBuilder();
        if (players > 0) notes.append("\n").append(players).append(" player(s) skipped - players always carry their own time");
        if (copies > 0) notes.append("\n").append(copies).append(" past copy(s) skipped - exempt the original instead");
        if (byType > 0) notes.append("\n").append(byType)
                .append(" target(s) stay exempt through the #timetravel_rewritten:exempt tag");
        if (changed == 0) {
            source.sendFailure(Component.literal((value ? "Nothing new to exempt" : "Nothing to return to the timeline") + notes));
            return 0;
        }
        int count = changed;
        source.sendSuccess(() -> Component.literal((value ? count + " target(s) are now exempt from time"
                : count + " target(s) are back on the timeline") + notes), true);
        return count;
    }
}
