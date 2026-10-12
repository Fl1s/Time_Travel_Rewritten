package com.fl1s.rewritten.timetravel.commands;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import com.fl1s.rewritten.timetravel.TimeTravel;

import java.util.function.Consumer;
import java.util.function.Supplier;

public class TogglesCommands {
    public static void register(LiteralArgumentBuilder<CommandSourceStack> settingsNode) {
        LiteralArgumentBuilder<CommandSourceStack> togglesNode = Commands.literal("toggles");
        LiteralArgumentBuilder<CommandSourceStack> voiceNode = Commands.literal("voice");

        togglesNode.then(toggle("syncDayTime", () -> TimeTravel.syncDayTime, v -> {
            TimeTravel.syncDayTime = v;
            TimeTravel.config.syncDayTime = v;
        }));

        togglesNode.then(toggle("jumpRequiresOp", () -> TimeTravel.jumpRequiresOp, v -> {
            TimeTravel.jumpRequiresOp = v;
            TimeTravel.config.jumpRequiresOp = v;
        }));

        togglesNode.then(toggle("chainDeath", () -> TimeTravel.chainDeath, v -> {
            TimeTravel.chainDeath = v;
            TimeTravel.config.chainDeath = v;
        }));

        togglesNode.then(toggle("trackItems", () -> TimeTravel.trackItems, v -> {
            TimeTravel.trackItems = v;
            TimeTravel.config.trackItems = v;
            TimeTravel.updateEntitiesToTrack(v);
        }));

        togglesNode.then(toggle("trackChat", () -> TimeTravel.trackChat, v -> {
            TimeTravel.trackChat = v;
            TimeTravel.config.trackChat = v;
        }));

        togglesNode.then(toggle("hurtClones", () -> TimeTravel.hurtLoopedPlayers, v -> {
            TimeTravel.hurtLoopedPlayers = v;
            TimeTravel.config.hurtLoopedPlayers = v;
            if (TimeTravel.mocapController != null) {
                TimeTravel.mocapController.setSetting("invulnerable_playback", String.valueOf(!v));
                TimeTravel.mocapController.setSetting("use_creative_game_mode", String.valueOf(!v));
            }
        }));

        voiceNode.then(toggle("trackVoice", () -> TimeTravel.trackVoice, v -> {
            TimeTravel.trackVoice = v;
            TimeTravel.config.trackVoice = v;
        }));

        voiceNode.then(toggle("icon", () -> TimeTravel.showPlayerVoiceIcon, v -> {
            TimeTravel.showPlayerVoiceIcon = v;
            TimeTravel.config.showPlayerVoiceIcon = v;
        }));

        voiceNode.then(toggle("interaction", () -> TimeTravel.voiceInteractionEnabled, v -> {
            TimeTravel.voiceInteractionEnabled = v;
            TimeTravel.config.voiceInteractionEnabled = v;
        }));

        togglesNode.then(toggle("timerInTicks", () -> TimeTravel.displayTimeInTicks, v -> {
            TimeTravel.displayTimeInTicks = v;
            TimeTravel.config.displayTimeInTicks = v;
            TimeTravel.broadcastSync();
        }));

        togglesNode.then(toggle("showTimer", () -> TimeTravel.showLoopInfo, v -> {
            TimeTravel.showLoopInfo = v;
            TimeTravel.config.showLoopInfo = v;
            TimeTravel.broadcastSync();
        }));

        settingsNode.then(togglesNode);
        settingsNode.then(voiceNode);
    }

    static LiteralArgumentBuilder<CommandSourceStack> toggle(String name, Supplier<Boolean> getter, Consumer<Boolean> setter) {
        return Commands.literal(name)
                .executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal(name + " is set to: " + getter.get()), false);
                    return 1;
                })
                .then(Commands.argument("value", BoolArgumentType.bool())
                        .executes(context -> {
                            boolean value = BoolArgumentType.getBool(context, "value");
                            setter.accept(value);
                            TimeTravel.config.save();
                            context.getSource().sendSuccess(() -> Component.literal(name + " is set to: " + value), true);
                            return 1;
                        }));
    }
}
