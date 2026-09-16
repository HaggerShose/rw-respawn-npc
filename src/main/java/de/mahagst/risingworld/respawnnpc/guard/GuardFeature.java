package de.mahagst.risingworld.respawnnpc.guard;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongFunction;

import de.mahagst.risingworld.respawnnpc.RespawnRepository;
import net.risingworld.api.Plugin;
import net.risingworld.api.Timer;
import net.risingworld.api.World;
import net.risingworld.api.objects.Npc;
import net.risingworld.api.objects.Player;
import net.risingworld.api.utils.Vector3f;

/**
 * Guard runtime: RAM posts, delayed walk after respawn, make/remove helpers.
 * Commands and admin checks live in {@code RespawnNpcPlugin}.
 */
public final class GuardFeature {
	/** Delay after spawn before moveTo; same-frame walk often slides/teleports. */
	private static final float WALK_AFTER_SPAWN_SECONDS = 0.5f;

	private final Plugin plugin;
	private final RespawnRepository repository;
	private final LongFunction<Optional<Npc>> livingNpcByRespawnId;

	private final GuardService service = new GuardService();
	private final Map<Long, Timer> pendingWalks = new HashMap<>();

	public GuardFeature(
			Plugin plugin,
			RespawnRepository repository,
			LongFunction<Optional<Npc>> livingNpcByRespawnId) {
		this.plugin = plugin;
		this.repository = repository;
		this.livingNpcByRespawnId = livingNpcByRespawnId;
	}

	public void enable() {
		service.loadPosts(repository.findAllGuardPosts());
		for (GuardPost post : service.allPosts()) {
			Optional<Npc> live = livingNpcByRespawnId.apply(post.respawnId());
			if (live.isPresent()) {
				service.onBodyReplaced(post.respawnId(), live.get());
			}
		}
		System.out.println("[RespawnNpc/Guard] enabled");
	}

	public void disable() {
		cancelAllPendingWalks();
		service.stop();
	}

	/**
	 * After respawn body swap: schedule walk shortly later.
	 * Calling moveTo in the same tick as spawnNpc tends to slide the NPC instead of walking.
	 */
	public void onBodyReplaced(long respawnId, Npc npc) {
		if (!service.hasPost(respawnId)) {
			cancelPendingWalk(respawnId);
			return;
		}
		scheduleWalkToPost(respawnId, npc.getGlobalID());
	}

	/**
	 * Clear RAM. DB row is removed by FK CASCADE when respawn_npcs row is deleted,
	 * or explicitly via /guard-remove.
	 */
	public void onRespawnRemoved(long respawnId) {
		cancelPendingWalk(respawnId);
		service.removePost(respawnId);
	}

	public void makeGuard(Player player, long respawnId) {
		if (repository.find(respawnId).isEmpty()) {
			player.sendTextMessage("Respawn #" + respawnId + " not found.");
			return;
		}
		Optional<Npc> liveOpt = livingNpcByRespawnId.apply(respawnId);
		if (liveOpt.isEmpty()) {
			player.sendTextMessage("NPC #" + respawnId + " is not alive; cannot set guard.");
			return;
		}
		Npc live = liveOpt.get();
		Vector3f pos = player.getPosition();
		var rot = player.getRotation();
		if (pos == null || rot == null) {
			player.sendTextMessage("Could not read player position.");
			return;
		}
		if (!repository.setGuardPost(respawnId, pos.x, pos.y, pos.z, rot.x, rot.y, rot.z, rot.w)) {
			player.sendTextMessage("Could not save guard post.");
			return;
		}
		cancelPendingWalk(respawnId);
		service.putAndBind(
				new GuardPost(respawnId, pos.x, pos.y, pos.z, rot.x, rot.y, rot.z, rot.w),
				live);
		player.sendTextMessage("Guard post set (#" + respawnId + ") at "
				+ fmtPos(pos.x, pos.y, pos.z) + ". NPC walking there.");
	}

	public void removeGuard(Player player, long respawnId) {
		if (!service.hasPost(respawnId)) {
			player.sendTextMessage("Respawn #" + respawnId + " has no guard post.");
			return;
		}
		cancelPendingWalk(respawnId);
		service.removePost(respawnId);
		repository.clearGuardPost(respawnId);
		player.sendTextMessage("Guard removed (#" + respawnId + ").");
	}

	private void scheduleWalkToPost(long respawnId, long npcId) {
		cancelPendingWalk(respawnId);
		Timer timer = new Timer(1f, WALK_AFTER_SPAWN_SECONDS, 0, () -> plugin.enqueue(() -> {
			pendingWalks.remove(respawnId);
			Npc live = World.getNpc(npcId);
			if (live == null || live.isDead()) {
				return;
			}
			service.onBodyReplaced(respawnId, live);
		}));
		pendingWalks.put(respawnId, timer);
		timer.start();
	}

	private void cancelPendingWalk(long respawnId) {
		Timer timer = pendingWalks.remove(respawnId);
		if (timer != null && !timer.isKilled()) {
			timer.kill();
		}
	}

	private void cancelAllPendingWalks() {
		for (Timer timer : pendingWalks.values()) {
			if (!timer.isKilled()) {
				timer.kill();
			}
		}
		pendingWalks.clear();
	}

	private static String fmtPos(float x, float y, float z) {
		return String.format(Locale.US, "(%.1f, %.1f, %.1f)", x, y, z);
	}
}
