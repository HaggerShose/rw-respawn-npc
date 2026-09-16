package de.mahagst.risingworld.respawnnpc.guard;

/**
 * Persisted guard post for one respawn entry (position + rotation).
 * Independent from spawn pose on respawn_npcs. Rotation stored now; applied later.
 */
public record GuardPost(
		long respawnId,
		float x,
		float y,
		float z,
		float rotX,
		float rotY,
		float rotZ,
		float rotW) {
}
