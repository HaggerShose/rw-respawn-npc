package de.mahagst.risingworld.respawnnpc.guard;

import java.util.ArrayList;
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
 * Guard phases: WAITING (hold at spawn) -> WALKING -> AT_POST, or COMBAT.
 * One global 0.5 Hz tick; AT_POST stays in {@link #watches}.
 * No nearby player and not at post: forceReplace, then WAITING until a player is near.
 */
public final class GuardFeature {
	private static final float SETTLE_SECONDS = 2f;
	private static final float ARRIVE_DIST = 0.1f;
	private static final float ARRIVE_DIST_SQ = ARRIVE_DIST * ARRIVE_DIST;
	/** Own proximity gate: engine getNearestPlayer() can stay set far away. */
	private static final float PLAYER_NEAR_DIST = 128f;
	private static final float TICK_SECONDS = 2f;
	private static final long COMBAT_POLL_MS = 10_000L;
	private static final int TURN_STEPS = 8;
	private static final float TURN_STEP_SECONDS = 0.05f;
	private static final float LOCK_AFTER_TURN_SECONDS = 0.25f;

	private final Plugin plugin;
	private final RespawnRepository repository;
	private final LongFunction<Optional<Npc>> livingNpcByRespawnId;
	private final LongConsumer forceReplace;

	private final GuardService service = new GuardService();
	private final Map<Long, Watch> watches = new HashMap<>();
	private Timer globalTimer;

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
			if (live.isEmpty()) {
				putWatch(post.respawnId(), 0L, Phase.AT_POST);
				continue;
			}
			Npc npc = live.get();
			if (isAtPost(npc, post)) {
				finishArrive(post.respawnId(), npc.getGlobalID(), post);
			} else {
				resetOffPost(post.respawnId());
			}
		}
		startGlobalTick();
		System.out.println("[RespawnNpc/Guard] enabled");
	}

	public void disable() {
		stopGlobalTick();
		for (Watch watch : watches.values()) {
			killTimer(watch);
		}
		watches.clear();
		service.stop();
	}

	/** After respawn body: hold at spawn until a player is nearby, then walk. */
	public void onBodyReplaced(long respawnId, Npc npc) {
		if (!service.hasPost(respawnId)) {
			stopWatch(respawnId);
			return;
		}
		enterWaiting(respawnId, npc.getGlobalID(), SETTLE_SECONDS);
	}

	public void onRespawnRemoved(long respawnId) {
		stopWatch(respawnId);
		service.removePost(respawnId);
	}

	/** Short runtime label for list/info. Empty if this respawn is not a guard. */
	public String statusOf(long respawnId) {
		if (!service.hasPost(respawnId)) {
			return "";
		}
		Watch watch = watches.get(respawnId);
		if (watch == null) {
			return "at post";
		}
		return switch (watch.phase) {
			case WAITING -> "waiting";
			case WALKING -> "walking";
			case COMBAT -> "combat";
			case AT_POST -> "at post";
		};
	}

	/** Guard post position for list/info, or empty. */
	public String postPosOf(long respawnId) {
		GuardPost post = service.getPost(respawnId);
		if (post == null) {
			return "";
		}
		return fmtPos(post.x(), post.y(), post.z());
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
		stopWatch(respawnId);
		GuardPost post = new GuardPost(respawnId, pos.x, pos.y, pos.z, yaw);
		service.putPost(post);
		if (isAtPost(live, post)) {
			finishArrive(respawnId, live.getGlobalID(), post);
		} else if (hasNearbyPlayer(live)) {
			startWalk(respawnId, live.getGlobalID());
		} else {
			enterWaiting(respawnId, live.getGlobalID(), TICK_SECONDS);
		}
		player.sendTextMessage("Guard post set (#" + respawnId + ") at "
				+ fmtPos(pos.x, pos.y, pos.z) + ".");
	}

	public void removeGuard(Player player, long respawnId) {
		if (!service.hasPost(respawnId)) {
			player.sendTextMessage("Respawn #" + respawnId + " has no guard post.");
			return;
		}
		stopWatch(respawnId);
		service.removePost(respawnId);
		repository.clearGuardPost(respawnId);
		player.sendTextMessage("Guard removed (#" + respawnId + ").");
	}

	private void enterWaiting(long respawnId, long npcId, float delaySeconds) {
		Watch watch = putWatch(respawnId, npcId, Phase.WAITING);
		watch.nextDueMs = System.currentTimeMillis() + (long) (delaySeconds * 1000f);
	}

	private void waitTick(long respawnId) {
		Watch watch = watches.get(respawnId);
		if (watch == null || watch.phase != Phase.WAITING) {
			return;
		}
		GuardPost post = service.getPost(respawnId);
		Npc live = liveNpc(respawnId, watch);
		if (post == null) {
			stopWatch(respawnId);
			return;
		}
		if (live == null) {
			return;
		}
		if (isAtPost(live, post)) {
			finishArrive(respawnId, live.getGlobalID(), post);
			return;
		}
		if (inCombat(live)) {
			enterCombat(respawnId, live.getGlobalID());
			return;
		}
		if (hasNearbyPlayer(live)) {
			startWalk(respawnId, live.getGlobalID());
		}
	}

	private void startWalk(long respawnId, long npcId) {
		GuardPost post = service.getPost(respawnId);
		Npc live = World.getNpc(npcId);
		if (live == null || live.isDead()) {
			Optional<Npc> rebound = livingNpcByRespawnId.apply(respawnId);
			live = rebound.orElse(null);
		}
		if (post == null || live == null) {
			if (post == null) {
				stopWatch(respawnId);
			} else {
				enterWaiting(respawnId, npcId, TICK_SECONDS);
			}
			return;
		}
		npcId = live.getGlobalID();
		if (inCombat(live)) {
			enterCombat(respawnId, npcId);
			return;
		}
		if (isAtPost(live, post)) {
			finishArrive(respawnId, npcId, post);
			return;
		}
		if (!hasNearbyPlayer(live)) {
			resetOffPost(respawnId);
			return;
		}
		service.sendToPost(live, post);
		putWatch(respawnId, npcId, Phase.WALKING);
	}

	private void walkTick(long respawnId) {
		Watch watch = watches.get(respawnId);
		if (watch == null || watch.phase != Phase.WALKING) {
			return;
		}
		GuardPost post = service.getPost(respawnId);
		Npc live = liveNpc(respawnId, watch);
		if (post == null) {
			stopWatch(respawnId);
			return;
		}
		if (live == null) {
			// Unloaded mid-walk: treat as off-post, hard reset.
			resetOffPost(respawnId);
			return;
		}
		if (isAtPost(live, post)) {
			finishArrive(respawnId, live.getGlobalID(), post);
			return;
		}
		if (!hasNearbyPlayer(live)) {
			resetOffPost(respawnId);
			return;
		}
		if (inCombat(live)) {
			enterCombat(respawnId, live.getGlobalID());
		}
	}

	private void enterCombat(long respawnId, long npcId) {
		Npc live = World.getNpc(npcId);
		if (live != null && !live.isDead()) {
			GuardService.cancelMoveToHere(live);
		}
		Watch watch = putWatch(respawnId, npcId, Phase.COMBAT);
		watch.nextDueMs = System.currentTimeMillis() + COMBAT_POLL_MS;
	}

	private void combatTick(long respawnId) {
		Watch watch = watches.get(respawnId);
		if (watch == null || watch.phase != Phase.COMBAT) {
			return;
		}
		GuardPost post = service.getPost(respawnId);
		Npc live = liveNpc(respawnId, watch);
		if (post == null) {
			stopWatch(respawnId);
			return;
		}
		if (live == null) {
			resetOffPost(respawnId);
			return;
		}
		if (isAtPost(live, post) && !inCombat(live)) {
			finishArrive(respawnId, live.getGlobalID(), post);
			return;
		}
		if (!hasNearbyPlayer(live)) {
			resetOffPost(respawnId);
			return;
		}
		if (inCombat(live)) {
			watch.nextDueMs = System.currentTimeMillis() + COMBAT_POLL_MS;
			return;
		}
		startWalk(respawnId, live.getGlobalID());
	}

	private static boolean inCombat(Npc npc) {
		return npc.isAlerted() || npc.getHostilePlayer() != null;
	}

	/**
	 * Engine getNearestPlayer() can remain set beyond useful range.
	 * Require both a nearest player and horizontal distance within {@link #PLAYER_NEAR_DIST}.
	 */
	private static boolean hasNearbyPlayer(Npc npc) {
		Player nearest = npc.getNearestPlayer();
		if (nearest == null) {
			return false;
		}
		Vector3f npcPos = npc.getPosition();
		Vector3f playerPos = nearest.getPosition();
		if (npcPos == null || playerPos == null) {
			return false;
		}
		float dx = npcPos.x - playerPos.x;
		float dz = npcPos.z - playerPos.z;
		return dx * dx + dz * dz <= PLAYER_NEAR_DIST * PLAYER_NEAR_DIST;
	}

	private void startGlobalTick() {
		stopGlobalTick();
		globalTimer = new Timer(
				TICK_SECONDS,
				TICK_SECONDS,
				-1,
				() -> plugin.enqueue(this::globalTick));
		globalTimer.start();
	}

	private void stopGlobalTick() {
		if (globalTimer != null && !globalTimer.isKilled()) {
			globalTimer.kill();
		}
		globalTimer = null;
	}

	private void globalTick() {
		long now = System.currentTimeMillis();
		for (Long respawnId : new ArrayList<>(watches.keySet())) {
			Watch watch = watches.get(respawnId);
			if (watch == null || watch.timer != null || watch.nextDueMs > now) {
				continue;
			}
			switch (watch.phase) {
				case WAITING -> waitTick(respawnId);
				case WALKING -> walkTick(respawnId);
				case COMBAT -> combatTick(respawnId);
				case AT_POST -> atPostTick(respawnId);
			}
		}
	}

	/** Idle at post: if alerted and a player is near, unlock into combat. */
	private void atPostTick(long respawnId) {
		Watch watch = watches.get(respawnId);
		if (watch == null || watch.phase != Phase.AT_POST) {
			return;
		}
		GuardPost post = service.getPost(respawnId);
		if (post == null) {
			stopWatch(respawnId);
			return;
		}
		Npc live = liveNpc(respawnId, watch);
		if (live == null) {
			return;
		}
		if (!isAtPost(live, post)) {
			if (!hasNearbyPlayer(live)) {
				resetOffPost(respawnId);
			} else {
				startWalk(respawnId, live.getGlobalID());
			}
			return;
		}
		if (!hasNearbyPlayer(live) || !inCombat(live)) {
			return;
		}
		if (live.isLocked()) {
			live.setLocked(false);
		}
		enterCombat(respawnId, live.getGlobalID());
	}

	/** Step-turn toward post yaw, then lock. API has no animated turn. */
	private void finishArrive(long respawnId, long npcId, GuardPost post) {
		Npc live = World.getNpc(npcId);
		if (live == null || live.isDead()) {
			stopWatch(respawnId);
			return;
		}
		if (inCombat(live)) {
			enterCombat(respawnId, npcId);
			return;
		}
		if (!hasNearbyPlayer(live) && !isAtPost(live, post)) {
			resetOffPost(respawnId);
			return;
		}
		float fromYaw = 0f;
		Quaternion rot = live.getRotation();
		if (rot != null) {
			fromYaw = rot.getYaw();
		}
		putWatch(respawnId, npcId, Phase.WALKING);
		turnStep(respawnId, npcId, fromYaw, post.yaw(), 1);
	}

	private void turnStep(long respawnId, long npcId, float fromYaw, float toYaw, int step) {
		Npc live = World.getNpc(npcId);
		if (live == null || live.isDead()) {
			stopWatch(respawnId);
			return;
		}
		if (inCombat(live)) {
			enterCombat(respawnId, npcId);
			return;
		}
		float t = step / (float) TURN_STEPS;
		GuardService.faceYaw(live, lerpYaw(fromYaw, toYaw, t));
		if (step >= TURN_STEPS) {
			Timer lockDelay = new Timer(1f, LOCK_AFTER_TURN_SECONDS, 0, () -> plugin.enqueue(() -> {
				Npc still = World.getNpc(npcId);
				if (still == null || still.isDead()) {
					stopWatch(respawnId);
					return;
				}
				if (inCombat(still)) {
					enterCombat(respawnId, npcId);
					return;
				}
				still.setLocked(true);
				putWatch(respawnId, npcId, Phase.AT_POST);
			}));
			Watch watch = putWatch(respawnId, npcId, Phase.WALKING);
			watch.timer = lockDelay;
			lockDelay.start();
			return;
		}
		Timer next = new Timer(1f, TURN_STEP_SECONDS, 0,
				() -> plugin.enqueue(() -> turnStep(respawnId, npcId, fromYaw, toYaw, step + 1)));
		Watch watch = putWatch(respawnId, npcId, Phase.WALKING);
		watch.timer = next;
		next.start();
	}

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

	private void resetOffPost(long respawnId) {
		System.out.println("[RespawnNpc/Guard] no player off-post #" + respawnId + ", forceReplace");
		stopWatch(respawnId);
		forceReplace.accept(respawnId);
	}

	private Npc liveNpc(long respawnId, Watch watch) {
		Npc live = World.getNpc(watch.npcId);
		if (live != null && !live.isDead()) {
			return live;
		}
		Optional<Npc> rebound = livingNpcByRespawnId.apply(respawnId);
		if (rebound.isEmpty()) {
			return null;
		}
		watch.npcId = rebound.get().getGlobalID();
		return rebound.get();
	}

	private Watch putWatch(long respawnId, long npcId, Phase phase) {
		Watch existing = watches.get(respawnId);
		if (existing != null) {
			killTimer(existing);
		}
		Watch watch = new Watch(npcId, phase);
		watches.put(respawnId, watch);
		return watch;
	}

	private void stopWatch(long respawnId) {
		Watch watch = watches.remove(respawnId);
		killTimer(watch);
	}

	private static void killTimer(Watch watch) {
		if (watch == null || watch.timer == null) {
			return;
		}
		if (!watch.timer.isKilled()) {
			watch.timer.kill();
		}
		watch.timer = null;
	}

	private static boolean isAtPost(Npc npc, GuardPost post) {
		Vector3f pos = npc.getPosition();
		if (pos == null) {
			return false;
		}
		float dx = pos.x - post.x();
		float dz = pos.z - post.z();
		return dx * dx + dz * dz <= ARRIVE_DIST_SQ;
	}

	private static String fmtPos(float x, float y, float z) {
		return String.format(Locale.US, "(%.1f, %.1f, %.1f)", x, y, z);
	}

	private enum Phase {
		WAITING, WALKING, COMBAT, AT_POST
	}

	private static final class Watch {
		long npcId;
		final Phase phase;
		long nextDueMs;
		Timer timer;

		Watch(long npcId, Phase phase) {
			this.npcId = npcId;
			this.phase = phase;
		}
	}
}
