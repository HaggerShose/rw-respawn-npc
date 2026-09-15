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

Notes:

- The snapshot is taken at register / `/respawn-update` time, never on death.
- Spawn pose comes from the admin's position and rotation, not from where the NPC was standing.
- `/respawn-update` refreshes both the snapshot and the spawn pose from where you stand.
- While a timer is already running, further deaths do not restart it.
- Respawn itself is silent (no broadcast).
- Only the admin who runs a command gets chat feedback.

## Commands

Admin only (`Server_Admins` in `server.properties`).

Target the NPC first (except `/respawn-list`), then use chat or the `^` console **with** a leading `/`.

| Command                   | Effect                                                                                               |
| ------------------------- | ---------------------------------------------------------------------------------------------------- |
| `/make-respawn <minutes>` | Register the target NPC. NPC state = snapshot; your pose = spawn. Already registered: interval only. |
| `/respawn-update`         | Save the current live NPC as the new snapshot and your pose as the new spawn (pending timer stays).  |
| `/respawn-now`            | Spawn immediately. If the NPC is still alive, it is deleted (no corpse) after a successful spawn.    |
| `/respawn-remove`         | Unregister. The living NPC stays.                                                                    |
| `/respawn-info`           | Show interval, pending state, remaining time, spawn/current position.                                |
| `/respawn-list`           | List all registered NPCs.                                                                            |

### Interval

- `<minutes>` is the delay after death until respawn.
- `0` (or less) -> **5 seconds** (for testing).
- Maximum: **24 hours** (`1440` minutes).

### Rules

- Transient NPCs cannot be registered.
- Target = LoS first, else nearest NPC within 10 blocks.
- `/make-respawn` on an already registered NPC only changes the interval (same interval = no-op). A pending timer is not restarted.
- On respawn the **saved** pose and snapshot are used, not the death location or corpse state.

## Install

Put the jar here and restart the server:

```text
Plugins/RespawnNpc/RespawnNpc.jar
```

State is stored automatically in:

```text
Plugins/RespawnNpc/respawn.db
```

On startup, overdue respawns run immediately. If a registered NPC is missing (e.g. after a crash mid-death), it is spawned again.

## License

MIT -- see [LICENSE](LICENSE).
