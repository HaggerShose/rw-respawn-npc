# AGENTS.md -- rw-respawn-npc

Rising World server plugin (Unity API **0.9.3**): register an NPC with a snapshot and spawn pose; after death a one-shot timer respawns it. Optional guard post: after spawn the NPC walks there via `moveTo` (pose separate from spawn).

Chat with the user in German. Code, identifiers, and commits in English. ASCII punctuation in files (`--`, `...`, `->`); German umlauts in prose are fine.

Javadoc: local under `RisingWorld/Data/SDK`, online at <https://javadoc.rising-world.net/latest/>

## Architecture

```text
RespawnNpcPlugin   -- entry (plugin.yml main): lifecycle, admin, all commands, LoS/#id focus
RespawnService     -- respawn domain: RespawnState, timers, death -> schedule, spawn/apply
guard/             -- GuardService + GuardPost (runtime only; no Listener)
RespawnRepository  -- SQLite: respawn_npcs + guard_posts
NpcSnapshot        -- capture / apply settable fields
Pose               -- lookYaw / faceYaw (shared; respawn must not import guard)
SqliteSchema       -- ensureColumn for schema evolution (call when adding columns)
```

Wiring on enable: open `<World.getName()>.db` -> createSchema -> `RespawnService` + `GuardService` -> `setGuardHooks` -> `respawn.loadMaps()` -> `guard.loadPosts()` -> `respawn.start()` -> `guard.startWalks()` -> register plugin command listener. Load failure: `abortEnable()` (disable both, close DB); do not start timers/walks.

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
9. If guard post exists: settle 2s, then `moveTo` post
10. At post (`xz dist <= 0.1`): turn + lock. Watch removed. Status checks live position if no watch.
```

Global guard tick every 2s only while at least one watch exists (arrive poll). Death never overwrites the snapshot. Players install nothing.

## Commands

Admins only: `player.isAdmin()` (`Server_Admins`). Otherwise ignore silently (no reply, do not cancel).

Admin commands reply only to the executing admin. Auto-respawn is silent.
Command DB/spawn failures: formatted chat to that admin. Runtime failures (death, timer, spawn): `System.out.println` only.

No name clash with RespawnChest (`/make-refill`, `/refill-*`).

| Command                           | Effect                                                                                                                                                            |
| --------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `/make-respawn <minutes>`         | Register focused NPC; snapshot from NPC, spawn pose = your pose. Already registered: error.                                                                       |
| `/respawn-update`                 | Snapshot only. Optional token: `snapshot`.                                                                                                                        |
| `/respawn-update pose`            | Spawn pose only (your position/rotation). Does **not** touch guard.                                                                                               |
| `/respawn-update all`             | Snapshot + spawn pose (one SQL write).                                                                                                                            |
| `/respawn-update timer <minutes>` | Interval only (pending timer not restarted).                                                                                                                      |
| `/respawn-update #id ...`         | Same modes by respawn id (requires `#`).                                                                                                                          |
| `/respawn-now [#id]`              | Spawn replacement now. If live body exists, `delete()` after successful spawn (no corpse). Failed now does not cancel a still-running death timer.                |
| `/respawn-remove [#id]`           | Drop DB row + timer + guard post (FK CASCADE + RAM clear). Living NPC stays.                                                                                      |
| `/respawn-info [#id]`             | Interval, pending / due-no-timer, spawn/current pos, type/name.                                                                                                   |
| `/respawn-list`                   | All entries (one chat block: state, spawn/now, interval). `pending Xs` = RAM timer; `due, no timer` = DB `next_respawn` without a live timer (restart can retry). |
| `/respawn-list timer`             | Active guard ticks (return / idle / medium / fast / combat / walk far / walk near) with interval and covered ids.                                                  |
| `/make-guard <id>`                | Guard post = your xyz + rotation. Living NPC walks there; on arrive facing+lock.                                                                                  |
| `/guard-remove <id>`              | Clear guard post. NPC not moved.                                                                                                                                  |

Focus: LoS 10f, else nearest non-transient within 10 (`World.getAllNpcsInRange`). Optional `#id` or bare `id` for `now` / `remove` / `info` / guard. `/respawn-update` id form is `#id` only. `/make-respawn` always needs focus. `/respawn-list` lists all.

Reject: transient; no focus (except list / id forms); `/make-respawn` without minutes or already registered; bad `/respawn-update` tokens; snapshot/`all` when body missing/dead; guard without living body.

Interval: `0` or less -> `MIN_TEST_SECONDS`. Else cap minutes at `MAX_INTERVAL_SECONDS / 60`, then `* 60` (cap **86400**). Stored as `interval_seconds`.

**Not in scope yet:** loot tables, admin UI, always-on periodic respawn, corpse cleanup, guard fight/return/leash.

## API notes

- Spawn pose from admin at register / `pose` / `all` (not from the NPC). DB stores yaw degrees only; spawn/arrive use `fromAngles(0, yaw, 0)`.
- Spawn: `World.spawnNpc(typeID, variant, position, rotation, false)` (persistent).
- Apply only fields with setters. Secondary item + pregnant: capture-only.
- Clothes serialize/deserialize; skin null-safe for animals; equipped with Modifier.
- Behaviour / attack reaction: `set*` if overridden flag saved, else `reset*`.
- `/respawn-now`: unbind old npc id from RAM **before** `delete()`, so the death event is ignored.
- Commands: single `PlayerCommandEvent` on the plugin; `setCancelled(true)` when handled.
- Guard core: spawn/make/startWalks -> `moveTo` post -> far poll (2s) until within 1m -> near poll (0.25s) until arrive (`dist^2 <= 0.1^2` xz) -> repeating step-turn on one Watch -> lock + `setPosition` post. Re-`moveTo` after 30s without arrive (API has no active-moveTo query; new call assumed to replace target). Watch only while walking (incl. turn). After lock: `stopWatch`. Status: walking / at post / away / combat. Far tick while watches exist; near tick only while someone is within 1m. Slow 10s return tick while posts exist: living {@code away} -> `startWalk`. Combat bands (while posts exist): idle 30s player-to-post scan; medium 2s from 160m (post); fast 0.25s from 56m (living NPC) checks `isAlerted` then `enterCombat` (cancel walk via `moveTo` current pos, unlock, drop watch). Combat tick 12s until `!isAlerted` / body gone -> leave combat (`away` / return tick). Combat ids skipped by walk/return ticks. RAM only (no DB). Startup: at post -> lock; off-post -> walk; missing body skipped; existing settle watch from spawn is not overwritten.
- Enqueued timer work is bound to a generation (respawn) or Watch identity (guard). Cancel invalidates leftover enqueue.

```text
NpcDeathEvent (RespawnService)
  -> map miss / already pending: return
  -> next_respawn = now + interval (abort if DB write fails)
  -> one-shot Timer (generation)
  -> onRespawnDue: consume timer, then spawn + apply + completeRespawn + rebind + guard onBodyReplaced
  -> spawn failure after consume: next_respawn stays, list shows "due, no timer"
```

At most one pending `Timer` per `respawn_id`. Startup: `loadMaps` one `findAll()` into RAM (fail = do not start). Then after guard posts are loaded, `start` resumes pending death timers (overdue spawn, else remaining delay). Do not spawn missing idle bodies (`getNpc == null` may mean unloaded chunk).

## Persistence: SQLite

One file per world: `getPath() + "/" + World.getName() + ".db"` (path-unsafe chars in the name become `_`). `PRAGMA foreign_keys = ON`, `journal_mode=DELETE`. Close on disable (no WAL checkpoint).

RAM: `npcIdToRespawnId` plus `byRespawnId` (`RespawnState`: npc id, interval, timer, generation). Death filter and `livingNpcForRespawn` use RAM (no full-row DB). Guard posts: RAM map in `GuardService` (hot path); DB only on enable load / make / remove. Guard watches (`respawn_id -> Watch`) only while walking. Combat bands: RAM sets `medium` / `fast` / `combat`.

Writes return false on SQL failure; RAM and success chat update only after a successful write. `completeRespawn` sets `current_npc_id` and clears `next_respawn` in one statement. `/respawn-update all` is one snapshot+pose UPDATE. `findAll` / `findAllGuardPosts` distinguish query failure from an empty table.

```text
respawn_npcs:
  respawn_id PK AUTOINCREMENT,
  current_npc_id, type_id, variant, type_name,
  pos_x/y/z, yaw,                  -- spawn pose (admin; yaw degrees)
  interval_seconds, next_respawn, created_at,
  -- snapshot: name, health, hunger, thirst, taming, age,
  behaviour*, attack_reaction*, group_id,
  locked, npc_static, invincible, invisible, interactable, collider_enabled,
  sounds, eq_*, clothes BLOB, skin_*,
  sec_* (capture-only), pregnant (capture-only)

guard_posts:
  respawn_id PK FK -> respawn_npcs ON DELETE CASCADE,
  pos_x/y/z, yaw                   -- guard post (independent of spawn pose)
```

### Schema evolution

No migration runner. `CREATE TABLE IF NOT EXISTS` + permanent `SqliteSchema.ensureColumn` for columns added later. Copy from [`_tools/templates/SqliteSchema.java`](../_tools/templates/SqliteSchema.java) if regenerating.

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
