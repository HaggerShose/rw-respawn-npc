package de.mahagst.risingworld.respawnnpc.guard;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import net.risingworld.api.objects.Npc;
import net.risingworld.api.utils.Quaternion;
import net.risingworld.api.utils.Vector3f;

/** Runtime guard posts. RAM only on the hot path; DB is load/save. */
public final class GuardService {
	private final Map<Long, GuardPost> posts = new HashMap<>();

	public void loadPosts(Collection<GuardPost> all) {
		posts.clear();
		for (GuardPost post : all) {
			posts.put(post.respawnId(), post);
		}
	}

	public boolean hasPost(long respawnId) {
		return posts.containsKey(respawnId);
	}

	public GuardPost getPost(long respawnId) {
		return posts.get(respawnId);
	}

	public Collection<GuardPost> allPosts() {
		return posts.values();
	}

	public void putPost(GuardPost post) {
		posts.put(post.respawnId(), post);
	}

	public void removePost(long respawnId) {
		posts.remove(respawnId);
	}

	public void stop() {
		posts.clear();
	}

	/** Facing + lock. No setPosition. */
	public void arrive(Npc npc, GuardPost post) {
		if (npc == null || npc.isDead() || post == null) {
			return;
		}
		npc.setRotation(new Quaternion().fromAngles(0f, post.yaw(), 0f));
		npc.setLocked(true);
	}

	/** Unlock if needed, then moveTo. */
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
}
