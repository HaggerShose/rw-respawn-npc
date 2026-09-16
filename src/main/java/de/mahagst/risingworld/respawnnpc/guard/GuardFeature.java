package de.mahagst.risingworld.respawnnpc.guard;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongConsumer;
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
 * Guard: spawn settle -> moveTo -> distance ticks -> turn -> lock.
 * Far walk: stuck check; retry moveTo then forceReplace. Post alert -> combat watch -> startWalk.
 */
public final class GuardFeature {
	private static final float WALK_AFTER_SPAWN_SECONDS = 2f;
	private static final float ARRIVE_DIST = 0.1f;
	/** Within this range, poll fast so arrive snaps cleanly. Stuck check is far-only. */
	private static final float NEAR_DIST = 1f;
	private static final float NEAR_INTERVAL = 0.1f;
	private static final float FAR_INTERVAL = 1f;
	/** Far ticks (~1s each) without enough approach before retry/respawn. */
	private static final int STUCK_TICKS = 5;
	/** Required distance closed toward post within {@link #STUCK_TICKS} (capped near post). */
	private static final float STUCK_PROGRESS_M = 5f;
	private static final int MAX_MOVE_RETRIES = 2;
	private static final int TURN_STEPS = 8;
	private static final float TURN_STEP_SECONDS = 0.05f;
	private static final float LOCK_AFTER_TURN_SECONDS = 0.25f;
	private static final float COMBAT_POLL_SECONDS = 10f;
	private static final float POST_ALERT_POLL_SECONDS = 1f;

	private final Plugin plugin;
	private final RespawnRepository repository;
	private final LongFunction<Optional<Npc>> livingNpcByRespawnId;
	private final LongConsumer forceReplace;

	private final GuardService service = new GuardService();
	private final Map<Long, Approach> approaches = new HashMap<>();
	private final Map<Long, Approach> combatWatches = new HashMap<>();
	/** respawn_id -> last known alerted/hostile for idle post guards. */
	private final Map<Long, Boolean> alertState = new HashMap<>();
	private Timer postAlertTimer;

	public GuardFeature(
			Plugin plugin,
			RespawnRepository repository,
			LongFunction<Optional<Npc>> livingNpcByRespawnId,
			LongConsumer forceReplace) {
		this.plugin = plugin;
		this.repository = repository;
		this.livingNpcByRespawnId = livingNpcByRespawnId;
		this.forceReplace = forceReplace;
	}

	public void enable() {
		service.loadPosts(repository.findAllGuardPosts());
		for (GuardPost post : service.allPosts()) {
			Optional<Npc> live = livingNpcByRespawnId.apply(post.respawnId());
			if (live.isPresent()) {
				onBodyReplaced(post.respawnId(), live.get());
			}
		}
		startPostAlertTimer();
		System.out.println("[RespawnNpc/Guard] enabled");
	}

	public void disable() {
		stopPostAlertTimer();
		for (Approach approach : approaches.values()) {
			stopApproachTimer(approach);
		}
		approaches.clear();
		for (Approach combat : combatWatches.values()) {
			stopApproachTimer(combat);
		}
		combatWatches.clear();
		alertState.clear();
		service.stop();
	}

	/** After respawn body: wait, then walk to post. */
	public void onBodyReplaced(long respawnId, Npc npc) {
		if (!service.hasPost(respawnId)) {
			stopAll(respawnId);
			return;
		}
		stopAll(respawnId);
		scheduleWalkToPost(respawnId, npc.getGlobalID());
	}

	public void onRespawnRemoved(long respawnId) {
		stopAll(respawnId);
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
		stopAll(respawnId);
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
		stopAll(respawnId);
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
			stopAll(respawnId);
			return;
		}
		if (inCombat(live)) {
			startCombatWatch(respawnId, npcId);
			return;
		}
		float dist = horizontalDist(live, post);
		if (dist <= ARRIVE_DIST) {
			finishArrive(respawnId, npcId, post);
			return;
		}
		service.sendToPost(live, post);
		Approach approach = new Approach(npcId, null);
		approach.windowDist = dist;
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
		Timer timer = new Timer(1f, delay, 0, () -> plugin.enqueue(() -> tick(respawnId)));
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
			stopAll(respawnId);
			return;
		}
		if (inCombat(live)) {
			startCombatWatch(respawnId, approach.npcId);
			return;
		}
		float dist = horizontalDist(live, post);
		if (dist <= ARRIVE_DIST) {
			finishArrive(respawnId, approach.npcId, post);
			return;
		}
		if (dist > NEAR_DIST && !noteFarProgress(approach, dist)) {
			onStuck(respawnId, approach, live, post, dist);
			return;
		}
		scheduleTick(respawnId, intervalFor(dist));
	}

	/**
	 * Far walk only: true = keep going; false = stuck window expired.
	 * Required close = min(5m, windowDist - near), else retry/respawn after {@link #STUCK_TICKS}.
	 */
	private static boolean noteFarProgress(Approach approach, float dist) {
		float need = Math.min(STUCK_PROGRESS_M, approach.windowDist - NEAR_DIST);
		if (need <= 0.01f) {
			approach.windowDist = dist;
			approach.staleTicks = 0;
			return true;
		}
		float gained = approach.windowDist - dist;
		if (gained >= need) {
			approach.windowDist = dist;
			approach.staleTicks = 0;
			return true;
		}
		approach.staleTicks++;
		return approach.staleTicks < STUCK_TICKS;
	}

	private void onStuck(long respawnId, Approach approach, Npc live, GuardPost post, float dist) {
		if (approach.moveRetries < MAX_MOVE_RETRIES) {
			approach.moveRetries++;
			System.out.println("[RespawnNpc/Guard] stuck #" + respawnId
					+ " retry moveTo (" + approach.moveRetries + "/" + MAX_MOVE_RETRIES + ")");
			service.sendToPost(live, post);
			approach.windowDist = dist;
			approach.staleTicks = 0;
			scheduleTick(respawnId, FAR_INTERVAL);
			return;
		}
		System.out.println("[RespawnNpc/Guard] stuck #" + respawnId + " forceReplace after retries");
		stopAll(respawnId);
		forceReplace.accept(respawnId);
	}

	/**
	 * Pause walk/arrive; poll until calm, then startWalk again (same as after respawn settle).
	 */
	private void startCombatWatch(long respawnId, long npcId) {
		stopApproach(respawnId);
		stopCombatWatch(respawnId);
		Npc live = World.getNpc(npcId);
		if (live != null && !live.isDead()) {
			GuardService.cancelMoveToHere(live);
		}
		Timer timer = new Timer(1f, COMBAT_POLL_SECONDS, 0, () -> plugin.enqueue(() -> combatTick(respawnId)));
		combatWatches.put(respawnId, new Approach(npcId, timer));
		timer.start();
	}

	private void combatTick(long respawnId) {
		Approach watch = combatWatches.get(respawnId);
		if (watch == null) {
			return;
		}
		if (!service.hasPost(respawnId)) {
			stopAll(respawnId);
			return;
		}
		Npc live = World.getNpc(watch.npcId);
		if (live == null || live.isDead()) {
			stopAll(respawnId);
			return;
		}
		if (inCombat(live)) {
			if (watch.timer != null && !watch.timer.isKilled()) {
				watch.timer.kill();
			}
			Timer timer = new Timer(1f, COMBAT_POLL_SECONDS, 0, () -> plugin.enqueue(() -> combatTick(respawnId)));
			watch.timer = timer;
			timer.start();
			return;
		}
		stopCombatWatch(respawnId);
		startWalk(respawnId, watch.npcId);
	}

	private static boolean inCombat(Npc npc) {
		return npc.isAlerted() || npc.getHostilePlayer() != null;
	}

	/** Idle guards at post: every few seconds, unlock into combat watch if alerted. */
	private void startPostAlertTimer() {
		stopPostAlertTimer();
		postAlertTimer = new Timer(
				POST_ALERT_POLL_SECONDS,
				POST_ALERT_POLL_SECONDS,
				-1,
				() -> plugin.enqueue(this::pollPostAlerts));
		postAlertTimer.start();
	}

	private void stopPostAlertTimer() {
		if (postAlertTimer != null && !postAlertTimer.isKilled()) {
			postAlertTimer.kill();
		}
		postAlertTimer = null;
	}

	private void pollPostAlerts() {
		for (GuardPost post : service.allPosts()) {
			long respawnId = post.respawnId();
			if (approaches.containsKey(respawnId) || combatWatches.containsKey(respawnId)) {
				continue;
			}
			Optional<Npc> liveOpt = livingNpcByRespawnId.apply(respawnId);
			if (liveOpt.isEmpty()) {
				alertState.remove(respawnId);
				continue;
			}
			Npc live = liveOpt.get();
			boolean alerted = inCombat(live);
			alertState.put(respawnId, alerted);
			if (!alerted) {
				continue;
			}
			if (live.isLocked()) {
				live.setLocked(false);
			}
			startCombatWatch(respawnId, live.getGlobalID());
		}
	}

	/** Step-turn toward post yaw, then lock. API has no animated turn. */
	private void finishArrive(long respawnId, long npcId, GuardPost post) {
		Npc live = World.getNpc(npcId);
		if (live == null || live.isDead()) {
			stopAll(respawnId);
			return;
		}
		if (inCombat(live)) {
			startCombatWatch(respawnId, npcId);
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
			stopAll(respawnId);
			return;
		}
		if (inCombat(live)) {
			startCombatWatch(respawnId, npcId);
			return;
		}
		float t = step / (float) TURN_STEPS;
		GuardService.faceYaw(live, lerpYaw(fromYaw, toYaw, t));
		if (step >= TURN_STEPS) {
			Timer lockDelay = new Timer(1f, LOCK_AFTER_TURN_SECONDS, 0, () -> plugin.enqueue(() -> {
				Npc still = World.getNpc(npcId);
				if (still == null || still.isDead()) {
					stopAll(respawnId);
					return;
				}
				if (inCombat(still)) {
					startCombatWatch(respawnId, npcId);
					return;
				}
				still.setLocked(true);
				stopAll(respawnId);
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

	private void stopAll(long respawnId) {
		stopApproach(respawnId);
		stopCombatWatch(respawnId);
		alertState.remove(respawnId);
	}

	private void stopApproach(long respawnId) {
		Approach approach = approaches.remove(respawnId);
		if (approach != null) {
			stopApproachTimer(approach);
		}
	}

	private void stopCombatWatch(long respawnId) {
		Approach combat = combatWatches.remove(respawnId);
		if (combat != null) {
			stopApproachTimer(combat);
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
		return dist <= NEAR_DIST ? NEAR_INTERVAL : FAR_INTERVAL;
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
		/** Dist at start of current stuck window (far only). */
		float windowDist;
		int staleTicks;
		int moveRetries;

		Approach(long npcId, Timer timer) {
			this.npcId = npcId;
			this.timer = timer;
		}
	}
}
