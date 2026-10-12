package com.fl1s.rewritten.timetravel.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.fl1s.rewritten.timetravel.TimeFormat;
import com.fl1s.rewritten.timetravel.TimeTravel;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import java.util.concurrent.CompletableFuture;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.List;

public class BaseCommands {
    private enum JumpMode { TO, FORWARD, BACK }

    public static void register(LiteralArgumentBuilder<CommandSourceStack> parentBuilder) {
        parentBuilder.then(Commands.literal("start").executes(BaseCommands::start));
        parentBuilder.then(Commands.literal("stop").executes(BaseCommands::stop));
        parentBuilder.then(Commands.literal("reset")
                .executes(BaseCommands::resetWarning)
                .then(Commands.literal("confirm").executes(BaseCommands::reset)));
        parentBuilder.then(Commands.literal("status").executes(BaseCommands::status));
        parentBuilder.then(Commands.literal("help").executes(BaseCommands::help));
        parentBuilder.then(Commands.literal("jump")
                .then(jumpNode("to", JumpMode.TO))
                .then(jumpNode("forward", JumpMode.FORWARD))
                .then(jumpNode("back", JumpMode.BACK))
                .then(Commands.literal("return").executes(BaseCommands::jumpReturn))
                .then(Commands.literal("mark")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(BaseCommands::suggestMarks)
                                .executes(BaseCommands::jumpMark))));

        parentBuilder.then(Commands.literal("mark")
                .then(Commands.argument("name", StringArgumentType.word()).executes(BaseCommands::setMark)));
        parentBuilder.then(Commands.literal("unmark")
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(BaseCommands::suggestMarks)
                        .executes(BaseCommands::removeMark)));
        parentBuilder.then(Commands.literal("marks").executes(BaseCommands::listMarks));

        parentBuilder.then(Commands.literal("visits")
                .executes(context -> visits(context, null))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((context, builder) -> CommandUtils.PlayerSuggestion(builder))
                        .executes(context -> visits(context, StringArgumentType.getString(context, "player")))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> jumpNode(String literal, JumpMode mode) {
        return Commands.literal(literal)
                .then(Commands.argument("time", StringArgumentType.word())
                        .suggests(CommandUtils::TimeSuggestion)
                        .executes(context -> jump(context, mode)));
    }

    private static int help(CommandContext<CommandSourceStack> context) {
        String text = String.join("\n",
                "Time Travel (/timetravel or /tt)",
                "start | stop | status - start or stop the shared server clock, or check on it",
                "reset confirm - wipe everything and reset the timeline",
                "jump to <time> - jump to an exact server time (e.g. 5m, 1h30m)",
                "jump forward <time> | jump back <time> - jump relative to the current time",
                "jump return - go back to where you were before your last jump",
                "mark <name> | unmark <name> | marks | jump mark <name> - bookmark time point",
                "visits [player] - list a player's recorded visits",
                "settings - show all settings",
                "settings toggles <option> [true/false] - turn an option on or off:",
                "  syncDayTime, jumpRequiresOp, chainDeath, trackItems, trackChat,",
                "  hurtClones, showTimer, timerInTicks",
                "settings maxJump <time> - cap the length of a single jump (0 = no limit)",
                "settings preload <ticks> - how far ahead past selves are preloaded (0 = off)",
                "settings jumpCooldown <ticks> - delay between jumps (0 = none)",
                "settings modifyPlayer <player> <nickname> [type] <skin> - change how a player's past selves look",
                "settings voice trackVoice|icon|interaction [true/false] - requires Simple Voice Chat",
                "settings voice distance|threshold|cooldown <value> - tune past selves voices");
        context.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static int start(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (TimeTravel.isLooping) {
            source.sendFailure(Component.literal("Timeline already running!"));
            return 0;
        }
        if (TimeTravel.serverLevel == null || TimeTravel.mocapController == null) {
            source.sendFailure(Component.literal("Error: server world or Motion Capture is not ready yet."));
            return 0;
        }
        TimeTravel.startLoop();
        source.sendSuccess(() -> Component.literal("Timeline started at " + TimeFormat.clock(TimeTravel.timelineTick)
                + ". Use [ ] to scroll time and Enter to jump."), true);
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!TimeTravel.isLooping) {
            source.sendFailure(Component.literal("Timeline not running"));
            return 0;
        }
        TimeTravel.stopLoop();
        source.sendSuccess(() -> Component.literal("Timeline stopped at " + TimeFormat.clock(TimeTravel.timelineTick)), true);
        return 1;
    }

    private static CompletableFuture<Suggestions> suggestMarks(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(TimeTravel.config.marks.keySet(), builder);
    }

    private static int resetWarning(CommandContext<CommandSourceStack> context) {
        context.getSource().sendFailure(Component.literal(
                "This deletes ALL recorded visits, past selves voices, block/chest/chat history and sets time to 00:00. "
                        + "Run '/timetravel reset confirm' if you are sure."));
        return 0;
    }

    private static int jumpReturn(CommandContext<CommandSourceStack> context) {
        if (TimeTravel.lastJumpOrigin < 0) {
            context.getSource().sendFailure(Component.literal("No previous jump to return to"));
            return 0;
        }
        return runJump(context.getSource(), TimeTravel.lastJumpOrigin);
    }

    private static int jumpMark(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        Long tick = TimeTravel.config.marks.get(name);
        if (tick == null) {
            context.getSource().sendFailure(Component.literal("No mark named '" + name + "' (see /timetravel marks)"));
            return 0;
        }
        return runJump(context.getSource(), tick);
    }

    private static int runJump(CommandSourceStack source, long target) {
        String error = TimeTravel.jumpTo(target, source.getTextName());
        if (error != null) {
            source.sendFailure(Component.literal(error));
            return 0;
        }
        return 1;
    }

    private static int setMark(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        long tick = TimeTravel.timelineTick;
        TimeTravel.config.marks.put(name, tick);
        TimeTravel.config.save();
        context.getSource().sendSuccess(() -> Component.literal("Mark '" + name + "' saved at " + TimeFormat.clock(tick)
                + ". Jump there with /timetravel jump mark " + name), true);
        return 1;
    }

    private static int removeMark(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        if (TimeTravel.config.marks.remove(name) == null) {
            context.getSource().sendFailure(Component.literal("No mark named '" + name + "'"));
            return 0;
        }
        TimeTravel.config.save();
        context.getSource().sendSuccess(() -> Component.literal("Mark '" + name + "' removed"), true);
        return 1;
    }

    private static int listMarks(CommandContext<CommandSourceStack> context) {
        if (TimeTravel.config.marks.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.literal("No marks yet. Save one with /timetravel mark <name>"), false);
            return 1;
        }
        StringBuilder sb = new StringBuilder("Marks:");
        TimeTravel.config.marks.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByValue())
                .forEach(e -> sb.append("\n  ").append(e.getKey()).append(" - ").append(TimeFormat.clock(e.getValue())));
        context.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static int visits(CommandContext<CommandSourceStack> context, String playerFilter) {
        List<String> lines = new java.util.ArrayList<>();
        TimeTravel.loopSceneManager.forEachRecordingPlayer(pd -> {
            if (playerFilter != null && !pd.getName().equalsIgnoreCase(playerFilter)) return;
            for (var v : pd.getVisits()) {
                String how = v.arrivalDir > 0 ? " (came from the past)" : v.arrivalDir < 0 ? " (came from the future)" : "";
                lines.add(String.format("#%03d %s  %s -> %s  (%s)%s%s", v.id, pd.getName(),
                        TimeFormat.clock(v.startTick), TimeFormat.clock(v.endTick),
                        TimeFormat.clock(v.endTick - v.startTick), how, v.deathTick >= 0 ? " [died]" : ""));
            }
            var live = pd.getCurrentVisit();
            if (live != null) {
                lines.add(String.format("#%03d %s  %s -> now  (current)", live.id, pd.getName(), TimeFormat.clock(live.startTick)));
            }
        });
        if (lines.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.literal("No visits recorded yet"), false);
            return 1;
        }
        java.util.Collections.sort(lines);
        List<String> shown = lines.size() > 30 ? lines.subList(lines.size() - 30, lines.size()) : lines;
        String text = "Visits (" + lines.size() + (lines.size() > 30 ? ", last 30 shown" : "") + "):\n" + String.join("\n", shown);
        context.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static int reset(CommandContext<CommandSourceStack> context) {
        TimeTravel.resetTimeline();
        context.getSource().sendSuccess(() -> Component.literal("Timeline reset: time is 00:00:00.0, all recorded visits deleted."), true);
        return 1;
    }

    private static int jump(CommandContext<CommandSourceStack> context, JumpMode mode) {
        CommandSourceStack source = context.getSource();
        long ticks;
        try {
            ticks = CommandUtils.parseDurationToTicks(StringArgumentType.getString(context, "time"));
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal(e.getMessage()));
            return 0;
        }

        long target = switch (mode) {
            case TO -> ticks;
            case FORWARD -> TimeTravel.timelineTick + ticks;
            case BACK -> TimeTravel.timelineTick - ticks;
        };

        String error = TimeTravel.jumpTo(target, source.getTextName());
        if (error != null) {
            source.sendFailure(Component.literal(error));
            return 0;
        }
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        List<String> trackedItems = CommandUtils.getTrackedItemsFromReflection();
        String tracking = trackedItems.isEmpty() ? "" : "Tracking " + CommandUtils.formatList(trackedItems) + ".\n";

        int[] visits = {0};
        TimeTravel.loopSceneManager.forEachRecordingPlayer(pd -> visits[0] += pd.getVisits().size());

        String voice = "Simple Voice Chat: " + (TimeTravel.voiceChatLoaded ? "Installed" : "Not present")
                + " (voice of clones: " + (TimeTravel.trackVoice ? "on" : "off") + ").\n";

        String status = (TimeTravel.isLooping ? "Timeline is running.\n" : "Timeline is not running.\n")
                + "Server time: " + TimeFormat.clock(TimeTravel.timelineTick) + " (tick " + TimeTravel.timelineTick + ").\n"
                + "Recorded visits: " + visits[0] + ", past selves queued: " + TimeTravel.countPendingGhosts() + ".\n"
                + "Sync day time: " + TimeTravel.syncDayTime + ".\n"
                + tracking + voice;

        source.sendSuccess(() -> Component.literal(status), false);
        return 1;
    }
}
