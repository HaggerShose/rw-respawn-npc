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
import net.risingworld.api.utils.Quaternion;
import net.risingworld.api.utils.Vector3f;

/**
 * Guard: spawn settle -> moveTo -> distance ticks -> turn in steps -> lock.
 */
public final class GuardFeature {
	private static final float WALK_AFTER_SPAWN_SECONDS = 2f;
	private static final float ARRIVE_DIST = 0.1f;
	private static final float MIN_INTERVAL = 0.1f;
	private static final float MAX_INTERVAL = 2f;
	private static final int TURN_STEPS = 8;
	private static final float TURN_STEP_SECONDS = 0.05f;
	private static final float LOCK_AFTER_TURN_SECONDS = 0.15f;

	private final Plugin plugin;
	private final RespawnRepository repository;
	private final LongFunction<Optional<Npc>> livingNpcByRespawnId;

	private final GuardService service = new GuardService();
	private final Map<Long, Approach> approaches = new HashMap<>();

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
				onBodyReplaced(post.respawnId(), live.get());
			}
		}
		System.out.println("[RespawnNpc/Guard] enabled");
	}

	public void disable() {
		for (Approach approach : approaches.values()) {
			stopApproachTimer(approach);
		}
		approaches.clear();
		service.stop();
	}

	/** After respawn body: wait, then walk to post. */
	public void onBodyReplaced(long respawnId, Npc npc) {
		if (!service.hasPost(respawnId)) {
			stopApproach(respawnId);
			return;
		}
		stopApproach(respawnId);
		scheduleWalkToPost(respawnId, npc.getGlobalID());
	}

	public void onRespawnRemoved(long respawnId) {
		stopApproach(respawnId);
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
		if (pos == null) {
			player.sendTextMessage("Could not read player position.");
			return;
		}
		float yaw = GuardService.lookYaw(player.getViewDirection(), player.getRotation());
		if (!repository.setGuardPost(respawnId, pos.x, pos.y, pos.z, yaw)) {
			player.sendTextMessage("Could not save guard post.");
			return;
		}
		stopApproach(respawnId);
		service.putPost(new GuardPost(respawnId, pos.x, pos.y, pos.z, yaw));
		startWalk(respawnId, live.getGlobalID());
		player.sendTextMessage("Guard post set (#" + respawnId + ") at "
				+ fmtPos(pos.x, pos.y, pos.z) + ". NPC walking there.");
	}

	public void removeGuard(Player player, long respawnId) {
		if (!service.hasPost(respawnId)) {
			player.sendTextMessage("Respawn #" + respawnId + " has no guard post.");
			return;
		}
		stopApproach(respawnId);
		service.removePost(respawnId);
		repository.clearGuardPost(respawnId);
		player.sendTextMessage("Guard removed (#" + respawnId + ").");
	}

	private void scheduleWalkToPost(long respawnId, long npcId) {
		Timer delay = new Timer(1f, WALK_AFTER_SPAWN_SECONDS, 0, () -> plugin.enqueue(() -> {
			Approach current = approaches.get(respawnId);
			if (current == null || current.npcId != npcId) {
				return;
			}
			startWalk(respawnId, npcId);
		}));
		approaches.put(respawnId, new Approach(npcId, delay));
		delay.start();
	}

	private void startWalk(long respawnId, long npcId) {
		GuardPost post = service.getPost(respawnId);
		Npc live = World.getNpc(npcId);
		if (post == null || live == null || live.isDead()) {
			stopApproach(respawnId);
			return;
		}
		float dist = horizontalDist(live, post);
		if (dist <= ARRIVE_DIST) {
			finishArrive(respawnId, npcId, post);
			return;
		}
		service.sendToPost(live, post);
		Approach approach = new Approach(npcId, null);
		approaches.put(respawnId, approach);
		scheduleTick(respawnId, intervalFor(dist));
	}

	private void scheduleTick(long respawnId, float delay) {
		Approach approach = approaches.get(respawnId);
		if (approach == null) {
			return;
		}
		if (approach.timer != null && !approach.timer.isKilled()) {
			approach.timer.kill();
		}
		Timer timer = new Timer(1f, Math.max(delay, MIN_INTERVAL), 0, () -> plugin.enqueue(() -> tick(respawnId)));
		approach.timer = timer;
		timer.start();
	}

	private void tick(long respawnId) {
		Approach approach = approaches.get(respawnId);
		if (approach == null) {
			return;
		}
		GuardPost post = service.getPost(respawnId);
		Npc live = World.getNpc(approach.npcId);
		if (post == null || live == null || live.isDead()) {
			stopApproach(respawnId);
			return;
		}
		float dist = horizontalDist(live, post);
		if (dist <= ARRIVE_DIST) {
			finishArrive(respawnId, approach.npcId, post);
			return;
		}
		scheduleTick(respawnId, intervalFor(dist));
	}

	/** Step-turn toward post yaw, then lock. API has no animated turn. */
	private void finishArrive(long respawnId, long npcId, GuardPost post) {
		Npc live = World.getNpc(npcId);
		if (live == null || live.isDead()) {
			stopApproach(respawnId);
			return;
		}
		float fromYaw = 0f;
		Quaternion rot = live.getRotation();
		if (rot != null) {
			fromYaw = rot.getYaw();
		}
		stopApproachTimer(approaches.get(respawnId));
		turnStep(respawnId, npcId, fromYaw, post.yaw(), 1);
	}

	private void turnStep(long respawnId, long npcId, float fromYaw, float toYaw, int step) {
		Npc live = World.getNpc(npcId);
		if (live == null || live.isDead()) {
			stopApproach(respawnId);
			return;
		}
		float t = step / (float) TURN_STEPS;
		GuardService.faceYaw(live, lerpYaw(fromYaw, toYaw, t));
		if (step >= TURN_STEPS) {
			Timer lockDelay = new Timer(1f, LOCK_AFTER_TURN_SECONDS, 0, () -> plugin.enqueue(() -> {
				Npc still = World.getNpc(npcId);
				if (still != null && !still.isDead()) {
					still.setLocked(true);
				}
				stopApproach(respawnId);
			}));
			approaches.put(respawnId, new Approach(npcId, lockDelay));
			lockDelay.start();
			return;
		}
		Timer next = new Timer(1f, TURN_STEP_SECONDS, 0,
				() -> plugin.enqueue(() -> turnStep(respawnId, npcId, fromYaw, toYaw, step + 1)));
		approaches.put(respawnId, new Approach(npcId, next));
		next.start();
	}

	/** Shortest-path lerp between two yaw angles (degrees). */
	private static float lerpYaw(float from, float to, float t) {
		float delta = to - from;
		while (delta > 180f) {
			delta -= 360f;
		}
		while (delta < -180f) {
			delta += 360f;
		}
		return from + delta * t;
	}

	private void stopApproach(long respawnId) {
		Approach approach = approaches.remove(respawnId);
		if (approach != null) {
			stopApproachTimer(approach);
		}
	}

	private static void stopApproachTimer(Approach approach) {
		if (approach == null) {
			return;
		}
		if (approach.timer != null && !approach.timer.isKilled()) {
			approach.timer.kill();
		}
		approach.timer = null;
	}

	private static float intervalFor(float dist) {
		float interval = 0.05f * dist * dist;
		if (interval < MIN_INTERVAL) {
			return MIN_INTERVAL;
		}
		if (interval > MAX_INTERVAL) {
			return MAX_INTERVAL;
		}
		return interval;
	}

	private static float horizontalDist(Npc npc, GuardPost post) {
		Vector3f pos = npc.getPosition();
		if (pos == null) {
			return Float.POSITIVE_INFINITY;
		}
		float dx = pos.x - post.x();
		float dz = pos.z - post.z();
		return (float) Math.sqrt(dx * dx + dz * dz);
	}

	private static String fmtPos(float x, float y, float z) {
		return String.format(Locale.US, "(%.1f, %.1f, %.1f)", x, y, z);
	}

	private static final class Approach {
		final long npcId;
		Timer timer;

		Approach(long npcId, Timer timer) {
			this.npcId = npcId;
			this.timer = timer;
		}
	}
}
