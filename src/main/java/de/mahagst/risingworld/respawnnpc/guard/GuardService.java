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
	/** respawn_id -> guard post (RAM mirror of guard.db) */
	private final Map<Long, GuardPost> posts = new HashMap<>();
	/** respawn_id -> current npc global id (only while a body is bound) */
	private final Map<Long, Long> boundNpcByRespawn = new HashMap<>();

	public void loadPosts(Collection<GuardPost> all) {
		posts.clear();
		boundNpcByRespawn.clear();
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

	/** Remember post in RAM and send NPC there. Caller persists to DB. */
	public void putAndBind(GuardPost post, Npc npc) {
		posts.put(post.respawnId(), post);
		bind(post, npc);
	}

	/** Remove from RAM. Caller deletes from DB. */
	public void removePost(long respawnId) {
		posts.remove(respawnId);
		boundNpcByRespawn.remove(respawnId);
	}

	/**
	 * After body swap: if this respawn has a post in RAM, walk the new NPC there.
	 * No-op (and no DB) when there is no post.
	 */
	public void onBodyReplaced(long respawnId, Npc npc) {
		GuardPost post = posts.get(respawnId);
		if (post == null) {
			boundNpcByRespawn.remove(respawnId);
			return;
		}
		bind(post, npc);
	}

	public void unbindByNpcId(long npcId) {
		boundNpcByRespawn.entrySet().removeIf(e -> e.getValue() == npcId);
	}

	public void stop() {
		posts.clear();
		boundNpcByRespawn.clear();
	}

	private void bind(GuardPost post, Npc npc) {
		if (npc == null || npc.isDead()) {
			return;
		}
		boundNpcByRespawn.put(post.respawnId(), npc.getGlobalID());
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
