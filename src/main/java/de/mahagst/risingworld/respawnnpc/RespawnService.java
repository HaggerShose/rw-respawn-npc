package de.mahagst.risingworld.respawnnpc;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.LongConsumer;
import java.util.function.LongFunction;

import de.mahagst.risingworld.respawnnpc.guard.GuardService;
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
 * Guard behaviour lives in {@code GuardFeature}; this class only fires hooks after spawn/remove.
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
	/** Reverse map for {@link #livingNpcForRespawn} without a DB round-trip. */
	private final Map<Long, Long> respawnIdToNpcId = new HashMap<>();
	/** Interval cache so death scheduling does not need a full-row {@code find}. */
	private final Map<Long, Integer> respawnIdToInterval = new HashMap<>();
	/** At most one pending RW respawn timer per respawn_id. */
	private final Map<Long, Timer> timers = new HashMap<>();
	/** Deaths caused by {@code /respawn-now} {@code delete()} must not start a timer. */
	private final Set<Long> ignoringDeathNpcIds = new HashSet<>();

	/** Fired after a successful {@link #spawnReplacement} (guard walks if post exists). */
	private BiConsumer<Long, Npc> onBodyReplaced = (id, npc) -> {
	};
	/** Fired before DB delete in {@link #drop} so guard RAM is cleared. */
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
	 * @param onBodyReplaced  after spawn/rebind (respawnId, new body)
	 * @param onRespawnRemoved before/around remove (respawnId)
	 */
	public void setGuardHooks(BiConsumer<Long, Npc> onBodyReplaced, LongConsumer onRespawnRemoved) {
		this.onBodyReplaced = onBodyReplaced != null ? onBodyReplaced : (id, npc) -> {
		};
		this.onRespawnRemoved = onRespawnRemoved != null ? onRespawnRemoved : id -> {
		};
	}

	/** List/info guard state string provider ({@code GuardFeature#statusOf}). */
	public void setGuardStatus(LongFunction<String> guardStatus) {
		this.guardStatus = guardStatus != null ? guardStatus : id -> "";
	}

	/** List/info guard post position provider ({@code GuardFeature#postPosOf}). */
	public void setGuardPostPos(LongFunction<String> guardPostPos) {
		this.guardPostPos = guardPostPos != null ? guardPostPos : id -> "";
	}

	/**
	 * Load RAM maps from one {@code findAll}, resume pending death timers, register this as event listener.
	 */
	public void enable() {
		loadAndResume();
		plugin.registerEventListener(this);
	}

	/** Cancel all pending respawn timers and unregister the death listener. */
	public void disable() {
		cancelAllTimers();
		plugin.unregisterEventListener(this);
	}

	/** Full DB row by respawn id (commands / spawn due). */
	public Optional<RespawnNpc> find(long respawnId) {
		return repository.find(respawnId);
	}

	/** Full DB row by current living body id (e.g. already-registered check). */
	public Optional<RespawnNpc> findByNpcId(long npcId) {
		return repository.findByNpcId(npcId);
	}

	/**
	 * Living world NPC for a respawn id via RAM {@link #respawnIdToNpcId} + {@link World#getNpc}.
	 * Empty if unknown, unloaded, or dead. No DB read.
	 */
	public Optional<Npc> livingNpcForRespawn(long respawnId) {
		Long npcId = respawnIdToNpcId.get(respawnId);
		if (npcId == null) {
			return Optional.empty();
		}
		Npc npc = World.getNpc(npcId);
		if (npc == null || npc.isDead()) {
			return Optional.empty();
		}
		return Optional.of(npc);
	}

	/**
	 * Convert admin minutes to stored seconds: {@code <=0} -> {@link #MIN_TEST_SECONDS}, else minutes*60 capped at {@link #MAX_INTERVAL_SECONDS}.
	 */
	static int intervalSecondsFromMinutes(int minutes) {
		if (minutes <= 0) {
			return MIN_TEST_SECONDS;
		}
		return Math.min(minutes * 60, MAX_INTERVAL_SECONDS);
	}

	/**
	 * {@code /make-respawn}: capture snapshot from the NPC, spawn pose from the player, insert DB + RAM maps.
	 * Fails if this body is already registered.
	 */
	public void register(Player player, Npc npc, int intervalSeconds) {
		Optional<RespawnNpc> existing = repository.findByNpcId(npc.getGlobalID());
		if (existing.isPresent()) {
			player.sendTextMessage("NPC is already registered (#" + existing.get().respawnId()
					+ "). Use /respawn-update ...");
			return;
		}
		Vector3f spawnPos = player.getPosition();
		if (spawnPos == null) {
			player.sendTextMessage("Could not read player position.");
			return;
		}
		float yaw = GuardService.lookYaw(player.getViewDirection(), player.getRotation());
		RespawnNpc snapshot = NpcSnapshot.capture(
				npc, spawnPos, yaw, 0L, intervalSeconds, null, System.currentTimeMillis());
		Optional<Long> id = repository.insert(snapshot);
		if (id.isEmpty()) {
			player.sendTextMessage("Could not save respawn NPC.");
			return;
		}
		long respawnId = id.get();
		npcIdToRespawnId.put(npc.getGlobalID(), respawnId);
		respawnIdToNpcId.put(respawnId, npc.getGlobalID());
		respawnIdToInterval.put(respawnId, intervalSeconds);
		player.sendTextMessage("Respawn NPC created (#" + respawnId + "). Interval: " + intervalSeconds + "s"
				+ " (spawn at your position)");
	}

	/**
	 * {@code /respawn-update} dispatcher: snapshot, pose, both, or timer only.
	 *
	 * @param focusedNpc live body for snapshot modes; may be null for pose/timer with {@code #id}
	 * @param timerMinutes  only used for {@link UpdateMode#TIMER}
	 */
	public void applyUpdate(
			Player player,
			RespawnNpc saved,
			Npc focusedNpc,
			UpdateMode mode,
			Integer timerMinutes) {
		switch (mode) {
			case SNAPSHOT -> updateSnapshot(player, saved, focusedNpc, false);
			case POSE -> updatePose(player, saved, false);
			case ALL -> {
				if (!updateSnapshot(player, saved, focusedNpc, true)) {
					return;
				}
				if (!updatePose(player, saved, true)) {
					return;
				}
				player.sendTextMessage("Snapshot and spawn pose updated (#" + saved.respawnId() + ").");
			}
			case TIMER -> updateInterval(player, saved, intervalSecondsFromMinutes(timerMinutes));
		}
	}

	/**
	 * {@code /respawn-now}: spawn replacement immediately (deletes living body after successful spawn).
	 */
	public void now(Player player, RespawnNpc saved) {
		if (!spawnReplacement(saved)) {
			player.sendTextMessage("Could not spawn replacement NPC.");
			return;
		}
		player.sendTextMessage("NPC reset.");
	}

	/**
	 * Silent spawn replacement (same as {@link #now} without chat).
	 * Kept for later recovery paths; guard core no longer calls this.
	 *
	 * @return false if row missing or spawn failed
	 */
	public boolean forceReplace(long respawnId) {
		Optional<RespawnNpc> saved = repository.find(respawnId);
		if (saved.isEmpty()) {
			return false;
		}
		return spawnReplacement(saved.get());
	}

	/** {@code /respawn-remove}: drop DB, timers, guard hook, RAM maps. Living NPC stays in world. */
	public void remove(Player player, RespawnNpc saved) {
		drop(saved);
		player.sendTextMessage("NPC removed.");
	}

	/** {@code /respawn-info}: one formatted chat line for this entry. */
	public void info(Player player, RespawnNpc saved) {
		player.sendTextMessage(formatEntry(saved, World.getNpc(saved.currentNpcId())));
	}

	/** {@code /respawn-list}: all entries in one coloured chat block. */
	public void list(Player player) {
		List<RespawnNpc> all = repository.findAll();
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
	 * Death hot path: ignore-set / unknown body / already pending -> return.
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
		if (ignoringDeathNpcIds.remove(npcId)) {
			return;
		}
		Long respawnId = npcIdToRespawnId.get(npcId);
		if (respawnId == null) {
			return;
		}
		if (timers.containsKey(respawnId)) {
			return;
		}
		Integer interval = respawnIdToInterval.get(respawnId);
		if (interval == null) {
			Optional<RespawnNpc> savedOpt = repository.find(respawnId);
			if (savedOpt.isEmpty()) {
				npcIdToRespawnId.remove(npcId);
				respawnIdToNpcId.remove(respawnId);
				return;
			}
			interval = savedOpt.get().intervalSeconds();
			respawnIdToInterval.put(respawnId, interval);
		}
		repository.setNextRespawn(respawnId, System.currentTimeMillis() + interval * 1000L);
		schedule(respawnId, interval);
	}

	/** Overwrite snapshot columns; keeps spawn pose, interval, pending, current npc id. */
	private boolean updateSnapshot(Player player, RespawnNpc saved, Npc focusedNpc, boolean quietSuccess) {
		Npc live = focusedNpc;
		if (live == null) {
			live = World.getNpc(saved.currentNpcId());
		}
		if (live == null || live.isDead()) {
			player.sendTextMessage("NPC #" + saved.respawnId() + " is not alive; cannot update snapshot.");
			return false;
		}
		if (live.isTransient()) {
			player.sendTextMessage("Transient NPCs are not supported.");
			return false;
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
			player.sendTextMessage("Could not save snapshot.");
			return false;
		}
		if (!quietSuccess) {
			player.sendTextMessage("Snapshot updated (#" + saved.respawnId() + ").");
		}
		return true;
	}

	/** Save spawn pose from the admin's current position/yaw. Does not touch guard_posts. */
	private boolean updatePose(Player player, RespawnNpc saved, boolean quietSuccess) {
		Vector3f spawnPos = player.getPosition();
		if (spawnPos == null) {
			player.sendTextMessage("Could not read player position.");
			return false;
		}
		float yaw = GuardService.lookYaw(player.getViewDirection(), player.getRotation());
		if (!repository.setSpawnPose(
				saved.respawnId(),
				spawnPos.x,
				spawnPos.y,
				spawnPos.z,
				yaw)) {
			player.sendTextMessage("Could not save spawn pose.");
			return false;
		}
		if (!quietSuccess) {
			player.sendTextMessage("Spawn pose updated (#" + saved.respawnId() + ").");
		}
		return true;
	}

	/** Persist interval and refresh {@link #respawnIdToInterval}. Does not restart a pending timer. */
	private void updateInterval(Player player, RespawnNpc saved, int intervalSeconds) {
		if (saved.intervalSeconds() == intervalSeconds) {
			player.sendTextMessage("Interval not changed (already " + intervalSeconds + "s).");
			return;
		}
		repository.setIntervalSeconds(saved.respawnId(), intervalSeconds);
		respawnIdToInterval.put(saved.respawnId(), intervalSeconds);
		player.sendTextMessage("Interval updated to " + intervalSeconds + "s (#" + saved.respawnId() + ").");
	}

	/** Timer fired: load row and {@link #spawnReplacement}. */
	private void onRespawnDue(long respawnId) {
		Optional<RespawnNpc> savedOpt = repository.find(respawnId);
		if (savedOpt.isEmpty()) {
			cancelTimer(respawnId);
			return;
		}
		spawnReplacement(savedOpt.get());
	}

	/**
	 * Spawn at register-time pose, apply snapshot, delete old body if still alive (ignore that death),
	 * rebind RAM maps + DB {@code current_npc_id}, clear pending, fire {@link #onBodyReplaced}.
	 *
	 * @return false if {@code spawnNpc} returned null
	 */
	private boolean spawnReplacement(RespawnNpc saved) {
		Npc living = World.getNpc(saved.currentNpcId());
		boolean livingExists = living != null && !living.isDead();
		Vector3f pos = new Vector3f(saved.posX(), saved.posY(), saved.posZ());
		Quaternion rot = new Quaternion().fromAngles(0f, saved.yaw(), 0f);
		Npc spawned = World.spawnNpc(saved.typeId(), saved.variant(), pos, rot, false);
		if (spawned == null) {
			System.out.println("[RespawnNpc] spawnNpc returned null for #" + saved.respawnId());
			return false;
		}
		NpcSnapshot.apply(spawned, saved);
		if (livingExists) {
			long oldId = living.getGlobalID();
			ignoringDeathNpcIds.add(oldId);
			npcIdToRespawnId.remove(oldId);
			living.delete();
		} else {
			npcIdToRespawnId.remove(saved.currentNpcId());
		}
		long newId = spawned.getGlobalID();
		repository.setCurrentNpcId(saved.respawnId(), newId);
		repository.setNextRespawn(saved.respawnId(), null);
		npcIdToRespawnId.put(newId, saved.respawnId());
		respawnIdToNpcId.put(saved.respawnId(), newId);
		cancelTimer(saved.respawnId());
		onBodyReplaced.accept(saved.respawnId(), spawned);
		return true;
	}

	/** Cancel timer, guard-removed hook, DB delete, clear RAM maps for this row. */
	private void drop(RespawnNpc saved) {
		cancelTimer(saved.respawnId());
		onRespawnRemoved.accept(saved.respawnId());
		repository.delete(saved.respawnId());
		Long mapped = respawnIdToNpcId.remove(saved.respawnId());
		if (mapped != null) {
			npcIdToRespawnId.remove(mapped);
		}
		npcIdToRespawnId.remove(saved.currentNpcId());
		respawnIdToInterval.remove(saved.respawnId());
	}

	/** Replace any existing timer with a one-shot that enqueues {@link #onRespawnDue}. */
	private void schedule(long respawnId, float delaySeconds) {
		cancelTimer(respawnId);
		float delay = Math.max(delaySeconds, 0.1f);
		Timer timer = new Timer(1f, delay, 0, () -> plugin.enqueue(() -> onRespawnDue(respawnId)));
		timers.put(respawnId, timer);
		timer.start();
	}

	private void cancelTimer(long respawnId) {
		Timer timer = timers.remove(respawnId);
		if (timer != null && !timer.isKilled()) {
			timer.kill();
		}
	}

	private void cancelAllTimers() {
		for (Timer timer : timers.values()) {
			if (!timer.isKilled()) {
				timer.kill();
			}
		}
		timers.clear();
	}

	/** Startup: RAM maps from one findAll, then resume death timers only. */
	private void loadAndResume() {
		npcIdToRespawnId.clear();
		respawnIdToNpcId.clear();
		respawnIdToInterval.clear();
		long now = System.currentTimeMillis();
		for (RespawnNpc saved : repository.findAll()) {
			npcIdToRespawnId.put(saved.currentNpcId(), saved.respawnId());
			respawnIdToNpcId.put(saved.respawnId(), saved.currentNpcId());
			respawnIdToInterval.put(saved.respawnId(), saved.intervalSeconds());
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

	/** One coloured chat line: state, interval, spawn/post/now positions. */
	private String formatEntry(RespawnNpc saved, Npc current) {
		boolean pending = saved.nextRespawn() != null;
		boolean alive = current != null && !current.isDead();
		String state;
		String color;
		if (pending) {
			long rest = Math.max(0, (saved.nextRespawn() - System.currentTimeMillis()) / 1000);
			state = "pending " + rest + "s";
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
					case "waiting" -> "#66ccff";
					case "combat" -> "#ff5555";
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

	/**
	 * Modes for {@link #applyUpdate}.
	 * {@link #POSE} never touches the guard post.
	 */
	public enum UpdateMode {
		SNAPSHOT, POSE, ALL, TIMER
	}
}
