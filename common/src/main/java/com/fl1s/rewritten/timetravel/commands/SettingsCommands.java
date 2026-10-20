package com.fl1s.rewritten.timetravel.commands;

import com.fl1s.rewritten.timetravel.data.PlayerData;
import com.fl1s.rewritten.timetravel.data.Skin;
import com.fl1s.rewritten.timetravel.util.TimeFormat;
import com.fl1s.rewritten.timetravel.TimeTravel;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.fl1s.rewritten.timetravel.types.SkinTypes;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;

public class SettingsCommands {
    public static void register(LiteralArgumentBuilder<CommandSourceStack> parentBuilder) {
        LiteralArgumentBuilder<CommandSourceStack> settingsNode = Commands.literal("settings");
        settingsNode.executes(SettingsCommands::showAll);

        settingsNode.then(Commands.literal("maxJump")
                .executes(context -> {
                    long ticks = TimeTravel.maxJumpTicks;
                    context.getSource().sendSuccess(() -> Component.literal(ticks == 0
                            ? "Max jump length: unlimited"
                            : "Max jump length: " + TimeFormat.clock(ticks)), false);
                    return 1;
                })
                .then(Commands.argument("time", StringArgumentType.word())
                        .suggests(CommandUtils::TimeSuggestion)
                        .executes(context -> {
                            try {
                                long ticks = CommandUtils.parseDurationToTicks(StringArgumentType.getString(context, "time"));
                                TimeTravel.maxJumpTicks = ticks;
                                TimeTravel.config.maxJumpTicks = ticks;
                                TimeTravel.config.save();
                                context.getSource().sendSuccess(() -> Component.literal(ticks == 0
                                        ? "Max jump length: unlimited"
                                        : "Max jump length is set to: " + TimeFormat.clock(ticks)), true);
                                return 1;
                            } catch (IllegalArgumentException e) {
                                context.getSource().sendFailure(Component.literal(e.getMessage()));
                                return 0;
                            }
                        })));

        settingsNode.then(Commands.literal("jumpCooldown")
                .executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal("jumpCooldown: " + TimeTravel.jumpCooldownTicks + " ticks"), false);
                    return 1;
                })
                .then(Commands.argument("ticks", IntegerArgumentType.integer(0, 1200))
                        .executes(context -> {
                            int v = IntegerArgumentType.getInteger(context, "ticks");
                            TimeTravel.jumpCooldownTicks = v;
                            TimeTravel.config.jumpCooldownTicks = v;
                            TimeTravel.config.save();
                            context.getSource().sendSuccess(() -> Component.literal("jumpCooldown is set to: " + v + " ticks"), true);
                            return 1;
                        })));

        settingsNode.then(Commands.literal("preload")
                .executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal(
                            "Clone preload: " + TimeTravel.cloneLookaheadTicks + " ticks (0 = off)."), false);
                    return 1;
                })
                .then(Commands.argument("ticks", IntegerArgumentType.integer(0, 100))
                        .executes(context -> {
                            int ticks = IntegerArgumentType.getInteger(context, "ticks");
                            TimeTravel.cloneLookaheadTicks = ticks;
                            TimeTravel.config.cloneLookaheadTicks = ticks;
                            TimeTravel.config.save();
                            context.getSource().sendSuccess(() -> Component.literal("Clone preload is set to: " + ticks + " ticks"), true);
                            return 1;
                        })));

        LiteralArgumentBuilder<CommandSourceStack> voiceNode = Commands.literal("voice");
        voiceNode.then(Commands.literal("distance")
                .executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal("voiceDistance: " + TimeTravel.voiceAudioDistance + " blocks (used if SVC's own max distance is unavailable)"), false);
                    return 1;
                })
                .then(Commands.argument("blocks", FloatArgumentType.floatArg(1.0f, 1000.0f))
                        .executes(context -> {
                            float v = FloatArgumentType.getFloat(context, "blocks");
                            TimeTravel.voiceAudioDistance = v;
                            TimeTravel.config.voiceAudioDistance = v;
                            TimeTravel.config.save();
                            context.getSource().sendSuccess(() -> Component.literal("voiceDistance is set to: " + v), true);
                            return 1;
                        })));

        voiceNode.then(Commands.literal("threshold")
                .executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal("voiceThreshold: " + TimeTravel.voiceInteractionThresholdDb + " dB (clone voice louder than this triggers sculk/warden events)"), false);
                    return 1;
                })
                .then(Commands.argument("db", IntegerArgumentType.integer(-127, 0))
                        .executes(context -> {
                            int v = IntegerArgumentType.getInteger(context, "db");
                            TimeTravel.voiceInteractionThresholdDb = v;
                            TimeTravel.config.voiceInteractionThresholdDb = v;
                            TimeTravel.config.save();
                            context.getSource().sendSuccess(() -> Component.literal("voiceThreshold is set to: " + v + " dB"), true);
                            return 1;
                        })));

        voiceNode.then(Commands.literal("cooldown")
                .executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal("voiceCooldown: " + TimeTravel.voiceInteractionCooldownTicks + " ticks"), false);
                    return 1;
                })
                .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 1200))
                        .executes(context -> {
                            int v = IntegerArgumentType.getInteger(context, "ticks");
                            TimeTravel.voiceInteractionCooldownTicks = v;
                            TimeTravel.config.voiceInteractionCooldownTicks = v;
                            TimeTravel.config.save();
                            context.getSource().sendSuccess(() -> Component.literal("voiceCooldown is set to: " + v + " ticks"), true);
                            return 1;
                        })));

        settingsNode.then(voiceNode);

        var targetPlayerArg = Commands.argument("targetPlayer", StringArgumentType.string())
                .suggests((context, builder) -> CommandUtils.PlayerSuggestion(builder));

        var newNameArg = Commands.argument("newName", StringArgumentType.string())
                .executes(context -> modifyPlayer(context, SkinTypes.NAME));

        for (SkinTypes skinType : SkinTypes.values()) {
            String commandName = skinType.getCommandName();

            switch (skinType) {
                case FILE -> newNameArg.then(Commands.literal(commandName)
                        .then(Commands.argument("newSkin", StringArgumentType.greedyString())
                                .suggests(CommandUtils::SkinSuggestion)
                                .executes(context -> modifyPlayer(context, skinType))));
                default -> newNameArg.then(Commands.literal(commandName)
                        .then(Commands.argument("newSkin", StringArgumentType.string())
                                .executes(context -> modifyPlayer(context, skinType))));
            }
        }

        settingsNode.then(Commands.literal("modifyPlayer").then(targetPlayerArg.then(newNameArg)));

        TogglesCommands.register(settingsNode);

        parentBuilder.then(settingsNode);
    }

    private static int showAll(CommandContext<CommandSourceStack> context) {
        String text = String.join("\n",
                "Time Travel settings (/timetravel settings toggles <option> <value>)",
                "syncDayTime: " + TimeTravel.syncDayTime,
                "internalTime: " + TimeTravel.internalTime,
                "jumpRequiresOp: " + TimeTravel.jumpRequiresOp,
                "chainDeath: " + TimeTravel.chainDeath,
                "trackItems: " + TimeTravel.trackItems,
                "trackChat: " + TimeTravel.trackChat,
                "hurtClones: " + TimeTravel.hurtPastSelves,
                "showTimer: " + TimeTravel.showTimelineInfo,
                "timerInTicks: " + TimeTravel.displayTimeInTicks,
                "maxJump: " + (TimeTravel.maxJumpTicks == 0 ? "unlimited" : TimeFormat.clock(TimeTravel.maxJumpTicks)),
                "preload: " + TimeTravel.cloneLookaheadTicks + " ticks",
                "jumpCooldown: " + TimeTravel.jumpCooldownTicks + " ticks",
                "voice (Simple Voice Chat " + (TimeTravel.voiceChatLoaded ? "found" : "not found") + "): trackVoice=" + TimeTravel.trackVoice
                        + ", voiceIcon=" + TimeTravel.showPlayerVoiceIcon + ", voiceInteraction=" + TimeTravel.voiceInteractionEnabled
                        + ", voiceDistance=" + TimeTravel.voiceAudioDistance + ", voiceThreshold=" + TimeTravel.voiceInteractionThresholdDb
                        + " dB, voiceCooldown=" + TimeTravel.voiceInteractionCooldownTicks + " ticks");
        context.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static int modifyPlayer(CommandContext<CommandSourceStack> context, SkinTypes skinType) {
        CommandSourceStack source = context.getSource();
        String targetPlayer = StringArgumentType.getString(context, "targetPlayer");

        // Validate the target player exists in the loop
        PlayerData targetData = TimeTravel.timelineSceneManager.getRecordingPlayer(targetPlayer);
        if (targetData == null) {
            source.sendFailure(Component.literal("Player '" + targetPlayer + "' is not a recording player."));
            return 0;
        }

        String newName = StringArgumentType.getString(context, "newName").replace(" ", "").toLowerCase();
        if (newName.isEmpty()) {
            source.sendFailure(Component.literal("Player name cannot be empty."));
            return 0;
        }

        Skin finalSkin = new Skin();
        String rawSkinValue;
        try {
            rawSkinValue = StringArgumentType.getString(context, "newSkin").replace(" ", "");
        } catch (Exception e) {
            rawSkinValue = newName;
        }

        finalSkin.value = rawSkinValue;

        try {
            String testUrl = rawSkinValue.contains("https://") ? rawSkinValue : "https://" + rawSkinValue;

            URI uri = new URI(testUrl);
            String host = uri.getHost();

            if (host != null && (host.equalsIgnoreCase("mineskin.org") || host.equalsIgnoreCase("minesk.in"))) {
                finalSkin.value = testUrl.replaceFirst("^[a-zA-Z]+://", "");
                finalSkin.skinType = SkinTypes.MINESKIN;
                TimelineCommands.TIMELINE_COMMANDS_LOGGER.info("Detected MineSkin URL {}", finalSkin.value);
            } else {
                String[] filetypes = {".jpg", ".jpeg", ".png"};
                boolean foundFile = false;

                for (String filetype : filetypes) {
                    String filename = rawSkinValue.toLowerCase().endsWith(filetype)
                            ? rawSkinValue
                            : rawSkinValue + filetype;
                    File skinFile = new File(TimeTravel.worldFolder.resolve("mocap_files").resolve("skins").resolve(filename).toString());

                    if (skinFile.isFile()) {
                        TimelineCommands.TIMELINE_COMMANDS_LOGGER.info("Detected skin at {}", skinFile.getPath());
                        finalSkin.value = rawSkinValue;
                        finalSkin.skinType = SkinTypes.FILE;
                        foundFile = true;
                        break;
                    }
                }

                if (!foundFile) {
                    source.sendFailure(Component.literal("Skin '" + rawSkinValue + "' is not a valid MineSkin URL or skin file."));
                    return 0;
                }
            }
        } catch (URISyntaxException | NullPointerException e) {
            // Not a URL - treat as player name skin (default)
            TimelineCommands.TIMELINE_COMMANDS_LOGGER.debug("Skin input '{}' is not a URL, using as player name", rawSkinValue);
        }

        TimeTravel.modifyPlayerAttributes(targetPlayer, newName, finalSkin);
        source.sendSuccess(() -> Component.literal("Modified player " + targetPlayer + " -> '" + newName + "'"), true);
        TimelineCommands.TIMELINE_COMMANDS_LOGGER.info("Modified player {} with name {} and skin {}", targetPlayer, newName, finalSkin.value);
        return 1;
    }
}
