# RespawnNpc

Server-side Rising World plugin that turns an NPC into a quiet respawn point.

Stand where it should come back, target an NPC (look at it, or stand nearby), register once. After it dies, a timer starts and a new copy is spawned at **your** saved position and facing. Players do not need to install anything.

This is not a perfect clone: every readable field is stored, but only fields with setters are restored. Secondary hand item and pregnancy are saved for later API versions and ignored on spawn.

## How it works

1. Stand at the desired respawn spot and face the direction you want.
2. Look at an NPC, or stand close if it is roaming (within 10 blocks).
3. Run `/make-respawn <minutes>`.
4. When that NPC dies, a one-shot timer starts.
5. When the timer ends, a new NPC is spawned at the **saved player pose** and the snapshot is applied.
6. Optional: if a guard post is set, the NPC walks there shortly after spawn and locks facing on arrival. If it wanders off, it is sent back. If it enters combat, the walk is paused until the fight ends.

Notes:

- The snapshot is taken at register / `/respawn-update` time, never on death.
- Spawn pose comes from the admin's position and rotation at register (or `/respawn-update pose` / `all`).
- Guard post is independent (`/make-guard`); `/respawn-update pose` does not change it.
- Default `/respawn-update` only refreshes the NPC snapshot; spawn pose stays.
- While a timer is already running, further deaths do not restart it.
- Respawn itself is silent (no broadcast).
- Only the admin who runs a command gets chat feedback.

## Commands

Admin only (`Server_Admins` in `server.properties`).

Target the NPC first (except `/respawn-list` and optional `#id` / `id` forms), then use chat or the `^` console **with** a leading `/`.

| Command                           | Effect                                                                                                                                                                         |
| --------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `/make-respawn <minutes>`         | Register the target NPC. NPC state = snapshot; your pose = spawn. Already registered: error (use `/respawn-update`).                                                           |
| `/respawn-update`                 | Snapshot only (attributes of the live NPC).                                                                                                                                    |
| `/respawn-update pose`            | Spawn pose only (your position/rotation).                                                                                                                                      |
| `/respawn-update all`             | Snapshot + spawn pose.                                                                                                                                                         |
| `/respawn-update timer <minutes>` | Interval only.                                                                                                                                                                 |
| `/respawn-update #2 ...`          | Same modes, target by respawn id (no LoS needed).                                                                                                                              |
| `/respawn-now [#id]`              | Spawn immediately. If the NPC is still alive, it is deleted (no corpse) after a successful spawn.                                                                              |
| `/respawn-remove [#id]`           | Unregister. The living NPC stays.                                                                                                                                              |
| `/respawn-info [#id]`             | Show interval, pending state, remaining time, spawn/current position.                                                                                                          |
| `/respawn-list`                   | List all registered NPCs (state, spawn/current pose, interval).                                                                                                                |
| `/respawn-list timer`             | List active guard checks (ON/off, interval, covered `#id`s).                                                                                                                   |
| `/make-guard <id>`                | Guard post = your position (+ rotation). NPC walks there, then faces and locks. If it leaves later, it walks back. Combat pauses the walk until the NPC is no longer fighting. |
| `/guard-remove <id>`              | Clear guard post. NPC stays put.                                                                                                                                               |

Optional `snapshot` token: `/respawn-update snapshot` equals bare `/respawn-update`.

`#id` and bare `id` both work for `now` / `remove` / `info` / guard. `/respawn-update` keeps the `#id` form.

### Interval

- `<minutes>` is the delay after death until respawn.
- `0` (or less) -> short test delay (see plugin constant).
- Maximum: **24 hours** (`1440` minutes).

### Rules

- Transient NPCs cannot be registered.
- Target = LoS first, else nearest NPC within 10 blocks (unless `#id` is given).
- On respawn the **saved** pose and snapshot are used, not the death location or corpse state.
- Snapshot update needs a living NPC; pose/timer work with `#id` even if the current body is missing.

## Install

Put the jar here and restart the server:

```text
Plugins/RespawnNpc/RespawnNpc.jar
```

State is stored automatically in:

```text
Plugins/RespawnNpc/<WorldName>.db
```

Each world gets its own SQLite file (from `World.getName()`).
On startup, stored overdue respawns (pending `next_respawn` in the past) wait ~30s, then spawn through the same timer path as a normal due. A failed spawn deletes the default body (if any) and retries every 30s (`pending (retry)`). Idle registered NPCs that are currently missing are not spawned (the body may only be unloaded).

## License

MIT -- see [LICENSE](LICENSE).
