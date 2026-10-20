# Time Travel: Rewritten

[![GitHub Release](https://img.shields.io/badge/GitHub-Release-green?logo=github)](https://github.com/Fl1s/Time_Travel_Rewritten/releases/latest)
[![Static Badge](https://img.shields.io/badge/Modrinth-Release-green?logo=Modrinth)](https://modrinth.com/mod/time-travel-rewritten/versions)
[![Static Badge](https://img.shields.io/badge/Curseforge-Release-green?logo=Curseforge)](https://www.curseforge.com/minecraft/mc-mods/time-travel-rewritten/files/all)

A Minecraft mod that lets you travel through a shared timeline and interact with past and future versions of yourself.

Requires [Motion Capture Mod](https://modrinth.com/mod/motion-capture/).
Based on mod [Time Loop](https://modrinth.com/mod/timeloop/), special thanks to [Vowless](https://modrinth.com/user/Vowless) and [luigi052005](https://modrinth.com/user/luigi052005) for this mod and [Remedy Hearts](https://discord.gg/jfCe8fAq3Y) for the sfx!!! 

If a serious bug or time desynchronization occurs, I highly recommend using the /tt reset command!!! Mod is on alpha stage!

# Usage

Use `/timetravel` or `/tt` to control the timeline.

- `start` - Start the timeline.
- `stop` - Stop the timeline.
- `reset confirm` - Reset the timeline and delete recorded history.
- `status` - Display the current timeline status.
- `help` - Show available commands.
- `jump to <time>` - Jump to a specific time.
- `jump forward <time>` - Jump forward by a duration.
- `jump back <time>` - Jump backward by a duration.
- `jump return` - Return to the previous time position.
- `jump present` - Jump to the newest point the timeline has reached.
- `jump join <player>` - Match another traveller's time. Requires `internalTime`.
- `jump mark <name>` - Jump to a saved time mark.
- `mark <name>` - Save the current time as a mark.
- `unmark <name>` - Delete a saved mark.
- `marks` - List saved marks.
- `visits [player]` - View recorded player visits.

Time can be specified in ticks (`t`), seconds (`s`), minutes (`m`), hours (`h`), or days (`d`). For example, `5m`, `30s`, or `1200t`.

## Settings

Use `/tt settings` to view the current settings.

- `maxJump <time>` - Set the maximum duration of a time jump. `0` disables the limit.
- `jumpCooldown <ticks>` - Set the delay between jumps.
- `preload <ticks>` - Set how far ahead past selves are preloaded.
- `modifyPlayer <player> <nickname> [skin type] <skin>` - Change a recorded player's name and skin.

### Toggles

Use `/tt settings toggles <option> <true|false>` to change an option.

- `syncDayTime` - Synchronize the world's time of day with the timeline.
- `jumpRequiresOp` - Configure operator requirements for time jumps.
- `chainDeath` - Configure chained death behavior.
- `trackItems` - Toggle dropped-item tracking.
- `trackChat` - Toggle chat tracking.
- `hurtClones` - Toggle whether recorded player clones can be hurt.
- `showTimer` - Toggle the timeline timer.
- `timerInTicks` - Display the timer in ticks instead of clock format.

### Voice

Requires [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat/).

- `voice trackVoice <true|false>` - Toggle voice recording and playback.
- `voice icon <true|false>` - Toggle speaking indicators.
- `voice interaction <true|false>` - Toggle voice-triggered interactions.
- `voice distance <blocks>` - Set the voice distance.
- `voice threshold <dB>` - Set the voice interaction threshold.
- `voice cooldown <ticks>` - Set the voice interaction cooldown.

# Support

If you encounter an issue or need help, open an issue on the [GitHub repository](https://github.com/Fl1s/Time_Travel_Rewritten/issues).
