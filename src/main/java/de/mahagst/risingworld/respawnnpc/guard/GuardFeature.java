package de.mahagst.risingworld.respawnnpc.guard;

import java.util.ArrayList;
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
 * Runtime guard behaviour for respawn NPCs that have a post.
 * <p>
 * Flow: after spawn (or {@code /make-guard}), wait briefly, {@code moveTo} the post,
 * poll until within {@value #ARRIVE_DIST}, step-turn to post yaw, then {@code setLocked(true)}.
 * While walking (including turn), a {@link Watch} is kept in {@link #watches}; after lock the watch is removed.
 * <p>
 * Intentionally no combat, player-proximity gate, or forceReplace. See {@code ideas.md} for later work.
 */
public final class GuardFeature {
	/** Delay after body spawn before issuing {@code moveTo} (lets the body settle). */
	private static final float SETTLE_SECONDS = 2f;
	/** Horizontal arrive threshold in world units (xz). */
	private static final float ARRIVE_DIST = 0.1f;
	private static final float ARRIVE_DIST_SQ = ARRIVE_DIST * ARRIVE_DIST;
	/** Global arrive-poll interval in seconds (0.5 Hz). */
	private static final float TICK_SECONDS = 2f;
	/** Number of discrete yaw steps when facing the post (API has no turn animation). */
	private static final int TURN_STEPS = 8;
	private static final float TURN_STEP_SECONDS = 0.05f;
	/** Short pause after the last turn step before locking. */
	private static final float LOCK_AFTER_TURN_SECONDS = 0.25f;

	private final Plugin plugin;
	private final RespawnRepository repository;
	/** Resolves the living world NPC for a respawn id (RAM map in RespawnService). */
	private final LongFunction<Optional<Npc>> livingNpcByRespawnId;

	private final GuardService service = new GuardService();
	/** Active walks: respawnId -> watch. Absent after lock means "at post". */
	private final Map<Long, Watch> watches = new HashMap<>();
	/** Single repeating timer that drives {@link #globalTick()}. */
	private Timer globalTimer;

	/**
	 * @param plugin                 host plugin (enqueue + timers)
	 * @param repository             DB for make/remove guard post
	 * @param livingNpcByRespawnId lookup living body without a full-row DB read
	 */
	public GuardFeature(
			Plugin plugin,
			RespawnRepository repository,
			LongFunction<Optional<Npc>> livingNpcByRespawnId) {
		this.plugin = plugin;
		this.repository = repository;
		this.livingNpcByRespawnId = livingNpcByRespawnId;
	}

	/**
	 * Load posts from DB, resume each living guard (lock if already at post, else walk), start the global tick.
	 * Missing bodies (unloaded chunk) are skipped until a later respawn/{@code /respawn-now}.
	 */
	public void enable() {
		service.loadPosts(repository.findAllGuardPosts());
		for (GuardPost post : service.allPosts()) {
			Optional<Npc> live = livingNpcByRespawnId.apply(post.respawnId());
			if (live.isEmpty()) {
				continue;
			}
			Npc npc = live.get();
			if (isAtPost(npc, post)) {
				finishArrive(post.respawnId(), npc.getGlobalID(), post);
			} else {
				startWalk(post.respawnId(), npc.getGlobalID());
			}
		}
		startGlobalTick();
		System.out.println("[RespawnNpc/Guard] enabled");
	}

	/**
	 * Stop the global tick, kill any turn timers, clear watches and RAM posts.
	 */
	public void disable() {
		stopGlobalTick();
		for (Watch watch : watches.values()) {
			killTimer(watch);
		}
		watches.clear();
		service.stop();
	}

	/**
	 * Called after a successful spawn/replacement when this respawn has a guard post.
	 * Schedules {@link #SETTLE_SECONDS} then walk; no-op (clears watch) if there is no post.
	 *
	 * @param respawnId respawn row id
	 * @param npc       newly spawned body
	 */
	public void onBodyReplaced(long respawnId, Npc npc) {
		if (!service.hasPost(respawnId)) {
			stopWatch(respawnId);
			return;
		}
		scheduleWalk(respawnId, npc.getGlobalID(), SETTLE_SECONDS);
	}

	/**
	 * Drop runtime watch and RAM post when the respawn entry is removed.
	 * DB cascade for {@code guard_posts} is handled by the repository delete.
	 */
	public void onRespawnRemoved(long respawnId) {
		stopWatch(respawnId);
		service.removePost(respawnId);
	}

	/**
	 * Short status for {@code /respawn-list} / info colouring.
	 *
	 * @return {@code ""} if not a guard; {@code "walking"} if a watch exists; else {@code "at post"}
	 */
	public String statusOf(long respawnId) {
		if (!service.hasPost(respawnId)) {
			return "";
		}
		if (watches.containsKey(respawnId)) {
			return "walking";
		}
		return "at post";
	}

	/**
	 * Formatted post coordinates for list/info, or empty if no post.
	 *
	 * @return e.g. {@code (x, y, z)} or {@code ""}
	 */
	public String postPosOf(long respawnId) {
		GuardPost post = service.getPost(respawnId);
		if (post == null) {
			return "";
		}
		return fmtPos(post.x(), post.y(), post.z());
	}

	/**
	 * {@code /make-guard}: save post at the admin's pose, then arrive or start walking immediately.
	 * Requires a living body for that respawn id.
	 */
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
		} else {
			startWalk(respawnId, live.getGlobalID());
		}
		player.sendTextMessage("Guard post set (#" + respawnId + ") at "
				+ fmtPos(pos.x, pos.y, pos.z) + ".");
	}

	/**
	 * {@code /guard-remove}: clear DB + RAM post and any active walk; NPC is not moved.
	 */
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

	/**
	 * Put or replace a walking watch and set when {@link #walkTick} may run.
	 *
	 * @param delaySeconds wait before the first tick (0 = due immediately on next poll / {@link #startWalk})
	 */
	private void scheduleWalk(long respawnId, long npcId, float delaySeconds) {
		Watch watch = putWatch(respawnId, npcId);
		watch.nextDueMs = System.currentTimeMillis() + (long) (delaySeconds * 1000f);
	}

	/**
	 * Begin walk now: schedule with zero delay and run one {@link #walkTick} immediately
	 * (issues {@code moveTo} if not already at post).
	 */
	private void startWalk(long respawnId, long npcId) {
		scheduleWalk(respawnId, npcId, 0f);
		walkTick(respawnId);
	}

	/**
	 * One arrive-poll step: stop if body/post gone; finish arrive if close enough;
	 * otherwise issue {@code moveTo} once ({@link Watch#dispatched}).
	 */
	private void walkTick(long respawnId) {
		Watch watch = watches.get(respawnId);
		if (watch == null) {
			return;
		}
		GuardPost post = service.getPost(respawnId);
		Npc live = liveNpc(respawnId, watch);
		if (post == null || live == null) {
			stopWatch(respawnId);
			return;
		}
		if (isAtPost(live, post)) {
			finishArrive(respawnId, live.getGlobalID(), post);
			return;
		}
		if (!watch.dispatched) {
			service.sendToPost(live, post);
			watch.dispatched = true;
		}
	}

	/** Start or restart the repeating 2s timer that calls {@link #globalTick}. */
	private void startGlobalTick() {
		stopGlobalTick();
		globalTimer = new Timer(
				TICK_SECONDS,
				TICK_SECONDS,
				-1,
				() -> plugin.enqueue(this::globalTick));
		globalTimer.start();
	}

	/** Kill and null the global arrive timer. */
	private void stopGlobalTick() {
		if (globalTimer != null && !globalTimer.isKilled()) {
			globalTimer.kill();
		}
		globalTimer = null;
	}

	/**
	 * Every {@value #TICK_SECONDS}s: for each watch that is due and not mid-turn-timer, run {@link #walkTick}.
	 * Skips watches with a local turn/lock {@link Watch#timer} so arrive poll does not fight the animation.
	 */
	private void globalTick() {
		long now = System.currentTimeMillis();
		for (Long respawnId : new ArrayList<>(watches.keySet())) {
			Watch watch = watches.get(respawnId);
			if (watch == null || watch.timer != null || watch.nextDueMs > now) {
				continue;
			}
			walkTick(respawnId);
		}
	}

	/**
	 * NPC reached the post: start step-turn toward post yaw, then lock.
	 * Replaces any previous watch timer with the turn sequence.
	 */
	private void finishArrive(long respawnId, long npcId, GuardPost post) {
		Npc live = World.getNpc(npcId);
		if (live == null || live.isDead()) {
			stopWatch(respawnId);
			return;
		}
		float fromYaw = 0f;
		Quaternion rot = live.getRotation();
		if (rot != null) {
			fromYaw = rot.getYaw();
		}
		putWatch(respawnId, npcId);
		turnStep(respawnId, npcId, fromYaw, post.yaw(), 1);
	}

	/**
	 * One frame of the face-post animation. Schedules the next step or, on the last step,
	 * a short lock delay then {@code setLocked(true)} and {@link #stopWatch}.
	 *
	 * @param step 1-based step index, ends at {@link #TURN_STEPS}
	 */
	private void turnStep(long respawnId, long npcId, float fromYaw, float toYaw, int step) {
		Npc live = World.getNpc(npcId);
		if (live == null || live.isDead()) {
			stopWatch(respawnId);
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
				still.setLocked(true);
				stopWatch(respawnId);
			}));
			Watch watch = putWatch(respawnId, npcId);
			watch.timer = lockDelay;
			lockDelay.start();
			return;
		}
		Timer next = new Timer(1f, TURN_STEP_SECONDS, 0,
				() -> plugin.enqueue(() -> turnStep(respawnId, npcId, fromYaw, toYaw, step + 1)));
		Watch watch = putWatch(respawnId, npcId);
		watch.timer = next;
		next.start();
	}

	/**
	 * Shortest-path yaw interpolation in degrees.
	 *
	 * @param t blend 0..1
	 */
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

	/**
	 * Living NPC for this watch: prefer {@link Watch#npcId}, rebound via {@link #livingNpcByRespawnId} if gone.
	 *
	 * @return live NPC or null if missing/dead
	 */
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

	/**
	 * Insert a fresh watch, killing any previous local timer for this respawn id.
	 * Resets {@link Watch#dispatched} and {@link Watch#nextDueMs} to defaults (0).
	 */
	private Watch putWatch(long respawnId, long npcId) {
		Watch existing = watches.get(respawnId);
		if (existing != null) {
			killTimer(existing);
		}
		Watch watch = new Watch(npcId);
		watches.put(respawnId, watch);
		return watch;
	}

	/** Remove watch and kill its one-shot turn/lock timer if any. */
	private void stopWatch(long respawnId) {
		Watch watch = watches.remove(respawnId);
		killTimer(watch);
	}

	/** Safe-kill {@link Watch#timer} if present. */
	private static void killTimer(Watch watch) {
		if (watch == null || watch.timer == null) {
			return;
		}
		if (!watch.timer.isKilled()) {
			watch.timer.kill();
		}
		watch.timer = null;
	}

	/**
	 * True if horizontal (xz) distance to the post is within {@link #ARRIVE_DIST}.
	 * Uses squared distance (no sqrt).
	 */
	private static boolean isAtPost(Npc npc, GuardPost post) {
		Vector3f pos = npc.getPosition();
		if (pos == null) {
			return false;
		}
		float dx = pos.x - post.x();
		float dz = pos.z - post.z();
		return dx * dx + dz * dz <= ARRIVE_DIST_SQ;
	}

	/** US-locale {@code (x, y, z)} with one decimal for chat. */
	private static String fmtPos(float x, float y, float z) {
		return String.format(Locale.US, "(%.1f, %.1f, %.1f)", x, y, z);
	}

	/**
	 * Per-guard walk state while not yet locked at post.
	 * Presence in {@link #watches} means status {@code walking}.
	 */
	private static final class Watch {
		/** Current body global id (updated on rebound). */
		long npcId;
		/** True after {@link GuardService#sendToPost} was issued once for this walk. */
		boolean dispatched;
		/** Earliest wall-clock ms when {@link #walkTick} may run (settle / due). */
		long nextDueMs;
		/** One-shot turn or lock-delay timer; when set, {@link #globalTick} skips this watch. */
		Timer timer;

		Watch(long npcId) {
			this.npcId = npcId;
		}
	}
}
