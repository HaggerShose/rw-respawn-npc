package de.mahagst.risingworld.respawnnpc;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.LongConsumer;
import java.util.function.LongFunction;

import net.risingworld.api.Plugin;
import net.risingworld.api.Timer;
import net.risingworld.api.World;
import net.risingworld.api.events.EventMethod;
import net.risingworld.api.events.Listener;
import net.risingworld.api.events.npc.NpcDeathEvent;
import net.risingworld.api.objects.Npc;
import net.risingworld.api.objects.Player;
import net.risingworld.api.utils.Quaternion;
import net.risingworld.api.utils.Vector3f;

/**
 * Respawn domain logic: RAM id maps, death -> one-shot timer, spawn replacement, register/update commands.
 * Snapshot is captured at register/{@code /respawn-update}, never on death.
 * Guard behaviour lives in {@code GuardService}; this class only fires hooks after spawn/remove.
 */
public final class RespawnService implements Listener {
	/** Cap for interval after converting minutes (24h). */
	static final int MAX_INTERVAL_SECONDS = 24 * 60 * 60;
	/** Effective delay when /make-respawn gets 0 or negative minutes (quick test). */
	static final int MIN_TEST_SECONDS = 1;

	private final Plugin plugin;
	private final RespawnRepository repository;

	/** Fast death filter: living body global id -> respawn_id. */
	private final Map<Long, Long> npcIdToRespawnId = new HashMap<>();
	/** Per respawn_id: living npc id, interval, pending timer, generation. */
	private final Map<Long, RespawnState> byRespawnId = new HashMap<>();
	/** Rows from {@link #loadMaps()} consumed by {@link #start()}. */
	private List<RespawnNpc> startupRows;
	/** False after {@link #disable()} so leftover enqueue callbacks no-op. */
	private boolean running;

	/** Fired after a successful {@link #spawnReplacement} (guard walks if post exists). */
	private BiConsumer<Long, Npc> onBodyReplaced = (id, npc) -> {
	};
	/** Fired after a successful DB delete in {@link #drop} so guard RAM is cleared. */
	private LongConsumer onRespawnRemoved = id -> {
	};
	/** Optional label for list colouring (e.g. walking / at post). */
	private LongFunction<String> guardStatus = id -> "";
	/** Optional formatted post coords for list lines. */
	private LongFunction<String> guardPostPos = id -> "";

	public RespawnService(Plugin plugin, RespawnRepository repository) {
		this.plugin = plugin;
		this.repository = repository;
	}

	/**
	 * Wire guard callbacks. Nulls become no-ops.
	 *
	 * @param onBodyReplaced   after spawn/rebind (respawnId, new body)
	 * @param onRespawnRemoved after successful remove (respawnId)
	 */
	public void setGuardHooks(BiConsumer<Long, Npc> onBodyReplaced, LongConsumer onRespawnRemoved) {
		this.onBodyReplaced = onBodyReplaced != null ? onBodyReplaced : (id, npc) -> {
		};
		this.onRespawnRemoved = onRespawnRemoved != null ? onRespawnRemoved : id -> {
		};
	}

	/** List/info guard state string provider ({@code GuardService#statusOf}). */
	public void setGuardStatus(LongFunction<String> guardStatus) {
		this.guardStatus = guardStatus != null ? guardStatus : id -> "";
	}

	/** List/info guard post position provider ({@code GuardService#postPosOf}). */
	public void setGuardPostPos(LongFunction<String> guardPostPos) {
		this.guardPostPos = guardPostPos != null ? guardPostPos : id -> "";
	}

	/**
	 * Load RAM maps from one {@code findAll}. Does not resume timers or spawn overdue bodies.
	 *
	 * @return false if the query failed (logged); RAM is left unchanged
	 */
	public boolean loadMaps() {
		Optional<List<RespawnNpc>> all = repository.findAll();
		if (all.isEmpty()) {
			System.out.println("[RespawnNpc] Failed to load respawn_npcs");
			return false;
		}
		npcIdToRespawnId.clear();
		byRespawnId.clear();
		List<RespawnNpc> rows = all.get();
		for (RespawnNpc saved : rows) {
			bind(saved.respawnId(), saved.currentNpcId(), saved.intervalSeconds());
		}
		startupRows = rows;
		return true;
	}

	/**
	 * Resume pending death timers (overdue spawn, else remaining delay) and register the death listener.
	 * Call after guard posts are loaded so spawn hooks see posts.
	 */
	public void start() {
		running = true;
		long now = System.currentTimeMillis();
		List<RespawnNpc> rows = startupRows;
		startupRows = null;
		if (rows != null) {
			for (RespawnNpc saved : rows) {
				Long next = saved.nextRespawn();
				if (next == null) {
					continue;
				}
				if (next <= now) {
					spawnReplacement(saved);
				} else {
					schedule(saved.respawnId(), (next - now) / 1000f);
				}
			}
		}
		plugin.registerEventListener(this);
	}

	/**
	 * Cancel timers, drop RAM maps and {@link #startupRows}, unregister the death listener.
	 * Safe if {@link #start()} never ran (load-fail abort).
	 */
	public void disable() {
		boolean started = running;
		running = false;
		cancelAllTimers();
		npcIdToRespawnId.clear();
		byRespawnId.clear();
		startupRows = null;
		if (started) {
			plugin.unregisterEventListener(this);
		}
	}

	/**
	 * Command lookup by respawn id. Sends chat on missing row or DB error.
	 *
	 * @return empty if the caller should stop
	 */
	public Optional<RespawnNpc> require(Player player, long respawnId) {
		RespawnRepository.FindResult result = repository.find(respawnId);
		if (!result.ok()) {
			commandFail(player, "load", respawnId, "database query failed");
			return Optional.empty();
		}
		if (result.row().isEmpty()) {
			player.sendTextMessage("Respawn #" + respawnId + " not found.");
			return Optional.empty();
		}
		return result.row();
	}

	/**
	 * Command lookup by living body. RAM map first, then PK load.
	 *
	 * @return empty if the caller should stop
	 */
	public Optional<RespawnNpc> requireByNpc(Player player, Npc npc) {
		Long respawnId = npcIdToRespawnId.get(npc.getGlobalID());
		if (respawnId == null) {
			player.sendTextMessage("NPC is not registered.");
			return Optional.empty();
		}
		return require(player, respawnId);
	}

	/**
	 * Living world NPC for a respawn id via RAM {@link RespawnState#npcId} + {@link World#getNpc}.
	 * Empty if unknown, unloaded, or dead. No DB read.
	 */
	public Optional<Npc> livingNpcForRespawn(long respawnId) {
		RespawnState state = byRespawnId.get(respawnId);
		if (state == null) {
			return Optional.empty();
		}
		Npc npc = World.getNpc(state.npcId);
		if (npc == null || npc.isDead()) {
			return Optional.empty();
		}
		return Optional.of(npc);
	}

	/**
	 * Convert admin minutes to stored seconds: {@code <=0} -> {@link #MIN_TEST_SECONDS},
	 * else minutes*60 capped at {@link #MAX_INTERVAL_SECONDS}.
	 */
	static int intervalSecondsFromMinutes(int minutes) {
		if (minutes <= 0) {
			return MIN_TEST_SECONDS;
		}
		return Math.min(minutes, MAX_INTERVAL_SECONDS / 60) * 60;
	}

	/**
	 * {@code /make-respawn}: capture snapshot from the NPC, spawn pose from the player, insert DB + RAM maps.
	 * Fails if this body is already registered.
	 */
	public void register(Player player, Npc npc, int intervalSeconds) {
		Long existingId = npcIdToRespawnId.get(npc.getGlobalID());
		if (existingId != null) {
			player.sendTextMessage("NPC is already registered (#" + existingId
					+ "). Use /respawn-update ...");
			return;
		}
		Vector3f spawnPos = player.getPosition();
		if (spawnPos == null) {
			player.sendTextMessage("Could not read player position.");
			return;
		}
		float yaw = Pose.lookYaw(player.getViewDirection(), player.getRotation());
		RespawnNpc snapshot = NpcSnapshot.capture(
				npc, spawnPos, yaw, 0L, intervalSeconds, null, System.currentTimeMillis());
		Optional<Long> id = repository.insert(snapshot);
		if (id.isEmpty()) {
			commandFail(player, "make-respawn", "database insert failed");
			return;
		}
		long respawnId = id.get();
		bind(respawnId, npc.getGlobalID(), intervalSeconds);
		player.sendTextMessage("Respawn NPC created (#" + respawnId + "). Interval: " + intervalSeconds + "s"
				+ " (spawn at your position)");
	}

	/**
	 * {@code /respawn-update} dispatcher: snapshot, pose, both, or timer only.
	 *
	 * @param focusedNpc   live body for snapshot modes; may be null for pose/timer with {@code #id}
	 * @param timerMinutes only used for {@link UpdateMode#TIMER}
	 */
	public void applyUpdate(
			Player player,
			RespawnNpc saved,
			Npc focusedNpc,
			UpdateMode mode,
			Integer timerMinutes) {
		switch (mode) {
			case SNAPSHOT -> updateSnapshot(player, saved, focusedNpc);
			case POSE -> updatePose(player, saved);
			case ALL -> updateAll(player, saved, focusedNpc);
			case TIMER -> updateInterval(player, saved, intervalSecondsFromMinutes(timerMinutes));
		}
	}

	/**
	 * {@code /respawn-now}: spawn replacement immediately (deletes living body after successful spawn).
	 */
	public void now(Player player, RespawnNpc saved) {
		if (!spawnReplacement(saved)) {
			commandFail(player, "respawn-now", saved.respawnId(), "could not spawn replacement NPC");
			return;
		}
		player.sendTextMessage("NPC reset.");
	}

	/** {@code /respawn-remove}: drop DB, timers, guard hook, RAM maps. Living NPC stays in world. */
	public void remove(Player player, RespawnNpc saved) {
		if (!drop(saved)) {
			commandFail(player, "respawn-remove", saved.respawnId(), "database delete failed");
			return;
		}
		player.sendTextMessage("NPC removed.");
	}

	/** {@code /respawn-info}: one formatted chat line for this entry. */
	public void info(Player player, RespawnNpc saved) {
		player.sendTextMessage(formatEntry(saved, World.getNpc(saved.currentNpcId())));
	}

	/** {@code /respawn-list}: all entries in one coloured chat block. */
	public void list(Player player) {
		Optional<List<RespawnNpc>> loaded = repository.findAll();
		if (loaded.isEmpty()) {
			commandFail(player, "respawn-list", "database query failed");
			return;
		}
		List<RespawnNpc> all = loaded.get();
		if (all.isEmpty()) {
			player.sendTextMessage("No respawn NPCs registered.");
			return;
		}
		StringBuilder out = new StringBuilder();
		out.append("<color=#aaaaaa>Respawn NPCs (").append(all.size()).append(")</color>");
		for (RespawnNpc saved : all) {
			out.append('\n').append(formatEntry(saved, World.getNpc(saved.currentNpcId())));
		}
		player.sendTextMessage(out.toString());
	}

	/**
	 * Death hot path: unknown body / already pending -> return.
	 * Else write {@code next_respawn}, schedule one-shot timer from RAM interval.
	 */
	@EventMethod
	public void onNpcDeath(NpcDeathEvent event) {
		if (event.isCancelled()) {
			return;
		}
		Npc npc = event.getNpc();
		if (npc == null) {
			return;
		}
		long npcId = npc.getGlobalID();
		Long respawnId = npcIdToRespawnId.get(npcId);
		if (respawnId == null) {
			return;
		}
		RespawnState state = byRespawnId.get(respawnId);
		if (state == null || state.timer != null) {
			return;
		}
		if (!repository.setNextRespawn(respawnId, System.currentTimeMillis() + state.intervalSeconds * 1000L)) {
			System.out.println("[RespawnNpc] Failed to persist next_respawn for #" + respawnId);
			return;
		}
		schedule(respawnId, state.intervalSeconds);
	}

	/** Overwrite snapshot columns; keeps spawn pose, interval, pending, current npc id. */
	private void updateSnapshot(Player player, RespawnNpc saved, Npc focusedNpc) {
		Npc live = resolveLiveForSnapshot(player, saved, focusedNpc);
		if (live == null) {
			return;
		}
		Vector3f keepPos = new Vector3f(saved.posX(), saved.posY(), saved.posZ());
		RespawnNpc snapshot = NpcSnapshot.capture(
				live,
				keepPos,
				saved.yaw(),
				saved.respawnId(),
				saved.intervalSeconds(),
				saved.nextRespawn(),
				saved.createdAt());
		if (!repository.replaceSnapshot(snapshot)) {
			commandFail(player, "respawn-update snapshot", saved.respawnId(), "database write failed");
			return;
		}
		player.sendTextMessage("Snapshot updated (#" + saved.respawnId() + ").");
	}

	/** Save spawn pose from the admin's current position/yaw. Does not touch guard_posts. */
	private void updatePose(Player player, RespawnNpc saved) {
		Vector3f spawnPos = player.getPosition();
		if (spawnPos == null) {
			player.sendTextMessage("Could not read player position.");
			return;
		}
		float yaw = Pose.lookYaw(player.getViewDirection(), player.getRotation());
		if (!repository.setSpawnPose(
				saved.respawnId(),
				spawnPos.x,
				spawnPos.y,
				spawnPos.z,
				yaw)) {
			commandFail(player, "respawn-update pose", saved.respawnId(), "database write failed");
			return;
		}
		player.sendTextMessage("Spawn pose updated (#" + saved.respawnId() + ").");
	}

	/** Snapshot attributes and spawn pose in one DB write. */
	private void updateAll(Player player, RespawnNpc saved, Npc focusedNpc) {
		Npc live = resolveLiveForSnapshot(player, saved, focusedNpc);
		if (live == null) {
			return;
		}
		Vector3f spawnPos = player.getPosition();
		if (spawnPos == null) {
			player.sendTextMessage("Could not read player position.");
			return;
		}
		float yaw = Pose.lookYaw(player.getViewDirection(), player.getRotation());
		RespawnNpc snapshot = NpcSnapshot.capture(
				live,
				spawnPos,
				yaw,
				saved.respawnId(),
				saved.intervalSeconds(),
				saved.nextRespawn(),
				saved.createdAt());
		if (!repository.replaceSnapshotAndPose(snapshot)) {
			commandFail(player, "respawn-update all", saved.respawnId(), "database write failed");
			return;
		}
		player.sendTextMessage("Snapshot and spawn pose updated (#" + saved.respawnId() + ").");
	}

	private Npc resolveLiveForSnapshot(Player player, RespawnNpc saved, Npc focusedNpc) {
		Npc live = focusedNpc;
		if (live == null) {
			live = World.getNpc(saved.currentNpcId());
		}
		if (live == null || live.isDead()) {
			player.sendTextMessage("NPC #" + saved.respawnId() + " is not alive; cannot update snapshot.");
			return null;
		}
		if (live.isTransient()) {
			player.sendTextMessage("Transient NPCs are not supported.");
			return null;
		}
		return live;
	}

	/** Persist interval and refresh {@link RespawnState#intervalSeconds}. Does not restart a pending timer. */
	private void updateInterval(Player player, RespawnNpc saved, int intervalSeconds) {
		if (saved.intervalSeconds() == intervalSeconds) {
			player.sendTextMessage("Interval not changed (already " + intervalSeconds + "s).");
			return;
		}
		if (!repository.setIntervalSeconds(saved.respawnId(), intervalSeconds)) {
			commandFail(player, "respawn-update timer", saved.respawnId(), "database write failed");
			return;
		}
		RespawnState state = byRespawnId.get(saved.respawnId());
		if (state != null) {
			state.intervalSeconds = intervalSeconds;
		}
		player.sendTextMessage("Interval updated to " + intervalSeconds + "s (#" + saved.respawnId() + ").");
	}

	/**
	 * Timer fired: consume this generation (timer gone, {@code next_respawn} kept on failure),
	 * then load row and {@link #spawnReplacement}.
	 */
	private void onRespawnDue(long respawnId, int generation) {
		if (!running) {
			return;
		}
		RespawnState state = byRespawnId.get(respawnId);
		if (state == null || state.generation != generation) {
			return;
		}
		cancelTimer(state);
		RespawnRepository.FindResult result = repository.find(respawnId);
		if (!result.ok()) {
			System.out.println("[RespawnNpc] DB read failed on respawn due #" + respawnId);
			return;
		}
		if (result.row().isEmpty()) {
			unbind(respawnId);
			return;
		}
		spawnReplacement(result.row().get());
	}

	/**
	 * Spawn at register-time pose, apply snapshot, delete old body if still alive,
	 * rebind RAM maps + DB {@code current_npc_id} and clear pending, fire {@link #onBodyReplaced}.
	 * Cancels a still-running timer only after a successful exchange; failure leaves it in place.
	 *
	 * @return false if spawn, apply, or DB write failed
	 */
	private boolean spawnReplacement(RespawnNpc saved) {
		RespawnState state = byRespawnId.get(saved.respawnId());
		Npc living = World.getNpc(saved.currentNpcId());
		boolean livingExists = living != null && !living.isDead();
		Vector3f pos = new Vector3f(saved.posX(), saved.posY(), saved.posZ());
		Quaternion rot = new Quaternion().fromAngles(0f, saved.yaw(), 0f);
		Npc spawned = World.spawnNpc(saved.typeId(), saved.variant(), pos, rot, false);
		if (spawned == null) {
			System.out.println("[RespawnNpc] spawnNpc returned null for #" + saved.respawnId());
			return false;
		}
		try {
			NpcSnapshot.apply(spawned, saved);
		} catch (RuntimeException e) {
			e.printStackTrace();
			System.out.println("[RespawnNpc] apply failed for #" + saved.respawnId() + "; deleting spawned NPC");
			spawned.delete();
			return false;
		}
		long newId = spawned.getGlobalID();
		if (!repository.completeRespawn(saved.respawnId(), newId)) {
			System.out.println("[RespawnNpc] completeRespawn failed for #" + saved.respawnId());
			spawned.delete();
			return false;
		}
		if (livingExists) {
			npcIdToRespawnId.remove(living.getGlobalID());
			living.delete();
		} else {
			npcIdToRespawnId.remove(saved.currentNpcId());
		}
		if (state != null) {
			state.npcId = newId;
			cancelTimer(state);
		}
		npcIdToRespawnId.put(newId, saved.respawnId());
		onBodyReplaced.accept(saved.respawnId(), spawned);
		return true;
	}

	/** DB delete first; on success cancel timer, guard hook, clear RAM. */
	private boolean drop(RespawnNpc saved) {
		if (!repository.delete(saved.respawnId())) {
			return false;
		}
		unbind(saved.respawnId());
		onRespawnRemoved.accept(saved.respawnId());
		npcIdToRespawnId.remove(saved.currentNpcId());
		return true;
	}

	/** Replace any existing timer with a one-shot that enqueues {@link #onRespawnDue}. */
	private void schedule(long respawnId, float delaySeconds) {
		RespawnState state = byRespawnId.get(respawnId);
		if (state == null) {
			return;
		}
		cancelTimer(state);
		int generation = state.generation;
		float delay = Math.max(delaySeconds, 0.1f);
		Timer timer = new Timer(1f, delay, 0, () -> plugin.enqueue(() -> onRespawnDue(respawnId, generation)));
		state.timer = timer;
		timer.start();
	}

	/** Invalidate generation so already-enqueued work no-ops, then kill the RW timer. */
	private void cancelTimer(RespawnState state) {
		state.generation++;
		if (state.timer != null && !state.timer.isKilled()) {
			state.timer.kill();
		}
		state.timer = null;
	}

	private void cancelAllTimers() {
		for (RespawnState state : byRespawnId.values()) {
			cancelTimer(state);
		}
	}

	private void bind(long respawnId, long npcId, int intervalSeconds) {
		RespawnState state = byRespawnId.get(respawnId);
		if (state == null) {
			state = new RespawnState();
			byRespawnId.put(respawnId, state);
		} else if (state.npcId != npcId) {
			npcIdToRespawnId.remove(state.npcId);
		}
		state.npcId = npcId;
		state.intervalSeconds = intervalSeconds;
		npcIdToRespawnId.put(npcId, respawnId);
	}

	private void unbind(long respawnId) {
		RespawnState state = byRespawnId.remove(respawnId);
		if (state == null) {
			return;
		}
		cancelTimer(state);
		npcIdToRespawnId.remove(state.npcId);
	}

	/** One coloured chat line: state, interval, spawn/post/now positions. */
	private String formatEntry(RespawnNpc saved, Npc current) {
		boolean pending = saved.nextRespawn() != null;
		boolean alive = current != null && !current.isDead();
		String state;
		String color;
		if (pending) {
			RespawnState ram = byRespawnId.get(saved.respawnId());
			if (ram != null && ram.timer != null) {
				long rest = Math.max(0, (saved.nextRespawn() - System.currentTimeMillis()) / 1000);
				state = "pending " + rest + "s";
			} else {
				state = "due, no timer";
			}
			color = "#ffcc66";
		} else if (current == null) {
			state = "missing";
			color = "#888888";
		} else if (!alive) {
			state = "dead";
			color = "#ff6666";
		} else {
			String guard = guardStatus.apply(saved.respawnId());
			if (guard == null || guard.isBlank()) {
				state = "alive";
				color = "#cccccc";
			} else {
				state = guard;
				color = switch (guard) {
					case "at post" -> "#66ff88";
					case "walking" -> "#66aaff";
					case "away" -> "#ccaa66";
					default -> "#cccccc";
				};
			}
		}
		String label = saved.typeName();
		if (saved.name() != null && !saved.name().isBlank()) {
			label = saved.typeName() + " \"" + saved.name() + "\"";
		}
		String now = alive ? fmtPos(current.getPosition()) : "-";
		String locked = alive && current.isLocked() ? "  <color=#ffaa44>locked</color>" : "";
		String post = guardPostPos.apply(saved.respawnId());
		String postPart = (post == null || post.isBlank()) ? "" : "   post " + post;
		return "<color=#ffffff>#" + saved.respawnId() + "</color>  " + label
				+ "  <color=" + color + ">" + state + "</color>" + locked
				+ "\n  " + saved.intervalSeconds() + "s"
				+ "   spawn " + fmtPos(saved.posX(), saved.posY(), saved.posZ())
				+ postPart
				+ "   now " + now;
	}

	private static String fmtPos(Vector3f pos) {
		if (pos == null) {
			return "-";
		}
		return fmtPos(pos.x, pos.y, pos.z);
	}

	private static String fmtPos(float x, float y, float z) {
		return String.format(Locale.US, "(%.1f, %.1f, %.1f)", x, y, z);
	}

	private static void commandFail(Player player, String operation, long respawnId, String detail) {
		commandFail(player, operation + " (#" + respawnId + ")", detail);
	}

	private static void commandFail(Player player, String operation, String detail) {
		player.sendTextMessage("<color=#ff6666>Failed:</color> " + operation
				+ "\n  <color=#aaaaaa>" + detail + "</color>");
	}

	/**
	 * Runtime row: living body, interval cache, at most one pending timer.
	 * {@code generation} invalidates already-enqueued timer work after cancel.
	 */
	private static final class RespawnState {
		long npcId;
		int intervalSeconds;
		Timer timer;
		int generation;
	}

	/**
	 * Modes for {@link #applyUpdate}.
	 * {@link #POSE} never touches the guard post.
	 */
	public enum UpdateMode {
		SNAPSHOT, POSE, ALL, TIMER
	}
}
