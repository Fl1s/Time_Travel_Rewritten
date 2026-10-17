# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

### [Unreleased]

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
