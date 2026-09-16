# AGENTS.md -- rw-respawn-npc

Rising World server plugin (Unity API **0.9.3**): register an NPC with a snapshot and spawn pose; after death a one-shot timer respawns it. Optional guard post: after spawn the NPC walks there via `moveTo` (pose separate from spawn).

Chat with the user in German. Code, identifiers, and commits in English. ASCII punctuation in files (`--`, `...`, `->`); German umlauts in prose are fine.

Javadoc: local under `RisingWorld/Data/SDK`, online at <https://javadoc.rising-world.net/latest/>

## Architecture

```text
RespawnNpcPlugin   -- entry (plugin.yml main): lifecycle, admin, all commands, LoS/#id focus
RespawnService     -- respawn domain: npc map, timers, death -> schedule, spawn/apply
guard/             -- GuardFeature + GuardService + GuardPost (runtime only; no Listener)
RespawnRepository  -- SQLite: respawn_npcs + guard_posts
NpcSnapshot        -- capture / apply settable fields
SqliteSchema       -- ensureColumn for schema evolution
```

Wiring on enable: open `respawn.db` -> createSchema -> `RespawnService` + `GuardFeature` -> `setGuardHooks(onBodyReplaced, onRespawnRemoved)` -> enable both -> register plugin command listener.

No second plugin, no `AdminAccess` class, no separate guard.db.

## Desired flow

```text
1. Admin stands where the NPC should respawn (position + facing)
2. Looks at an NPC, or stands near one (LoS first, else nearest within 10)
3. /make-respawn 60
4. Plugin stores respawn_id + current npc id + spawn pose from player + snapshot + interval
5. Optional: /make-guard <id> at the watch post (xyz + rotation stored; NPC walks there once)
6. NPC dies (NpcDeathEvent, not cancelled)
7. One-shot timer (if none pending)
8. Timer -> spawnNpc at spawn pose -> apply snapshot -> rebind npc id
9. If guard post exists: after ~0.5s moveTo(guard xyz)  -- delay avoids same-tick slide
```

No continuous poll. Death never overwrites the snapshot. Players install nothing.

## Commands

Admins only: `player.isAdmin()` (`Server_Admins`) or UIDs in `RespawnNpcPlugin.ALLOWED_UIDS`. Otherwise ignore silently (no reply, do not cancel).

Admin commands reply only to the executing admin. Auto-respawn is silent.

No name clash with RespawnChest (`/make-refill`, `/refill-*`).

| Command                           | Effect                                                                                       |
| --------------------------------- | -------------------------------------------------------------------------------------------- |
| `/make-respawn <minutes>`         | Register focused NPC; snapshot from NPC, spawn pose = your pose. Already registered: error.  |
| `/respawn-update`                 | Snapshot only. Optional token: `snapshot`.                                                   |
| `/respawn-update pose`            | Spawn pose only (your position/rotation). Does **not** touch guard.                          |
| `/respawn-update all`             | Snapshot + spawn pose.                                                                       |
| `/respawn-update timer <minutes>` | Interval only (pending timer not restarted).                                                 |
| `/respawn-update #id ...`         | Same modes by respawn id (requires `#`).                                                     |
| `/respawn-now [#id]`              | Spawn replacement now. If live body exists, `delete()` after successful spawn (no corpse).   |
| `/respawn-remove [#id]`           | Drop DB row + timer + guard post (FK CASCADE + RAM clear). Living NPC stays.                 |
| `/respawn-info [#id]`             | Interval, pending, spawn/current pos, type/name.                                             |
| `/respawn-list`                   | All entries.                                                                                 |
| `/make-guard <id>`                | Guard post = your xyz + rotation (stored). Living NPC `moveTo` once. Facing not applied yet. |
| `/guard-remove <id>`              | Clear guard post. NPC not moved.                                                             |

Focus: LoS 10f, else nearest non-transient within 10. Optional `#id` or bare `id` for `now` / `remove` / `info` / guard. `/respawn-update` id form is `#id` only. `/make-respawn` always needs focus. `/respawn-list` lists all.

Reject: transient; no focus (except list / id forms); `/make-respawn` without minutes or already registered; bad `/respawn-update` tokens; snapshot/`all` when body missing/dead; guard without living body.

Interval: `0` or less -> `MIN_TEST_SECONDS`. Else `minutes * 60`, cap **86400**. Stored as `interval_seconds`.

**Not in scope yet:** loot tables, admin UI, always-on periodic respawn, corpse cleanup, guard fight/return/leash, applying guard rotation on arrival.

## API notes

- Spawn pose from admin at register / `pose` / `all` (not from the NPC).
- Spawn: `World.spawnNpc(typeID, variant, position, rotation, false)` (persistent).
- Apply only fields with setters. Secondary item + pregnant: capture-only.
- Clothes serialize/deserialize; skin null-safe for animals; equipped with Modifier.
- Behaviour / attack reaction: `set*` if overridden flag saved, else `reset*`.
- `/respawn-now`: ignore death from that `delete()` via `ignoringDeathNpcIds`.
- Commands: single `PlayerCommandEvent` on the plugin; `setCancelled(true)` when handled.
- Guard: `Npc.moveTo`; unlock/static temporarily for the walk (not written back into snapshot). After respawn, delay ~0.5s before `moveTo`.

```text
NpcDeathEvent (RespawnService)
  -> ignore-set / map miss / already pending: return
  -> next_respawn = now + interval, one-shot Timer
  -> timer: spawn + apply + rebind + guard onBodyReplaced (delayed walk if post in RAM)
```

At most one pending `Timer` per `respawn_id`. Startup: load map; overdue/missing -> spawn; else schedule remaining delay.

## Persistence: SQLite

One file: `getPath() + "/respawn.db"`. `PRAGMA foreign_keys = ON`, `journal_mode=DELETE`. Checkpoint on disable.

RAM map `current_npc_id -> respawn_id` for death filter. Guard posts: RAM map in `GuardService` (hot path); DB only on enable load / make / remove.

```text
respawn_npcs:
  respawn_id PK AUTOINCREMENT,
  current_npc_id, type_id, variant, type_name,
  pos_x/y/z, rot_x/y/z/w,          -- spawn pose (admin)
  interval_seconds, next_respawn, created_at,
  -- snapshot: name, health, hunger, thirst, taming, age,
  behaviour*, attack_reaction*, group_id,
  locked, npc_static, invincible, invisible, interactable, collider_enabled,
  sounds, eq_*, clothes BLOB, skin_*,
  sec_* (capture-only), pregnant (capture-only)

guard_posts:
  respawn_id PK FK -> respawn_npcs ON DELETE CASCADE,
  pos_x/y/z, rot_x/y/z/w           -- guard post (independent of spawn pose)
```

### Schema evolution

No migration runner. `CREATE TABLE IF NOT EXISTS` + permanent `SqliteSchema.ensureColumn` for columns added later (e.g. guard `rot_*`). Copy from [`_tools/templates/SqliteSchema.java`](../_tools/templates/SqliteSchema.java) if regenerating.

## Build / Setup

- Java **20**. JAR name `RespawnNpc`. Deploy: `plugins/RespawnNpc/RespawnNpc.jar`.
- `plugin.yml` in JAR as `resources/plugin.yml` (`src/main/resources/resources/plugin.yml`).
- Dependency: `net.rising-world:plugin-api:0.9.3` (`provided`), bootstrap once into `.m2`.

```powershell
.\_tools\bootstrap-libs.ps1 -P rw-respawn-npc
cd rw-respawn-npc; mvn -B package
```

PowerShell: quote `-D...` args.

## Conventions / agent notes

- Package: `de.mahagst.risingworld.respawnnpc` (+ `.guard`).
- Smallest sensible change. LF only.
- `notes.md` = scratch/API discussion; this file is the spec. `ideas.md` = later guard ideas.
- Read Javadoc 0.9.3 before new API calls.
- `respawn_id` is identity; NPC global id is the current body only.
- Capture never on death. Death hot path: RAM map first.
- Spawn pose != guard post. `/respawn-update pose` never changes guard.
