package de.mahagst.risingworld.respawnnpc.guard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongFunction;

import de.mahagst.risingworld.respawnnpc.Pose;
import de.mahagst.risingworld.respawnnpc.RespawnRepository;
import net.risingworld.api.Plugin;
import net.risingworld.api.Timer;
import net.risingworld.api.World;
import net.risingworld.api.objects.Npc;
import net.risingworld.api.objects.Player;
import net.risingworld.api.utils.Quaternion;
import net.risingworld.api.utils.Vector3f;

/**
 * Guard posts, watches, walk/turn/lock. Hot path never hits the DB;
 * persistence is load on enable / save on make / clear on remove.
 * <p>
 * Flow: after spawn (or {@code /make-guard}), wait briefly, {@code moveTo} the post,
 * poll until within {@value #ARRIVE_DIST}, step-turn to post yaw, then {@code setLocked(true)}.
 * While walking (including turn), a {@link Watch} is kept in {@link #watches}; after lock the watch is removed.
 * <p>
 * Intentionally no combat, player-proximity gate, or forceReplace. See {@code ideas.md} for later work.
 */
public final class GuardService {
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

	/** respawnId -> post. Cleared on {@link #disable()}. */
	private final Map<Long, GuardPost> posts = new HashMap<>();
	/** Active walks: respawnId -> watch. Absent after lock is not automatically "at post". */
	private final Map<Long, Watch> watches = new HashMap<>();
	/** Repeating arrive poll; running only while {@link #watches} is not empty. */
	private Timer globalTimer;

	/**
	 * @param plugin               host plugin (enqueue + timers)
	 * @param repository           DB for make/remove guard post
	 * @param livingNpcByRespawnId lookup living body without a full-row DB read
	 */
	public GuardService(
			Plugin plugin,
			RespawnRepository repository,
			LongFunction<Optional<Npc>> livingNpcByRespawnId) {
		this.plugin = plugin;
		this.repository = repository;
		this.livingNpcByRespawnId = livingNpcByRespawnId;
	}

	/**
	 * Load posts from DB into RAM. Does not start walks.
	 *
	 * @return false if the query failed (logged); RAM is left unchanged
	 */
	public boolean loadPosts() {
		Optional<List<GuardPost>> all = repository.findAllGuardPosts();
		if (all.isEmpty()) {
			System.out.println("[RespawnNpc/Guard] Failed to load guard_posts");
			return false;
		}
		posts.clear();
		for (GuardPost post : all.get()) {
			posts.put(post.respawnId(), post);
		}
		return true;
	}

	/**
	 * Resume each living guard (lock if already at post, else walk).
	 * Skips ids that already have a watch (e.g. settle from a spawn during {@code RespawnService.start}).
	 * Missing bodies (unloaded chunk) are skipped until a later respawn/{@code /respawn-now}.
	 */
	public void startWalks() {
		for (GuardPost post : new ArrayList<>(posts.values())) {
			if (watches.containsKey(post.respawnId())) {
				continue;
			}
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
		posts.clear();
	}

	/**
	 * Called after a successful spawn/replacement when this respawn has a guard post.
	 * Schedules {@link #SETTLE_SECONDS} then walk; no-op (clears watch) if there is no post.
	 *
	 * @param respawnId respawn row id
	 * @param npc       newly spawned body
	 */
	public void onBodyReplaced(long respawnId, Npc npc) {
		if (!posts.containsKey(respawnId)) {
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
		posts.remove(respawnId);
	}

	/**
	 * Short status for {@code /respawn-list} / info colouring.
	 *
	 * @return {@code ""} if not a guard; {@code "walking"} if a watch exists;
	 *         {@code "at post"} if the living body is at the post; else {@code "away"}
	 */
	public String statusOf(long respawnId) {
		GuardPost post = posts.get(respawnId);
		if (post == null) {
			return "";
		}
		if (watches.containsKey(respawnId)) {
			return "walking";
		}
		Optional<Npc> live = livingNpcByRespawnId.apply(respawnId);
		if (live.isEmpty()) {
			return "away";
		}
		return isAtPost(live.get(), post) ? "at post" : "away";
	}

	/**
	 * Formatted post coordinates for list/info, or empty if no post.
	 *
	 * @return e.g. {@code (x, y, z)} or {@code ""}
	 */
	public String postPosOf(long respawnId) {
		GuardPost post = posts.get(respawnId);
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
		RespawnRepository.FindResult found = repository.find(respawnId);
		if (!found.ok()) {
			commandFail(player, "make-guard", respawnId, "database query failed");
			return;
		}
		if (found.row().isEmpty()) {
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
		float yaw = Pose.lookYaw(player.getViewDirection(), player.getRotation());
		if (!repository.setGuardPost(respawnId, pos.x, pos.y, pos.z, yaw)) {
			commandFail(player, "make-guard", respawnId, "database write failed");
			return;
		}
		stopWatch(respawnId);
		GuardPost post = new GuardPost(respawnId, pos.x, pos.y, pos.z, yaw);
		posts.put(respawnId, post);
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
		if (!posts.containsKey(respawnId)) {
			player.sendTextMessage("Respawn #" + respawnId + " has no guard post.");
			return;
		}
		if (!repository.clearGuardPost(respawnId)) {
			commandFail(player, "guard-remove", respawnId, "database delete failed");
			return;
		}
		stopWatch(respawnId);
		posts.remove(respawnId);
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
		GuardPost post = posts.get(respawnId);
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
			sendToPost(live, post);
			watch.dispatched = true;
		}
	}

	private void startGlobalTickIfNeeded() {
		if (globalTimer != null && !globalTimer.isKilled()) {
			return;
		}
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
		if (globalTimer == null) {
			return;
		}
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
	 * Keeps the current watch (or creates one) so turn callbacks can check identity.
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
		Watch watch = watches.get(respawnId);
		if (watch == null) {
			watch = putWatch(respawnId, npcId);
		} else {
			watch.npcId = npcId;
		}
		startTurn(respawnId, watch, fromYaw, post.yaw());
	}

	/**
	 * Bounded repeating turn timer on the same {@link Watch}, then {@link #scheduleLock}.
	 */
	private void startTurn(long respawnId, Watch watch, float fromYaw, float toYaw) {
		watch.turnFromYaw = fromYaw;
		watch.turnToYaw = toYaw;
		watch.turnStep = 0;
		killTimer(watch);
		Watch captured = watch;
		Timer turn = new Timer(
				TURN_STEP_SECONDS,
				TURN_STEP_SECONDS,
				TURN_STEPS - 1,
				() -> plugin.enqueue(() -> turnTick(respawnId, captured)));
		watch.timer = turn;
		turn.start();
	}

	private void turnTick(long respawnId, Watch watch) {
		if (watches.get(respawnId) != watch) {
			return;
		}
		Npc live = World.getNpc(watch.npcId);
		if (live == null || live.isDead()) {
			stopWatch(respawnId);
			return;
		}
		watch.turnStep++;
		if (watch.turnStep > TURN_STEPS) {
			return;
		}
		float t = watch.turnStep / (float) TURN_STEPS;
		Pose.faceYaw(live, lerpYaw(watch.turnFromYaw, watch.turnToYaw, t));
		if (watch.turnStep >= TURN_STEPS) {
			scheduleLock(respawnId, watch);
		}
	}

	private void scheduleLock(long respawnId, Watch watch) {
		killTimer(watch);
		Watch captured = watch;
		Timer lockDelay = new Timer(1f, LOCK_AFTER_TURN_SECONDS, 0, () -> plugin.enqueue(() -> {
			if (watches.get(respawnId) != captured) {
				return;
			}
			Npc still = World.getNpc(captured.npcId);
			GuardPost post = posts.get(respawnId);
			if (still == null || still.isDead() || post == null) {
				stopWatch(respawnId);
				return;
			}
			if (!isAtPost(still, post)) {
				startWalk(respawnId, still.getGlobalID());
				return;
			}
			still.setLocked(true);
			stopWatch(respawnId);
		}));
		watch.timer = lockDelay;
		lockDelay.start();
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
		startGlobalTickIfNeeded();
		return watch;
	}

	/** Remove watch and kill its one-shot turn/lock timer if any. */
	private void stopWatch(long respawnId) {
		Watch watch = watches.remove(respawnId);
		killTimer(watch);
		if (watches.isEmpty()) {
			stopGlobalTick();
		}
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
	 * Unlock and un-static if needed, then {@link Npc#moveTo} the post xyz.
	 * Does not change rotation; facing is applied on arrive.
	 */
	private static void sendToPost(Npc npc, GuardPost post) {
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

	private static void commandFail(Player player, String operation, long respawnId, String detail) {
		player.sendTextMessage("<color=#ff6666>Failed:</color> " + operation
				+ " (#" + respawnId + ")\n  <color=#aaaaaa>" + detail + "</color>");
	}

	/**
	 * Per-guard walk state while not yet locked at post.
	 * Presence in {@link #watches} means status {@code walking}.
	 */
	private static final class Watch {
		/** Current body global id (updated on rebound). */
		long npcId;
		/** True after {@link #sendToPost} was issued once for this walk. */
		boolean dispatched;
		/** Earliest wall-clock ms when {@link #walkTick} may run (settle / due). */
		long nextDueMs;
		/** Turn or lock-delay timer; when set, {@link #globalTick} skips this watch. */
		Timer timer;
		int turnStep;
		float turnFromYaw;
		float turnToYaw;

		Watch(long npcId) {
			this.npcId = npcId;
		}
	}
}
