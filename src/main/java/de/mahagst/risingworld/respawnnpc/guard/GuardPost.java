package de.mahagst.risingworld.respawnnpc.guard;

/**
 * Persisted guard post for one respawn entry (position + yaw degrees).
 * Independent from spawn pose on respawn_npcs.
 */
public record GuardPost(
		long respawnId,
		float x,
		float y,
		float z,
		float yaw) {
}
