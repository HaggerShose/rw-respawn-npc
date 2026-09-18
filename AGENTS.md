# AGENTS.md -- rw-respawn-npc

Rising World server plugin (Unity API **0.9.3**): register an NPC with a snapshot and spawn pose; after death a one-shot timer respawns it. Optional guard post: after spawn the NPC walks there via `moveTo` (pose separate from spawn). If it leaves, a slow return tick sends it back. If it fights, walk is cancelled until it is no longer alerted.

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

Wiring on enable: open `<World.getName()>.db` -> createSchema -> `RespawnService` + `GuardService` -> `setGuardHooks` (body replaced, removed, pending) -> `respawn.loadMaps()` -> `guard.loadPosts()` -> `respawn.start()` -> `guard.startWalks()` -> register plugin command listener. Load failure: `abortEnable()` (disable both, close DB); do not start timers/walks.

No second plugin, no `AdminAccess` class, no separate guard.db.

## Desired flow

```text
1. Admin stands where the NPC should respawn (position + facing)
2. Looks at an NPC, or stands near one (LoS first, else nearest within 10)
3. /make-respawn 60
4. Plugin stores respawn_id + current npc id + spawn pose from player + snapshot + interval
5. Optional: /make-guard <id> at the watch post (xyz + rotation stored; NPC walks there once)
6. NPC dies (NpcDeathEvent, not cancelled)
7. One-shot timer (if none pending); guard drops this id from proximity bands
8. Timer -> spawnNpc at spawn pose -> apply snapshot -> rebind npc id
9. If guard post exists: settle 2s, then `moveTo` post
10. At post (`xz dist <= 0.1`): turn + lock. Watch removed.
11. Later: away (unlocked, not at post) -> 10s return tick -> walk again
12. Player near post (160m) -> medium; walking/at-post NPC with player within 56m -> fast
    `isAlerted` -> combat (cancel walk, unlock). 12s until !alerted -> medium/away.
```

Death never overwrites the snapshot. Players install nothing.

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
| `/respawn-now [#id]`              | Spawn replacement now. If live body exists, `delete()` after successful spawn (no corpse). Failed now schedules a 30s retry (same as overdue startup).              |
| `/respawn-remove [#id]`           | Drop DB row + timer + guard post (FK CASCADE + RAM clear). Living NPC stays.                                                                                      |
| `/respawn-info [#id]`             | Interval, pending / pending-retry / due-no-timer, spawn/current pos, type/name.                                                                                   |
| `/respawn-list`                   | All entries (one chat block: state, spawn/now, interval). `pending Xs` = RAM timer before due; `pending (retry)` = due with a live retry timer; `due, no timer` = DB `next_respawn` without a live timer. |
| `/respawn-list timer`             | Active guard ticks (return / idle / medium / fast / combat / walk far / walk near) with interval and covered ids.                                                 |
| `/make-guard <id>`                | Guard post = your xyz + rotation. Living NPC walks there; on arrive facing+lock.                                                                                  |
| `/guard-remove <id>`              | Clear guard post. NPC not moved.                                                                                                                                  |

Focus: LoS 10f, else nearest non-transient within 10 (`World.getAllNpcsInRange`). Optional `#id` or bare `id` for `now` / `remove` / `info` / guard. `/respawn-update` id form is `#id` only. `/make-respawn` always needs focus. `/respawn-list` lists all.

Reject: transient; no focus (except list / id forms); `/make-respawn` without minutes or already registered; bad `/respawn-update` tokens; snapshot/`all` when body missing/dead; guard without living body.

Interval: `0` or less -> `MIN_TEST_SECONDS`. Else cap minutes at `MAX_INTERVAL_SECONDS / 60`, then `* 60` (cap **86400**). Stored as `interval_seconds`.

**Not in scope:** loot tables, admin UI, always-on periodic respawn, corpse cleanup.

## API notes

- Spawn pose from admin at register / `pose` / `all` (not from the NPC). DB stores yaw degrees only; spawn/arrive use `fromAngles(0, yaw, 0)`.
- Spawn: `World.spawnNpc(typeID, variant, position, rotation, false)` (persistent).
- Apply only fields with setters. Secondary item + pregnant: capture-only.
- Clothes serialize/deserialize; skin null-safe for animals; equipped with Modifier.
- Behaviour / attack reaction: `set*` if overridden flag saved, else `reset*`.
- `/respawn-now`: unbind old npc id from RAM **before** `delete()`, so the death event is ignored.
- Commands: single `PlayerCommandEvent` on the plugin; `setCancelled(true)` when handled.
- Enqueued timer work is bound to a generation (respawn) or Watch identity (guard). Cancel invalidates leftover enqueue.

```text
NpcDeathEvent (RespawnService)
  -> map miss / already pending: return
  -> next_respawn = now + interval (abort if DB write fails)
  -> one-shot Timer (generation); onPending -> guard drops proximity + watch
  -> onRespawnDue: consume timer, then spawn + apply + completeRespawn + rebind + guard onBodyReplaced
  -> spawn / DB-read failure: delete the default body if any, keep next_respawn, scheduleRetry 30s (list: pending (retry))
```

At most one pending `Timer` per `respawn_id`. Startup: `loadMaps` one `findAll()` into RAM (fail = do not start). Then after guard posts are loaded, `start` resumes pending death timers (overdue -> `scheduleRetry` 30s, else remaining delay; both fire `onPending`). Never spawn overdue bodies synchronously in `start` (world may not be ready; a default NPC without snapshot can leak). Do not spawn missing idle bodies (`getNpc == null` may mean unloaded chunk).

### Guard runtime (RAM only)

Status: `combat` / `walking` (watch present) / `at post` / `away`. Combat and pending skip walk/return.

Walk: spawn/make/startWalks -> `moveTo` post -> far poll (2s, all watches) until within 1m -> that id joins `nearWatches` -> near poll (0.25s, **only** that set) until arrive (`dist^2 <= 0.1^2` xz) -> step-turn on the Watch -> lock + `setPosition` post -> `stopWatch`. Re-`moveTo` after 30s without arrive (API has no active-moveTo query; new call assumed to replace target -- if the engine later reports progress, couple retry to stalled movement). Watch only while walking (incl. turn).

Return: 10s tick while posts exist; living `away` (not pending/combat/watch) -> `startWalk` unless `isAlerted` (then combat).

Combat bands while posts exist:

- idle 30s: player-to-post; within 160m -> `medium` (skip pending/combat)
- medium 2s: keep while player at post; promote to `fast` only if walking or at post **and** player within 56m of the NPC
- fast 0.25s: `isAlerted` -> `enterCombat` (cancel walk via `moveTo` current pos, unlock, drop watch)
- combat 12s: missing body or `!isAlerted` -> `leaveCombat(id, null)`: drop combat, put in `medium`, stay `away` until return tick
- `isAlerted` is also gated immediately before `moveTo` and before lock
- `onBodyReplaced` -> `leaveCombat(id, newBody)`: settle+walk if a post exists
- `onPending` -> pending set, stop watch, drop medium/fast/combat until a new body

Startup: at post -> lock; off-post -> walk; pending/missing body skipped; existing settle watch from spawn is not overwritten.

`livingNpcForRespawn` already returns only living NPCs; guard ticks do not call `isDead()` again on that result. Position is read once per walk check. Player-in-radius uses early-exit (`hasPlayerWithin`).

## Persistence: SQLite

One file per world: `getPath() + "/" + World.getName() + ".db"` (path-unsafe chars in the name become `_`). `PRAGMA foreign_keys = ON`, `journal_mode=DELETE`. Close on disable (no WAL checkpoint).

RAM: `npcIdToRespawnId` plus `byRespawnId` (`RespawnState`: npc id, interval, timer, generation). Death filter and `livingNpcForRespawn` use RAM (no full-row DB). Guard posts: RAM map in `GuardService` (hot path); DB only on enable load / make / remove. Guard watches (`respawn_id -> Watch`) only while walking. Combat bands: RAM sets `medium` / `fast` / `combat` / `nearWatches` / `pending`.

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
