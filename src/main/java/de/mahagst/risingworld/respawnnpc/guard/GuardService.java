package de.mahagst.risingworld.respawnnpc.guard;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import net.risingworld.api.objects.Npc;
import net.risingworld.api.utils.Quaternion;
import net.risingworld.api.utils.Vector3f;

/**
 * In-memory map of guard posts plus small NPC helpers used by {@link GuardFeature}.
 * Hot path never hits the DB; persistence is load on enable / save on make / clear on remove.
 */
public final class GuardService {
	/** respawnId -> post. Cleared on {@link #stop()}. */
	private final Map<Long, GuardPost> posts = new HashMap<>();

	/**
	 * Replace the entire RAM map (typically from {@code findAllGuardPosts} on enable).
	 */
	public void loadPosts(Collection<GuardPost> all) {
		posts.clear();
		for (GuardPost post : all) {
			posts.put(post.respawnId(), post);
		}
	}

	/** @return true if this respawn has a guard post in RAM */
	public boolean hasPost(long respawnId) {
		return posts.containsKey(respawnId);
	}

	/** @return post or null */
	public GuardPost getPost(long respawnId) {
		return posts.get(respawnId);
	}

	/** Live view of all posts (backed by the map; do not mutate while iterating elsewhere). */
	public Collection<GuardPost> allPosts() {
		return posts.values();
	}

	/** Insert or overwrite one post in RAM (after DB save in make-guard). */
	public void putPost(GuardPost post) {
		posts.put(post.respawnId(), post);
	}

	/** Remove one post from RAM (DB clear is separate). */
	public void removePost(long respawnId) {
		posts.remove(respawnId);
	}

	/** Clear all posts (plugin disable). */
	public void stop() {
		posts.clear();
	}

	/**
	 * Horizontal yaw in degrees from the player's look direction (pitch ignored).
	 * If looking straight up/down (xz length ~0), falls back to body rotation yaw.
	 *
	 * @param viewDirection camera forward, may be null
	 * @param bodyRotation  player body rotation fallback, may be null
	 * @return yaw degrees suitable for {@link GuardPost#yaw()} / spawn pose
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
	 * Used during the step-turn arrive animation.
	 */
	public static void faceYaw(Npc npc, float yaw) {
		if (npc == null || npc.isDead()) {
			return;
		}
		npc.setRotation(new Quaternion().fromAngles(0f, yaw, 0f));
	}

	/**
	 * Unlock and un-static if needed, then {@link Npc#moveTo} the post xyz.
	 * Does not change rotation; facing is applied on arrive.
	 */
	public void sendToPost(Npc npc, GuardPost post) {
		if (npc == null || npc.isDead() || post == null) {
			return;
		}
		if (npc.isLocked()) {
			npc.setLocked(false);
		}
		if (npc.isStatic()) {
			npc.setStatic(false);
		}
		npc.moveTo(new Vector3f(post.x(), post.y(), post.z()));
	}

	/**
	 * Best-effort cancel of an active {@code moveTo} by targeting the current position.
	 * The API has no cancel; this overwrites the previous target. Unused in the core walk path
	 * (kept for later combat / interrupt).
	 */
	public static void cancelMoveToHere(Npc npc) {
		if (npc == null || npc.isDead()) {
			return;
		}
		Vector3f pos = npc.getPosition();
		if (pos == null) {
			return;
		}
		npc.moveTo(new Vector3f(pos.x, pos.y, pos.z));
	}
}
