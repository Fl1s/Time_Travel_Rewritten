package com.fl1s.rewritten.timetravel.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TimelineCommands {
    public static final Logger TIMELINE_COMMANDS_LOGGER = LoggerFactory.getLogger("TimelineCommands");

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> commandBuilder = Commands.literal("timetravel");

        BaseCommands.register(commandBuilder);
        ExemptCommands.register(commandBuilder);
        SettingsCommands.register(commandBuilder);

        LiteralCommandNode<CommandSourceStack> node = dispatcher.register(commandBuilder);
        dispatcher.register(Commands.literal("tt").redirect(node));
    }
}
