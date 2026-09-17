package de.mahagst.risingworld.respawnnpc.guard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongFunction;

import de.mahagst.risingworld.respawnnpc.Pose;
import de.mahagst.risingworld.respawnnpc.RespawnRepository;
import net.risingworld.api.Plugin;
import net.risingworld.api.Server;
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
 * far-poll until within {@value #NEAR_DIST}, then near-poll until {@value #ARRIVE_DIST},
 * step-turn to post yaw, then {@code setLocked(true)}.
 * While walking (including turn), a {@link Watch} is kept in {@link #watches}; after lock the watch is removed.
 * A slow return tick sends living {@code away} guards back to the post ({@code startWalk}).
 * Walk {@code moveTo} is re-issued after {@link #WALK_TIMEOUT_SECONDS} without arrive.
 * <p>
 * Combat: idle scan of player-to-post distance, medium/fast bands, then {@code isAlerted}
 * enters {@code combat} (walk cancelled). Combat NPCs are ignored by walk/return ticks.
 */
public final class GuardService {
	/** Delay after body spawn before issuing {@code moveTo} (lets the body settle). */
	private static final float SETTLE_SECONDS = 2f;
	/** Horizontal arrive threshold in world units (xz) -- start turn/lock. */
	private static final float ARRIVE_DIST = 0.1f;
	private static final float ARRIVE_DIST_SQ = ARRIVE_DIST * ARRIVE_DIST;
	/** Within this xz distance the near-poll runs (faster arrive checks). */
	private static final float NEAR_DIST = 1f;
	private static final float NEAR_DIST_SQ = NEAR_DIST * NEAR_DIST;
	/** Far walk poll while watches exist but outside {@link #NEAR_DIST} (0.5 Hz). */
	private static final float TICK_SECONDS = 2f;
	/** Near walk poll while at least one watch is within {@link #NEAR_DIST}. */
	private static final float NEAR_TICK_SECONDS = 0.25f;
	/** Re-issue {@code moveTo} if still not arrived after this many seconds. */
	private static final float WALK_TIMEOUT_SECONDS = 30f;
	private static final long WALK_TIMEOUT_MS = (long) (WALK_TIMEOUT_SECONDS * 1000f);
	/** Slow scan: living {@code away} guards get {@link #startWalk}. */
	private static final float RETURN_SECONDS = 10f;
	/** Player-to-post scan while posts exist. */
	private static final float PROX_IDLE_SECONDS = 30f;
	/** Posts with a player within {@link #MEDIUM_DIST}. */
	private static final float PROX_MEDIUM_SECONDS = 2f;
	/** NPCs with a player within {@link #FAST_DIST}; checks {@code isAlerted}. */
	private static final float PROX_FAST_SECONDS = 0.25f;
	/** Combat NPCs: leave when no longer alerted. */
	private static final float COMBAT_SECONDS = 12f;
	/** Player-to-post xz: enter/keep medium band. */
	private static final float MEDIUM_DIST = 160f;
	private static final float MEDIUM_DIST_SQ = MEDIUM_DIST * MEDIUM_DIST;
	/** Player-to-NPC xz: enter fast / alert check. */
	private static final float FAST_DIST = 56f;
	private static final float FAST_DIST_SQ = FAST_DIST * FAST_DIST;
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
	/** Posts with a player within {@link #MEDIUM_DIST} (not combat). */
	private final Set<Long> medium = new HashSet<>();
	/** Living guards with a player within {@link #FAST_DIST} (not combat). */
	private final Set<Long> fast = new HashSet<>();
	/** Alerted guards; walk/return ticks skip these. */
	private final Set<Long> combat = new HashSet<>();
	/** Far arrive-poll; running only while {@link #watches} is not empty. */
	private Timer globalTimer;
	/** Fast arrive-poll; running only while at least one watch is within {@link #NEAR_DIST}. */
	private Timer nearTimer;
	/** Slow away-return scan; running only while {@link #posts} is not empty. */
	private Timer returnTimer;
	/** Player-to-post scan; running only while {@link #posts} is not empty. */
	private Timer idleProximityTimer;
	/** Medium band; running only while {@link #medium} is not empty. */
	private Timer mediumTimer;
	/** Fast alert check; running only while {@link #fast} is not empty. */
	private Timer fastTimer;
	/** Combat leave check; running only while {@link #combat} is not empty. */
	private Timer combatTimer;

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
			long respawnId = post.respawnId();
			if (watches.containsKey(respawnId) || combat.contains(respawnId)) {
				continue;
			}
			Optional<Npc> live = livingNpcByRespawnId.apply(respawnId);
			if (live.isEmpty()) {
				continue;
			}
			Npc npc = live.get();
			if (isAtPost(npc, post)) {
				finishArrive(respawnId, npc.getGlobalID(), post);
			} else {
				startWalk(respawnId, npc.getGlobalID());
			}
		}
		startReturnTickIfNeeded();
		startIdleProximityIfNeeded();
		System.out.println("[RespawnNpc/Guard] enabled");
	}

	/**
	 * Stop all ticks, kill any turn timers, clear watches, proximity sets and RAM posts.
	 */
	public void disable() {
		stopReturnTick();
		stopIdleProximity();
		stopMediumTick();
		stopFastTick();
		stopCombatTick();
		stopNearTick();
		stopGlobalTick();
		for (Watch watch : watches.values()) {
			killTimer(watch);
		}
		watches.clear();
		posts.clear();
		medium.clear();
		fast.clear();
		combat.clear();
	}

	/**
	 * Called after a successful spawn/replacement when this respawn has a guard post.
	 * Schedules {@link #SETTLE_SECONDS} then walk; no-op (clears watch) if there is no post.
	 *
	 * @param respawnId respawn row id
	 * @param npc       newly spawned body
	 */
	public void onBodyReplaced(long respawnId, Npc npc) {
		leaveCombat(respawnId);
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
		dropProximity(respawnId);
		posts.remove(respawnId);
		if (posts.isEmpty()) {
			stopReturnTick();
			stopIdleProximity();
		}
	}

	/**
	 * Short status for {@code /respawn-list} / info colouring.
	 *
	 * @return {@code ""} if not a guard; {@code "combat"} if alerted;
	 *         {@code "walking"} if a watch exists; {@code "at post"} if the living body
	 *         is at the post; else {@code "away"}
	 */
	public String statusOf(long respawnId) {
		GuardPost post = posts.get(respawnId);
		if (post == null) {
			return "";
		}
		if (combat.contains(respawnId)) {
			return "combat";
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
		leaveCombat(respawnId);
		GuardPost post = new GuardPost(respawnId, pos.x, pos.y, pos.z, yaw);
		posts.put(respawnId, post);
		startReturnTickIfNeeded();
		startIdleProximityIfNeeded();
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
		dropProximity(respawnId);
		posts.remove(respawnId);
		if (posts.isEmpty()) {
			stopReturnTick();
			stopIdleProximity();
		}
		player.sendTextMessage("Guard removed (#" + respawnId + ").");
	}

	/**
	 * Put or replace a walking watch and set when {@link #walkTick} may run.
	 *
	 * @param delaySeconds wait before the first tick (0 = due immediately on next poll / {@link #startWalk})
	 */
	private void scheduleWalk(long respawnId, long npcId, float delaySeconds) {
		if (combat.contains(respawnId)) {
			return;
		}
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
	 * otherwise issue or re-issue {@code moveTo}. Starts the near-tick when within {@link #NEAR_DIST}.
	 */
	private void walkTick(long respawnId) {
		if (combat.contains(respawnId)) {
			return;
		}
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
		long now = System.currentTimeMillis();
		if (!watch.dispatched) {
			sendToPost(live, post);
			watch.dispatched = true;
			watch.moveIssuedAtMs = now;
		} else if (now - watch.moveIssuedAtMs >= WALK_TIMEOUT_MS) {
			// API has no "active moveTo?" query or cancel. A new moveTo replaces the previous
			// target under current engine behaviour (0.9.3); revisit if that changes.
			sendToPost(live, post);
			watch.moveIssuedAtMs = now;
		}
		if (isNearPost(live, post)) {
			startNearTickIfNeeded();
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

	/** Kill and null the far arrive timer. */
	private void stopGlobalTick() {
		if (globalTimer != null && !globalTimer.isKilled()) {
			globalTimer.kill();
		}
		globalTimer = null;
	}

	private void startNearTickIfNeeded() {
		if (nearTimer != null && !nearTimer.isKilled()) {
			return;
		}
		nearTimer = new Timer(
				NEAR_TICK_SECONDS,
				NEAR_TICK_SECONDS,
				-1,
				() -> plugin.enqueue(this::nearTick));
		nearTimer.start();
	}

	private void stopNearTick() {
		if (nearTimer != null && !nearTimer.isKilled()) {
			nearTimer.kill();
		}
		nearTimer = null;
	}

	private void startReturnTickIfNeeded() {
		if (posts.isEmpty()) {
			return;
		}
		if (returnTimer != null && !returnTimer.isKilled()) {
			return;
		}
		returnTimer = new Timer(
				RETURN_SECONDS,
				RETURN_SECONDS,
				-1,
				() -> plugin.enqueue(this::returnTick));
		returnTimer.start();
	}

	private void stopReturnTick() {
		if (returnTimer != null && !returnTimer.isKilled()) {
			returnTimer.kill();
		}
		returnTimer = null;
	}

	private void startIdleProximityIfNeeded() {
		if (posts.isEmpty()) {
			return;
		}
		if (idleProximityTimer != null && !idleProximityTimer.isKilled()) {
			return;
		}
		idleProximityTimer = new Timer(
				PROX_IDLE_SECONDS,
				0f,
				-1,
				() -> plugin.enqueue(this::idleScan));
		idleProximityTimer.start();
	}

	private void stopIdleProximity() {
		if (idleProximityTimer != null && !idleProximityTimer.isKilled()) {
			idleProximityTimer.kill();
		}
		idleProximityTimer = null;
	}

	private void startMediumTickIfNeeded() {
		if (medium.isEmpty()) {
			return;
		}
		if (mediumTimer != null && !mediumTimer.isKilled()) {
			return;
		}
		mediumTimer = new Timer(
				PROX_MEDIUM_SECONDS,
				0f,
				-1,
				() -> plugin.enqueue(this::mediumTick));
		mediumTimer.start();
	}

	private void stopMediumTick() {
		if (mediumTimer != null && !mediumTimer.isKilled()) {
			mediumTimer.kill();
		}
		mediumTimer = null;
	}

	private void startFastTickIfNeeded() {
		if (fast.isEmpty()) {
			return;
		}
		if (fastTimer != null && !fastTimer.isKilled()) {
			return;
		}
		fastTimer = new Timer(
				PROX_FAST_SECONDS,
				0f,
				-1,
				() -> plugin.enqueue(this::fastTick));
		fastTimer.start();
	}

	private void stopFastTick() {
		if (fastTimer != null && !fastTimer.isKilled()) {
			fastTimer.kill();
		}
		fastTimer = null;
	}

	private void startCombatTickIfNeeded() {
		if (combat.isEmpty()) {
			return;
		}
		if (combatTimer != null && !combatTimer.isKilled()) {
			return;
		}
		combatTimer = new Timer(
				COMBAT_SECONDS,
				0f,
				-1,
				() -> plugin.enqueue(this::combatTick));
		combatTimer.start();
	}

	private void stopCombatTick() {
		if (combatTimer != null && !combatTimer.isKilled()) {
			combatTimer.kill();
		}
		combatTimer = null;
	}

	private void dropProximity(long respawnId) {
		medium.remove(respawnId);
		fast.remove(respawnId);
		combat.remove(respawnId);
		stopBandTimersIfEmpty();
	}

	private void stopBandTimersIfEmpty() {
		if (medium.isEmpty()) {
			stopMediumTick();
		}
		if (fast.isEmpty()) {
			stopFastTick();
		}
		if (combat.isEmpty()) {
			stopCombatTick();
		}
	}

	/**
	 * Cancel walk, unlock, drop watch. Walk/return ticks skip this id until {@link #leaveCombat}.
	 */
	private void enterCombat(long respawnId) {
		if (!combat.add(respawnId)) {
			return;
		}
		medium.remove(respawnId);
		fast.remove(respawnId);
		stopBandTimersIfEmpty();
		Npc live = livingNpcByRespawnId.apply(respawnId).orElse(null);
		if (watches.containsKey(respawnId)) {
			if (live != null && !live.isDead()) {
				cancelWalkInPlace(live);
			}
			stopWatch(respawnId);
		} else if (live != null && !live.isDead()) {
			unlockIfNeeded(live);
		}
		startCombatTickIfNeeded();
	}

	/**
	 * Drop combat membership. Does not lock; {@code away} bodies are picked up by {@link #returnTick}.
	 */
	private void leaveCombat(long respawnId) {
		if (!combat.remove(respawnId)) {
			return;
		}
		if (combat.isEmpty()) {
			stopCombatTick();
		}
	}

	/**
	 * Every {@value #PROX_IDLE_SECONDS}s: player-to-post xz. Within {@link #MEDIUM_DIST} -> medium set.
	 */
	private void idleScan() {
		if (idleProximityTimer == null) {
			return;
		}
		List<Vector3f> players = onlinePlayerPositions();
		for (GuardPost post : new ArrayList<>(posts.values())) {
			long respawnId = post.respawnId();
			if (combat.contains(respawnId)) {
				continue;
			}
			if (minDistSqXZ(post.x(), post.z(), players) <= MEDIUM_DIST_SQ) {
				if (medium.add(respawnId)) {
					startMediumTickIfNeeded();
				}
			} else {
				medium.remove(respawnId);
				fast.remove(respawnId);
			}
		}
		stopBandTimersIfEmpty();
	}

	/**
	 * Every {@value #PROX_MEDIUM_SECONDS}s: keep medium while a player is within post range;
	 * promote to fast when a player is within {@link #FAST_DIST} of the living NPC.
	 */
	private void mediumTick() {
		if (mediumTimer == null) {
			return;
		}
		List<Vector3f> players = onlinePlayerPositions();
		for (Long respawnId : new ArrayList<>(medium)) {
			if (combat.contains(respawnId)) {
				medium.remove(respawnId);
				fast.remove(respawnId);
				continue;
			}
			GuardPost post = posts.get(respawnId);
			if (post == null || minDistSqXZ(post.x(), post.z(), players) > MEDIUM_DIST_SQ) {
				medium.remove(respawnId);
				fast.remove(respawnId);
				continue;
			}
			Optional<Npc> live = livingNpcByRespawnId.apply(respawnId);
			if (live.isEmpty() || live.get().isDead()) {
				fast.remove(respawnId);
				continue;
			}
			Vector3f npcPos = live.get().getPosition();
			if (npcPos != null && minDistSqXZ(npcPos.x, npcPos.z, players) <= FAST_DIST_SQ) {
				if (fast.add(respawnId)) {
					startFastTickIfNeeded();
				}
			} else {
				fast.remove(respawnId);
			}
		}
		stopBandTimersIfEmpty();
	}

	/**
	 * Every {@value #PROX_FAST_SECONDS}s: {@code isAlerted} -> {@link #enterCombat}.
	 */
	private void fastTick() {
		if (fastTimer == null) {
			return;
		}
		for (Long respawnId : new ArrayList<>(fast)) {
			if (combat.contains(respawnId)) {
				fast.remove(respawnId);
				continue;
			}
			Optional<Npc> live = livingNpcByRespawnId.apply(respawnId);
			if (live.isEmpty() || live.get().isDead()) {
				continue;
			}
			if (live.get().isAlerted()) {
				enterCombat(respawnId);
			}
		}
		stopBandTimersIfEmpty();
	}

	/**
	 * Every {@value #COMBAT_SECONDS}s: missing/dead or {@code !isAlerted} -> {@link #leaveCombat}.
	 */
	private void combatTick() {
		if (combatTimer == null) {
			return;
		}
		for (Long respawnId : new ArrayList<>(combat)) {
			Optional<Npc> live = livingNpcByRespawnId.apply(respawnId);
			if (live.isEmpty() || live.get().isDead() || !live.get().isAlerted()) {
				leaveCombat(respawnId);
			}
		}
	}

	private static List<Vector3f> onlinePlayerPositions() {
		Player[] all = Server.getAllPlayers();
		if (all == null || all.length == 0) {
			return List.of();
		}
		List<Vector3f> out = new ArrayList<>(all.length);
		for (Player player : all) {
			if (player == null) {
				continue;
			}
			Vector3f pos = player.getPosition();
			if (pos != null) {
				out.add(pos);
			}
		}
		return out;
	}

	private static float minDistSqXZ(float x, float z, List<Vector3f> positions) {
		float best = Float.POSITIVE_INFINITY;
		for (Vector3f pos : positions) {
			float dx = pos.x - x;
			float dz = pos.z - z;
			float d = dx * dx + dz * dz;
			if (d < best) {
				best = d;
			}
		}
		return best;
	}

	/**
	 * Every {@value #RETURN_SECONDS}s: living guards that are {@code away} (post, no watch, not at post)
	 * get {@link #startWalk}. Missing/dead bodies and {@code at post} / {@code walking} / {@code combat} are skipped.
	 */
	private void returnTick() {
		if (returnTimer == null) {
			return;
		}
		for (GuardPost post : new ArrayList<>(posts.values())) {
			long respawnId = post.respawnId();
			if (combat.contains(respawnId) || watches.containsKey(respawnId)) {
				continue;
			}
			Optional<Npc> live = livingNpcByRespawnId.apply(respawnId);
			if (live.isEmpty()) {
				continue;
			}
			Npc npc = live.get();
			if (isAtPost(npc, post)) {
				continue;
			}
			startWalk(respawnId, npc.getGlobalID());
		}
	}

	/**
	 * Every {@value #TICK_SECONDS}s: far walk poll for due watches (not mid-turn).
	 * Skips watches with a local turn/lock {@link Watch#timer}.
	 */
	private void globalTick() {
		if (globalTimer == null) {
			return;
		}
		long now = System.currentTimeMillis();
		for (Long respawnId : new ArrayList<>(watches.keySet())) {
			Watch watch = watches.get(respawnId);
			if (watch == null || watch.timer != null || watch.nextDueMs > now
					|| combat.contains(respawnId)) {
				continue;
			}
			walkTick(respawnId);
		}
	}

	/**
	 * Every {@value #NEAR_TICK_SECONDS}s: only watches already within {@link #NEAR_DIST}.
	 * Stops itself when no near walkers remain.
	 */
	private void nearTick() {
		if (nearTimer == null) {
			return;
		}
		long now = System.currentTimeMillis();
		boolean anyNear = false;
		for (Long respawnId : new ArrayList<>(watches.keySet())) {
			Watch watch = watches.get(respawnId);
			if (watch == null || watch.timer != null || watch.nextDueMs > now
					|| combat.contains(respawnId)) {
				continue;
			}
			GuardPost post = posts.get(respawnId);
			Npc live = liveNpc(respawnId, watch);
			if (post == null || live == null) {
				stopWatch(respawnId);
				continue;
			}
			if (!isNearPost(live, post)) {
				continue;
			}
			anyNear = true;
			walkTick(respawnId);
		}
		if (!anyNear) {
			stopNearTick();
		}
	}

	/**
	 * NPC reached the post: start step-turn toward post yaw, then lock.
	 * Keeps the current watch (or creates one) so turn callbacks can check identity.
	 */
	private void finishArrive(long respawnId, long npcId, GuardPost post) {
		if (combat.contains(respawnId)) {
			return;
		}
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
			// set the position to the post position after lock because it fixes the visual position glitch
			still.setPosition(new Vector3f(post.x(), post.y(), post.z()));
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
			stopNearTick();
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
	 * <p>
	 * The API exposes no active-moveTo query or cancel. Calling {@code moveTo} again is assumed
	 * to discard the previous target (Rising World 0.9.3). If the engine later queues targets
	 * instead, re-issue / timeout logic must be revisited.
	 */
	private static void sendToPost(Npc npc, GuardPost post) {
		if (npc == null || npc.isDead() || post == null) {
			return;
		}
		unlockIfNeeded(npc);
		npc.moveTo(new Vector3f(post.x(), post.y(), post.z()));
	}

	private static void unlockIfNeeded(Npc npc) {
		if (npc.isLocked()) {
			npc.setLocked(false);
		}
		if (npc.isStatic()) {
			npc.setStatic(false);
		}
	}

	/**
	 * Stop walking by issuing {@code moveTo} at the current position.
	 * API has no cancel; a new {@code moveTo} replaces the previous target (0.9.3).
	 */
	private static void cancelWalkInPlace(Npc npc) {
		unlockIfNeeded(npc);
		Vector3f pos = npc.getPosition();
		if (pos == null) {
			return;
		}
		npc.moveTo(new Vector3f(pos.x, pos.y, pos.z));
	}

	/** Squared horizontal (xz) distance to the post, or +inf if position missing. */
	private static float distSqXZ(Npc npc, GuardPost post) {
		Vector3f pos = npc.getPosition();
		if (pos == null) {
			return Float.POSITIVE_INFINITY;
		}
		float dx = pos.x - post.x();
		float dz = pos.z - post.z();
		return dx * dx + dz * dz;
	}

	/**
	 * True if horizontal (xz) distance to the post is within {@link #ARRIVE_DIST}.
	 * Uses squared distance (no sqrt).
	 */
	private static boolean isAtPost(Npc npc, GuardPost post) {
		return distSqXZ(npc, post) <= ARRIVE_DIST_SQ;
	}

	/**
	 * True if horizontal (xz) distance to the post is within {@link #NEAR_DIST}.
	 * Near zone uses the faster {@link #nearTick} poll.
	 */
	private static boolean isNearPost(Npc npc, GuardPost post) {
		return distSqXZ(npc, post) <= NEAR_DIST_SQ;
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
		/** True after {@link #sendToPost} was issued at least once for this walk. */
		boolean dispatched;
		/** Wall-clock ms of the last {@link #sendToPost} (timeout / re-issue). */
		long moveIssuedAtMs;
		/** Earliest wall-clock ms when {@link #walkTick} may run (settle / due). */
		long nextDueMs;
		/** Turn or lock-delay timer; when set, far/near ticks skip this watch. */
		Timer timer;
		int turnStep;
		float turnFromYaw;
		float turnToYaw;

		Watch(long npcId) {
			this.npcId = npcId;
		}
	}
}
