# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

### [Unreleased]

### Added

 - Combat roll, Better Combat and Emotecraft compat
 - Gliders, Immersive Aircraft and Civillian Aviation compat
 - Iron's Spells 'n Spellbooks

### Changed

 - Combat roll, Better Combat and Emotecraft compat
 - Timeline hud

### Fixed

 - Paradox death chain
 - oldSfx delay

## [1.21.1-0.11.2] - 2026-11-22

### Added

 - Mod API (wip) 
 - Past Selves attribute, fishing and ender pearl tracking

### Changed

 - Past Self category in client-side mod config

### Fixed

 - Ender Dragon bossbar incorrect update though timeline & unskippable death cutscene
 - Mobs tame/sit/collar/anger rewind
 - Saddle remain on horse after travel back in time before taming the horse

## [1.21.1-0.11.1] - 2026-11-21

### Added

 - Past self time jump vfx

### Changed

 - New time jump sfx by Remedy Hearts

### Fixed

 - doDaylightCycle=false gamerule does nothing
 - Spawners duplicating mobs on replay 

## [1.21.1-0.11.0] - 2026-11-20

### Added

 - Weather bossfight support
 - End Dragon (huge wip) and Warden (mid wip) bossfights support

### Fixed

 - Golems duplicated spawn and flying pumpkin that kills them

## [1.21.1-0.10.8] - 2026-11-19

### Added

 - Mod config

### Changed

 - More accurate variable and method namings
 - Optimized past selves, blocks, items, redstone, mobs, vehicle tracks

### Fixed

 - Items restoration when jumping forward after an item destruction event

## [1.21.1-0.10.7] - 2026-11-19

### Added

 - Dropped items record

### Changed

 - Code structure refactoring (it wont increase the performance but speed up the development!)

### Fixed

 - Past selves get hurt when jumping through time in caves 
 - Past selves do not deal enough damage to break carts/boats
 - Loot duplication on containers destruction

## [1.21.1-0.10.6] - 2026-11-18

### Added

 - Past selves now tracking player's hp

### Fixed

 - Mobs loot did not disappear if the player skipped the moment in time at which they were picked up
 - Creeper's explosion ignores past selves shields
 - Reduced chance of time desync of mobTrailJournal

## [1.21.1-0.10.5] - 2026-11-18

### Added

 - WeatherJournal that records and rewinds weather

### Fixed

 - Vehicle trajectories lost on rejoin & proper timeline protection
 - Vehicle container polled twice per tick
 - Past selves voices is playing even if the world is paused

## [1.21.1-0.10.4] - 2026-11-18

### Added

 - MobStateJournal that records and rewinds mobs nbt state more precisely (villager trades, enderman blocks, equipment, taming etc)

### Fixed

 - Leaves rewind when cut them with shears

## [1.21.1-0.10.3] - 2026-11-18

### Fixed

 - Fixed desync when killing an out-of-time mob while other clones are replaying

## [1.21.1-0.10.2] - 2026-11-17

### Added

 - Text when attempting to kill a doomed mob

### Fixed

 - Desync hat occurs when killing a mob that will canonically die later
 - Dropped items will not disappear if the player missed the event from which they appeared

## [1.21.1-0.10.1] - 2026-11-17

### Changed
 - Journals optimization (O(1) entity lookup & cached state/nbt parsing)

## [1.21.1-0.10.0] - 2026-11-17

### Added

 - Item frames, pictures, armor stands and end crystals tracking

### Changed
 - File `timetravel_rewritten_armor_stands.json` replaced by `timetravel_rewritten_entity_states.json` so the old file need to be renamed

### Fixed

 - Furnace-like blocks data dupe after jumping forward in time

### [Contributors]

 - Fl1s

## [1.21.1-0.9.5] - 2026-11-17

### Changed

 - /timetravel reset confirm -> /timetravel reset
 - Hotkeys category rename

### Fixed

 - Loot and xp pickup by past selves
 - Xp travelling back in time
 - Drop duplication
 - Mobs from unloaded chunks registration
 - Creeper explosion rewind 
 - Skeleton bow attack stacking over multiple time travels

### [Contributors]

- Fl1s

## [1.21.1-0.8.9] - 2026-11-16

### Added

 - Predetermined doomed mobs death position and slow approach to it

### Changed

 - Better past selves advancement tracking
 - Optimized simple voice chat
 - Timeline spawn queue

### Fixed

 - Infinite cooldown after re-entering the world 
 - Record arrows, lingering potions, and other consumables
 - TNT and its cascading changes (affected blocks)
 - Redstone and mechanisms animation & state update
 - Record blocks data
 - Time travel data leak across the worlds
 - Container dupe
 - Timeline mob spawning

### [Contributors]
 - Fl1s
