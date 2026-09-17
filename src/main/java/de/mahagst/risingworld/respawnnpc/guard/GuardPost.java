package de.mahagst.risingworld.respawnnpc.guard;

/**
 * One guard watch post bound to a respawn entry.
 * <p>
 * Stored in {@code guard_posts} and mirrored in {@link GuardService} RAM.
 * Independent from the spawn pose on {@code respawn_npcs} (where the NPC is recreated after death).
 *
 * @param respawnId FK / PK matching {@code respawn_npcs.respawn_id}
 * @param x         world X of the stand position
 * @param y         world Y of the stand position
 * @param z         world Z of the stand position
 * @param yaw       facing in degrees (applied on arrive via {@code fromAngles(0, yaw, 0)})
 */
public record GuardPost(
		long respawnId,
		float x,
		float y,
		float z,
		float yaw) {
}
