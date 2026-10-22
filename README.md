# Time Travel: Rewritten

[![GitHub Release](https://img.shields.io/badge/GitHub-Release-green?logo=github)](https://github.com/Fl1s/Time_Travel_Rewritten/releases/latest)
[![Static Badge](https://img.shields.io/badge/Modrinth-Release-green?logo=Modrinth)](https://modrinth.com/mod/time-travel-rewritten/versions)

https://github.com/user-attachments/assets/9edb60bc-1a26-46ae-b8ea-485697659877

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
- `jump join <player>` - Match another traveller's time. Requires `individualTime`.
- `jump ... bring <targets>` - Take other players along on your jump, e.g. `jump back 30s bring @a[distance=..8]`. Only players carry their own time, so non-player targets are ignored.
- `jump mark <name>` - Jump to a saved time mark.
- `mark <name>` - Save the current time as a mark.
- `unmark <name>` - Delete a saved mark.
- `marks` - List saved marks.
- `visits [player]` - View recorded player visits.

Time can be specified in ticks (`t`), seconds (`s`), minutes (`m`), hours (`h`), or days (`d`). For example, `5m`, `30s`, or `1200t`.

A **Return to the Present** button appears in the pause menu whenever you are behind the present, and
the same jump is bound to `;` by default (rebindable under Controls). Both confirm with
`Returning to the present...`.

## Settings

Use `/tt settings` to view the current settings.

- `maxJump <time>` - Set the maximum duration of a time jump. `0` disables the limit.
- `jumpCooldown <ticks>` - Set the delay between jumps.
- `preload <ticks>` - Set how far ahead past selves are preloaded.
- `modifyPlayer <player> <nickname> [skin type] <skin>` - Change a recorded player's name and skin.

### Toggles

Use `/tt settings toggles <option> <true|false>` to change an option.

- `individualTime` - Give each player their own position on the timeline instead of moving the whole server together. See below.
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

# Individual time

By default a time jump moves the whole server: everyone's clock changes together and the world
rewinds with it. With `individualTime` on, each player instead carries their own position on the
timeline, so two people can be five minutes apart without dragging anyone along.

What changes for a player in the past:

- They see the past selves recorded at *their* time, and nobody else's. A player at the present sees
  the present's past selves. Neither is forced to the other's time.
- Their recording, deaths, chat and voice are stamped at their own position, so what they do there
  becomes history that anyone who later passes that point will observe.
- Players only see each other while they share a time. Someone five minutes behind you is gone from
  your world until one of you closes the gap, and the same goes for past selves belonging to another
  player's time. Nobody you cannot see can push you or be pushed by you.
- The HUD gains a `Current time` counter under `Server time`, showing the time you are in right now.
- Jumping plays the same effect a past self gets: you fade in at the time you arrive at, while anyone
  left behind in the time you departed from watches you dissolve.
- Everyone's recording is closed and republished in chunks while anyone is behind, so a traveller sees
  the other players' past selves too, not just their own. A chunk is only replayable once it is closed,
  so a player less than two seconds behind the present may not see the very latest history yet.
- `jump join <player>` matches another traveller's time, and `jump ... bring <targets>` takes players
  with you.

The static world rewinds **for the traveller only**. Blocks you are behind are shown to you as they
were at your own position, and as your clock advances you watch the recorded changes happen in front
of you. Recorded explosions, sounds and chest lids replay to you at the moment you reach them. None
of this touches anyone else's world: a player at the present sees the present.

Blocks you are behind are not just drawn as they were, they behave that way for you: you stand on a
floor that existed then even if it has since been mined, and you walk straight through a wall that
was built after you left. Everyone else keeps the present world; the past is only solid for you.

Editing the past cascades forward. Break a block five minutes back and it is broken from that moment
on, including now: the recorded history of that spot after the moment you changed it is erased,
because it no longer happened. Anyone standing between your time and the present sees the block
change under them as the new history reaches them. Breaking something a recorded build still depends
on is refused instead, as it always was.

Items a past self drops come back as real items when your clock reaches the moment they were dropped,
so you can pick up what your past self mined just as you can with the whole server moving together.
They are real entities in the shared world rather than something only you can see, which is what lets
you pick them up - so a player at the present watches them appear too, and the copy your past self
originally collected still exists.

Mobs you have history for are rewound the same way: the live mob is hidden from you and a replay of
its recorded path is streamed to you instead, so an iron golem walks the route it walked back then
rather than wandering around as if nothing happened. A mob that had not spawned yet at your position
is simply absent.

What does **not** rewind: a replayed mob is **scenery**. It cannot be hit and cannot hurt you, because
it is a replay sent to your client rather than a real creature - one mob cannot chase you in the past
while standing still in someone else's present. Mobs that died before your position do not come back
yet, and mobs that were never recorded (too far from any player to be tracked) are shown live. Chest
*contents* are read from the server when you open the chest, so those are the present contents even
though the chest block and its lid are not. Items and vehicles stay shared and live. If you want the
whole world to rewind for everybody, including creatures you can fight, leave `individualTime` off
and let the server move together.

Only players carry a time offset, which is why `bring` reports non-player targets as ignored.

# Support

If you encounter an issue or need help, open an issue on the [GitHub repository](https://github.com/Fl1s/Time_Travel_Rewritten/issues).
