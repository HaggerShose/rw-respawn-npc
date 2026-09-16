package de.mahagst.risingworld.respawnnpc.guard;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import net.risingworld.api.objects.Npc;
import net.risingworld.api.utils.Vector3f;

/**
 * Runtime guard state. Posts live in RAM; DB is only for load/save.
 * Hot path (respawn body swap) never hits SQLite.
 */
public final class GuardService {
	/** respawn_id -> guard post (RAM mirror of guard_posts) */
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

	public Collection<GuardPost> allPosts() {
		return posts.values();
	}

	/** Remember post in RAM and send NPC there. Caller persists to DB. */
	public void putAndBind(GuardPost post, Npc npc) {
		posts.put(post.respawnId(), post);
		bind(post, npc);
	}

	/** Remove from RAM. Caller deletes from DB. */
	public void removePost(long respawnId) {
		posts.remove(respawnId);
	}

	/**
	 * After body swap: if this respawn has a post in RAM, walk the new NPC there.
	 * No-op (and no DB) when there is no post.
	 */
	public void onBodyReplaced(long respawnId, Npc npc) {
		GuardPost post = posts.get(respawnId);
		if (post == null) {
			return;
		}
		bind(post, npc);
	}

	public void stop() {
		posts.clear();
	}

	private void bind(GuardPost post, Npc npc) {
		if (npc == null || npc.isDead()) {
			return;
		}
		sendToPost(npc, post.x(), post.y(), post.z());
	}

	private static void sendToPost(Npc npc, float x, float y, float z) {
		if (npc.isLocked()) {
			npc.setLocked(false);
		}
		if (npc.isStatic()) {
			npc.setStatic(false);
		}
		npc.moveTo(new Vector3f(x, y, z));
	}
}
