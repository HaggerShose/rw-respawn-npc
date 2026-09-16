# Guard subpackage

Package: `de.mahagst.risingworld.respawnnpc.guard`

Logic here (`GuardFeature`, `GuardService`, `GuardPost`). All SQLite lives in `RespawnRepository` (`respawn.db`):

```text
respawn_npcs
guard_posts   -- FK CASCADE on respawn delete
```

## v0.1

| Command              | Effect                                                                                 |
| -------------------- | -------------------------------------------------------------------------------------- |
| `/make-guard <id>`   | Post = your xyz + rotation (stored). Living NPC `moveTo` once; facing not applied yet. |
| `/remove-guard <id>` | Clear post. NPC stays.                                                                 |

RAM map for hot path. DB only on enable load / make / remove.
After respawn, `moveTo` is delayed ~0.5s (same-tick walk often slides).
