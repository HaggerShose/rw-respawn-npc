package de.mahagst.risingworld.respawnnpc;

import net.risingworld.api.objects.Npc;
import net.risingworld.api.utils.Quaternion;
import net.risingworld.api.utils.Vector3f;

/**
 * Horizontal yaw helpers for spawn pose and guard facing.
 */
public final class Pose {
	private Pose() {
	}

	/**
	 * Horizontal yaw in degrees from the player's look direction (pitch ignored).
	 * If looking straight up/down (xz length ~0), falls back to body rotation yaw.
	 *
	 * @param viewDirection camera forward, may be null
	 * @param bodyRotation  player body rotation fallback, may be null
	 * @return yaw degrees suitable for spawn pose / {@code GuardPost#yaw()}
	 */
	public static float lookYaw(Vector3f viewDirection, Quaternion bodyRotation) {
		if (viewDirection != null) {
			float x = viewDirection.x;
			float z = viewDirection.z;
			float lenSq = x * x + z * z;
			if (lenSq >= 1e-8f) {
				float inv = 1f / (float) Math.sqrt(lenSq);
				return new Quaternion().lookAt(x * inv, 0f, z * inv).getYaw();
			}
		}
		return bodyRotation == null ? 0f : bodyRotation.getYaw();
	}

	/**
	 * Snap NPC facing to the given yaw (degrees). No-op if null or dead.
	 */
	public static void faceYaw(Npc npc, float yaw) {
		if (npc == null || npc.isDead()) {
			return;
		}
		npc.setRotation(new Quaternion().fromAngles(0f, yaw, 0f));
	}
}
