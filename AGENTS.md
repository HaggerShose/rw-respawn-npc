# AGENTS.md -- rw-respawn-npc

Rising World server plugin (Unity API **0.9.3**): an admin targets an NPC and saves a snapshot. After that NPC dies, a one-shot timer starts; when it fires a new NPC is spawned at the **admin's register/update pose** and the snapshot is applied (settable fields only).

Chat with the user in German. Code, identifiers, and commits in English. ASCII punctuation in files (`--`, `...`, `->`); German umlauts in prose are fine.

Javadoc: local under `RisingWorld/Data/SDK`, online at <https://javadoc.rising-world.net/latest/>

## Desired flow (v1)

```text
1. Admin stands where the NPC should respawn (position + facing)
2. Looks at an NPC, or stands near one (LoS first, else nearest within 10)
3. /make-respawn 60
4. Plugin stores own respawn_id + current npc id + spawn pose from player + full snapshot + interval
5. NPC dies (NpcDeathEvent, not cancelled)
6. Plugin starts a one-shot timer (if none is pending)
7. Timer fires -> spawnNpc(type, variant, player-saved pose, persistent) -> apply snapshot -> store new npc id
```

No continuous poll. Idle entries cost almost nothing. Players install nothing.

The original snapshot is kept. Death never overwrites it (do not save a corpse as the new template).

## Commands (v1)

Admins only: `player.isAdmin()` (`Server_Admins` in `server.properties`). Extra UIDs in `ALLOWED_UIDS`. Otherwise ignore silently (no reply, do not cancel the event).

Admin commands reply only to the executing admin. Auto-respawn is silent (no chat).

No command-name overlap with RespawnChest (`/make-refill`, `/refill-*`).

| Command                           | Effect                                                                                                               |
| --------------------------------- | -------------------------------------------------------------------------------------------------------------------- |
| `/make-respawn <minutes>`         | Register target NPC; NPC state = snapshot, **your** pose = spawn. Already registered: error (use `/respawn-update`). |
| `/respawn-update`                 | Snapshot only (live NPC attributes). Optional token: `snapshot`.                                                     |
| `/respawn-update pose`            | Spawn pose only from your position/rotation.                                                                         |
| `/respawn-update all`             | Snapshot + spawn pose.                                                                                               |
| `/respawn-update timer <minutes>` | Interval only (pending timer not restarted).                                                                         |
| `/respawn-update #id ...`         | Same modes by respawn id (no LoS).                                                                                   |
| `/respawn-now [#id]`              | Spawn replacement immediately. If the current NPC still lives, delete it (no corpse) after a successful spawn.       |
| `/respawn-remove [#id]`           | Remove from DB, kill pending timer. Living NPC stays.                                                                |
| `/respawn-info [#id]`             | Interval, pending yes/no (+ remaining), spawn/current position, type/name                                            |
| `/respawn-list`                   | All entries: id, type/name, alive/dead, current pos, spawn pos, pending                                              |

Focus: `Player.getNpcInLineOfSight(10f, callback)`; if null, nearest non-transient NPC within 10 blocks (`World.getAllNpcs`). Optional `#id` / `id` skips focus (`/respawn-now|remove|info|update`). `/make-respawn` always uses focus. `/respawn-list` lists all.

Reject:

- transient NPCs
- no NPC in focus or nearby (except `/respawn-list` and `#id` forms)
- `/make-respawn` without a minutes argument
- `/make-respawn` on an already registered NPC
- `/respawn-update` with unknown tokens / `timer` without minutes
- snapshot/`all` when the current NPC body is missing or dead

Interval in minutes: `0` (or less) -> effective test seconds (`MIN_TEST_SECONDS`). Else `minutes * 60`, cap **86400** (one day). Stored as `interval_seconds` (effective delay).

**Not in v1:** loot chances, YAML tables, admin UI, periodic always-on respawn, corpse cleanup.

## API path

```text
getNpcInLineOfSight(10f)
  -> if null: nearest NPC within 10f
  -> reject null / transient
  -> NpcSnapshot.capture(npc, player.pos, player.rot)
  -> INSERT respawn_npcs (own PK)
```

- Spawn pose from the admin player at register / `pose` / `all` (not from the NPC). Default `/respawn-update` does not change pose.
- Spawn: `World.spawnNpc(typeID, variant, position, rotation, false)` (persistent).
- Apply only fields with setters. Capture also stores secondary item + pregnant (no setters).
- Clothes: `getClothes().serialize()` / `deserialize(bytes)`.
- Skin: read/write gender, variation, colors, hairstyle, beard (null-safe for animals).
- Equipped: `setEquippedItem(id, variant, status, value, durability, modifier)`.
- Behaviour / attack reaction: if overridden flag was saved, `set*`; else `reset*`.
- `/respawn-now` / double-spawn guard: if `World.getNpc(current_npc_id)` is alive, `delete()` (no corpse) **after** a successful spawn.
- Death from that delete: ignore (`ignoringDeathNpcIds` + map unbind) so it does not start a timer.
- Commands: `PlayerCommandEvent`; on admin handling `setCancelled(true)`.

Trigger: `NpcDeathEvent`. Cancelled deaths do not schedule a respawn.

```text
NpcDeathEvent not cancelled
  -> ignore-set hit: drop and return
  -> RAM map miss: return (no SQLite)
  -> find row; ghost: remove from map
  -> if next_respawn == null: next_respawn = now + interval, one-shot timer
  -> further deaths while pending: do not restart timer
  -> timer: spawn + apply + rebind current_npc_id + next_respawn = null
```

At most one pending `net.risingworld.api.Timer` per `respawn_id` (`repetitions = 0`). `/respawn-remove` and `onDisable` kill timers.

On startup: load rows into `npcId -> respawnId` map; if `next_respawn` due -> spawn immediately, else schedule remaining delay; if `next_respawn` is null and `World.getNpc(current_npc_id)` is missing/dead -> spawn immediately (crash recovery). Register the event listener last.

## Persistence: SQLite

`getSQLiteConnection(getPath() + "/respawn.db")`. `PRAGMA foreign_keys = ON`. Prefer `PRAGMA journal_mode=DELETE` so a copied `respawn.db` alone is usable. Checkpoint on disable (`PRAGMA wal_checkpoint(TRUNCATE)`).

A RAM `Map<Long, Long>` (`current_npc_id` -> `respawn_id`) filters death events. SQLite remains source of truth. Sync: put after successful insert/rebind, remove in `drop` / before replacing the npc id.

```text
respawn_npcs:
  respawn_id PK AUTOINCREMENT,   -- stable identity
  current_npc_id,                -- current embodiment, changes after spawn
  type_id, variant, type_name,
  pos_x/y/z, rot_x/y/z/w,        -- spawn pose from admin player
  interval_seconds,
  next_respawn,                  -- unix ms, NULL = idle (alive / not waiting)
  created_at,
  -- snapshot (register / update only):
  name, health, hunger, thirst, taming, age,
  behaviour, behaviour_overridden,
  attack_reaction, attack_reaction_overridden,
  group_id,
  locked, npc_static, invincible, invisible, interactable, collider_enabled,
  footstep_sound, idle_sound, alert_sound,
  eq_* , clothes BLOB, skin_*,
  sec_* (capture-only), pregnant (capture-only)
```

### Schema evolution

No migration runner. `CREATE TABLE IF NOT EXISTS` is the target schema. After CREATE, call `SqliteSchema.ensureColumn` for each column added later so old `respawn.db` files pick it up.

Copy [`_tools/templates/SqliteSchema.java`](../_tools/templates/SqliteSchema.java) into the plugin package (already present as `SqliteSchema.java`) and change the package line. `ensureColumn` is idempotent (`PRAGMA table_info`, then `ALTER TABLE ... ADD COLUMN` only if missing). Keep the call permanently -- it also covers an old db copied onto a new server.

## Scope

v1: one plugin class (commands + death + timers), Snapshot, SQLite (`RespawnRepository`).

No framework layers, no client mods.

## Build / Setup

- Java **20** (`pom.xml` source/target) -- RW Unity API runs on JDK 20. JAR name `RespawnNpc`. Deploy: `plugins/RespawnNpc/RespawnNpc.jar`.
- `plugin.yml` must live in the JAR as `resources/plugin.yml` (`src/main/resources/resources/plugin.yml`), or RW will not load the plugin.
- Dependency: `net.rising-world:plugin-api:0.9.3` (`provided`). Install once into local `.m2` via workspace bootstrap (not on every Maven run).
- After an RW update, bump `<rw.plugin.api.version>` / `api:` and refresh `.m2` from the workspace root:

```powershell
.\_tools\bootstrap-libs.ps1 -P rw-respawn-npc
cd rw-respawn-npc; mvn -B package
```

PowerShell: always quote `-D...` args.

## Code conventions

- Package: `de.mahagst.risingworld.respawnnpc`.
- Smallest sensible change. LF line endings.
- `notes.txt` = operator notes for API/bootstrap updates (paths assume workspace root); this file is the spec.

## Agent notes

- Read Javadoc 0.9.3 before API calls.
- Own `respawn_id` is the PK. NPC global ID is only the current body.
- No global continuous poll. Capture never runs on death.
- Auto-respawn stays silent. Commands only for server admins.
- Death hot path: RAM map first; SQLite only on hit.
- Secondary item and pregnancy are stored, not restored.
- Spawn pose from player at register / `pose` / `all`; default `/respawn-update` is snapshot only.
- LoS then nearest within 10; `#id` for update by respawn id.
