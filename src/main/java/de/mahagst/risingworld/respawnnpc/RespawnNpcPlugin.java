package de.mahagst.risingworld.respawnnpc;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import de.mahagst.risingworld.respawnnpc.guard.GuardFeature;
import net.risingworld.api.Plugin;
import net.risingworld.api.Timer;
import net.risingworld.api.World;
import net.risingworld.api.database.Database;
import net.risingworld.api.events.EventMethod;
import net.risingworld.api.events.Listener;
import net.risingworld.api.events.npc.NpcDeathEvent;
import net.risingworld.api.events.player.PlayerCommandEvent;
import net.risingworld.api.objects.Npc;
import net.risingworld.api.objects.Player;
import net.risingworld.api.utils.Quaternion;
import net.risingworld.api.utils.Vector3f;

/**
 * Admin commands, death trigger, one-shot respawn timers.
 * Snapshot is taken at register/update, never on death.
 */
public class RespawnNpcPlugin extends Plugin implements Listener {
	static final float LOS_DISTANCE = 10f;
	static final int MAX_INTERVAL_SECONDS = 24 * 60 * 60;
	/** Effective delay when /make-respawn gets 0 or negative minutes (quick test). */
	static final int MIN_TEST_SECONDS = 1;
	private static final Set<String> ALLOWED_UIDS = Set.of(
			"76561198002368372");

	private Database database;
	private RespawnRepository repository;
	private GuardFeature guard;
	/** current npc id -> respawn_id */
	private final Map<Long, Long> npcIdToRespawnId = new HashMap<>();
	/** At most one pending RW timer per respawn_id. */
	private final Map<Long, Timer> timers = new HashMap<>();
	/** Deaths caused by /respawn-now delete() must not start a timer. */
	private final Set<Long> ignoringDeathNpcIds = new HashSet<>();

	@Override
	public void onEnable() {
		database = getSQLiteConnection(getPath() + "/respawn.db");
		if (database == null) {
			System.out.println("[RespawnNpc] Failed to open SQLite database");
			return;
		}
		repository = new RespawnRepository(database);
		repository.createSchema();
		loadNpcMap();
		guard = new GuardFeature(this, repository, this::livingNpcForRespawn);
		guard.enable();
		sweepAndResume();
		registerEventListener(this);
		System.out.println("[RespawnNpc] enabled");
	}

	@Override
	public void onDisable() {
		cancelAllTimers();
		if (guard != null) {
			guard.disable();
		}
		if (database != null) {
			database.execute("PRAGMA wal_checkpoint(TRUNCATE)");
			database.close();
		}
		System.out.println("[RespawnNpc] disabled");
	}

	@EventMethod
	public void onCommand(PlayerCommandEvent event) {
		String[] args = event.getCommand().split(" ");
		if (args.length == 0) {
			return;
		}
		String cmd = args[0].toLowerCase(Locale.ROOT);
		if (!isOurs(cmd)) {
			return;
		}
		Player player = event.getPlayer();
		if (!isAllowed(player)) {
			return;
		}
		event.setCancelled(true);
		switch (cmd) {
			case "/make-respawn" -> makeRespawn(player, args);
			case "/respawn-update" -> respawnUpdate(player, args);
			case "/respawn-now" -> respawnNow(player, args);
			case "/respawn-remove" -> respawnRemove(player, args);
			case "/respawn-info" -> respawnInfo(player, args);
			case "/respawn-list" -> list(player);
			default -> {
			}
		}
	}

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
		Optional<RespawnNpc> savedOpt = repository.find(respawnId);
		if (savedOpt.isEmpty()) {
			npcIdToRespawnId.remove(npcId);
			return;
		}
		RespawnNpc saved = savedOpt.get();
		if (saved.nextRespawn() != null) {
			return;
		}
		repository.setNextRespawn(respawnId, System.currentTimeMillis() + saved.intervalSeconds() * 1000L);
		schedule(respawnId, saved.intervalSeconds());
	}

	private static boolean isOurs(String cmd) {
		return cmd.equals("/make-respawn")
				|| cmd.equals("/respawn-update")
				|| cmd.equals("/respawn-now")
				|| cmd.equals("/respawn-remove")
				|| cmd.equals("/respawn-info")
				|| cmd.equals("/respawn-list");
	}

	private static boolean isAllowed(Player player) {
		if (player.isAdmin()) {
			return true;
		}
		String uid = player.getUID();
		return uid != null && ALLOWED_UIDS.contains(uid);
	}

	static int intervalSecondsFromMinutes(int minutes) {
		if (minutes <= 0) {
			return MIN_TEST_SECONDS;
		}
		return Math.min(minutes * 60, MAX_INTERVAL_SECONDS);
	}

	private void makeRespawn(Player player, String[] args) {
		if (args.length < 2) {
			player.sendTextMessage("Usage: /make-respawn <minutes>");
			return;
		}
		int minutes;
		try {
			minutes = Integer.parseInt(args[1]);
		} catch (NumberFormatException e) {
			player.sendTextMessage("Usage: /make-respawn <minutes>");
			return;
		}
		int intervalSeconds = intervalSecondsFromMinutes(minutes);
		withFocused(player, (p, npc) -> register(p, npc, intervalSeconds));
	}

	private void register(Player player, Npc npc, int intervalSeconds) {
		Optional<RespawnNpc> existing = repository.findByNpcId(npc.getGlobalID());
		if (existing.isPresent()) {
			player.sendTextMessage("NPC is already registered (#" + existing.get().respawnId()
					+ "). Use /respawn-update ...");
			return;
		}
		Vector3f spawnPos = player.getPosition();
		Quaternion spawnRot = player.getRotation();
		if (spawnPos == null || spawnRot == null) {
			player.sendTextMessage("Could not read player position.");
			return;
		}
		RespawnNpc snapshot = NpcSnapshot.capture(
				npc, spawnPos, spawnRot, 0L, intervalSeconds, null, System.currentTimeMillis());
		Optional<Long> id = repository.insert(snapshot);
		if (id.isEmpty()) {
			player.sendTextMessage("Could not save respawn NPC.");
			return;
		}
		npcIdToRespawnId.put(npc.getGlobalID(), id.get());
		player.sendTextMessage("Respawn NPC created (#" + id.get() + "). Interval: " + intervalSeconds + "s"
				+ " (spawn at your position)");
	}

	/**
	 * /respawn-update [#id] [snapshot|pose|all|timer <minutes>]
	 * Default mode is snapshot. Timer minutes required for timer mode.
	 */
	private void respawnUpdate(Player player, String[] args) {
		UpdateRequest request = parseUpdateRequest(args);
		if (request == null) {
			player.sendTextMessage(
					"Usage: /respawn-update [#id] [snapshot|pose|all|timer <minutes>]");
			return;
		}
		if (request.respawnId() != null) {
			Optional<RespawnNpc> savedOpt = repository.find(request.respawnId());
			if (savedOpt.isEmpty()) {
				player.sendTextMessage("Respawn #" + request.respawnId() + " not found.");
				return;
			}
			applyUpdate(player, savedOpt.get(), null, request.mode(), request.timerMinutes());
			return;
		}
		withFocused(player, (p, npc) -> {
			RespawnNpc saved = requireRegistered(p, npc);
			if (saved == null) {
				return;
			}
			applyUpdate(p, saved, npc, request.mode(), request.timerMinutes());
		});
	}

	private static UpdateRequest parseUpdateRequest(String[] args) {
		Long respawnId = null;
		UpdateMode mode = UpdateMode.SNAPSHOT;
		Integer timerMinutes = null;
		int i = 1;
		if (i < args.length && args[i].startsWith("#")) {
			String raw = args[i].substring(1);
			try {
				respawnId = Long.parseLong(raw);
			} catch (NumberFormatException e) {
				return null;
			}
			if (respawnId <= 0) {
				return null;
			}
			i++;
		}
		if (i < args.length) {
			String token = args[i].toLowerCase(Locale.ROOT);
			switch (token) {
				case "snapshot" -> i++;
				case "pose" -> {
					mode = UpdateMode.POSE;
					i++;
				}
				case "all" -> {
					mode = UpdateMode.ALL;
					i++;
				}
				case "timer" -> {
					mode = UpdateMode.TIMER;
					i++;
					if (i >= args.length) {
						return null;
					}
					try {
						timerMinutes = Integer.parseInt(args[i]);
					} catch (NumberFormatException e) {
						return null;
					}
					i++;
				}
				default -> {
					return null;
				}
			}
		}
		if (i != args.length) {
			return null;
		}
		return new UpdateRequest(respawnId, mode, timerMinutes);
	}

	private void applyUpdate(
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
		Quaternion keepRot = new Quaternion(saved.rotX(), saved.rotY(), saved.rotZ(), saved.rotW());
		RespawnNpc snapshot = NpcSnapshot.capture(
				live,
				keepPos,
				keepRot,
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

	private boolean updatePose(Player player, RespawnNpc saved, boolean quietSuccess) {
		Vector3f spawnPos = player.getPosition();
		Quaternion spawnRot = player.getRotation();
		if (spawnPos == null || spawnRot == null) {
			player.sendTextMessage("Could not read player position.");
			return false;
		}
		if (!repository.setSpawnPose(
				saved.respawnId(),
				spawnPos.x,
				spawnPos.y,
				spawnPos.z,
				spawnRot.x,
				spawnRot.y,
				spawnRot.z,
				spawnRot.w)) {
			player.sendTextMessage("Could not save spawn pose.");
			return false;
		}
		if (!quietSuccess) {
			player.sendTextMessage("Spawn pose updated (#" + saved.respawnId() + ").");
		}
		return true;
	}

	private void updateInterval(Player player, RespawnNpc saved, int intervalSeconds) {
		if (saved.intervalSeconds() == intervalSeconds) {
			player.sendTextMessage("Interval not changed (already " + intervalSeconds + "s).");
			return;
		}
		repository.setIntervalSeconds(saved.respawnId(), intervalSeconds);
		player.sendTextMessage("Interval updated to " + intervalSeconds + "s (#" + saved.respawnId() + ").");
	}

	private void now(Player player, RespawnNpc saved) {
		if (!spawnReplacement(saved)) {
			player.sendTextMessage("Could not spawn replacement NPC.");
			return;
		}
		player.sendTextMessage("NPC reset.");
	}

	private void remove(Player player, RespawnNpc saved) {
		drop(saved);
		player.sendTextMessage("NPC removed.");
	}

	private void info(Player player, RespawnNpc saved) {
		player.sendTextMessage(infoLine(saved, World.getNpc(saved.currentNpcId())));
	}

	private void respawnNow(Player player, String[] args) {
		withOptionalIdOrFocus(player, args, "/respawn-now [#id]", this::now);
	}

	private void respawnRemove(Player player, String[] args) {
		withOptionalIdOrFocus(player, args, "/respawn-remove [#id]", this::remove);
	}

	private void respawnInfo(Player player, String[] args) {
		withOptionalIdOrFocus(player, args, "/respawn-info [#id]", this::info);
	}

	/**
	 * Optional `#id` / `id` as sole argument; otherwise LoS/nearest focus.
	 * {@code /respawn-list} has no id form.
	 */
	private void withOptionalIdOrFocus(
			Player player,
			String[] args,
			String usage,
			SavedHandler handler) {
		if (args.length > 2) {
			player.sendTextMessage("Usage: " + usage);
			return;
		}
		if (args.length == 2) {
			Long respawnId = parseRespawnIdToken(args[1]);
			if (respawnId == null) {
				player.sendTextMessage("Usage: " + usage);
				return;
			}
			Optional<RespawnNpc> savedOpt = repository.find(respawnId);
			if (savedOpt.isEmpty()) {
				player.sendTextMessage("Respawn #" + respawnId + " not found.");
				return;
			}
			handler.handle(player, savedOpt.get());
			return;
		}
		withFocused(player, (p, npc) -> {
			RespawnNpc saved = requireRegistered(p, npc);
			if (saved == null) {
				return;
			}
			handler.handle(p, saved);
		});
	}

	/** Accepts `1` or `#1`. */
	private static Long parseRespawnIdToken(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String token = raw.startsWith("#") ? raw.substring(1) : raw;
		try {
			long id = Long.parseLong(token);
			return id > 0 ? id : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private void list(Player player) {
		List<RespawnNpc> all = repository.findAll();
		if (all.isEmpty()) {
			player.sendTextMessage("No respawn NPCs registered.");
			return;
		}
		for (RespawnNpc saved : all) {
			player.sendTextMessage(infoLine(saved, World.getNpc(saved.currentNpcId())));
		}
	}

	private RespawnNpc requireRegistered(Player player, Npc npc) {
		Optional<RespawnNpc> saved = repository.findByNpcId(npc.getGlobalID());
		if (saved.isEmpty()) {
			player.sendTextMessage("NPC is not registered.");
			return null;
		}
		return saved.get();
	}

	/**
	 * Prefer LoS NPC; if none, nearest non-transient NPC within {@link #LOS_DISTANCE}
	 * (same idea as the game's editnpc proximity fallback).
	 */
	private void withFocused(Player player, NpcHandler handler) {
		player.getNpcInLineOfSight(LOS_DISTANCE, npc -> {
			Npc target = npc;
			if (target == null) {
				target = findNearestNpc(player, LOS_DISTANCE);
			}
			if (target == null) {
				player.sendTextMessage("No NPC in focus or nearby.");
				return;
			}
			if (target.isTransient()) {
				player.sendTextMessage("Transient NPCs are not supported.");
				return;
			}
			handler.handle(player, target);
		});
	}

	private static Npc findNearestNpc(Player player, float maxDistance) {
		Vector3f pos = player.getPosition();
		if (pos == null) {
			return null;
		}
		float maxSq = maxDistance * maxDistance;
		Npc best = null;
		float bestSq = maxSq;
		for (Npc candidate : World.getAllNpcs()) {
			if (candidate == null || candidate.isDead() || candidate.isTransient()) {
				continue;
			}
			Vector3f npcPos = candidate.getPosition();
			if (npcPos == null) {
				continue;
			}
			float distSq = pos.distanceSquared(npcPos);
			if (distSq <= bestSq) {
				bestSq = distSq;
				best = candidate;
			}
		}
		return best;
	}

	private void onRespawnDue(long respawnId) {
		Optional<RespawnNpc> savedOpt = repository.find(respawnId);
		if (savedOpt.isEmpty()) {
			cancelTimer(respawnId);
			return;
		}
		spawnReplacement(savedOpt.get());
	}

	/** Silent spawn at register-time pose, apply snapshot, rebind current npc id. */
	private boolean spawnReplacement(RespawnNpc saved) {
		Npc living = World.getNpc(saved.currentNpcId());
		boolean livingExists = living != null && !living.isDead();
		Vector3f pos = new Vector3f(saved.posX(), saved.posY(), saved.posZ());
		Quaternion rot = new Quaternion(saved.rotX(), saved.rotY(), saved.rotZ(), saved.rotW());
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
		cancelTimer(saved.respawnId());
		if (guard != null) {
			guard.onBodyReplaced(saved.respawnId(), spawned);
		}
		return true;
	}

	private void drop(RespawnNpc saved) {
		cancelTimer(saved.respawnId());
		if (guard != null) {
			guard.onRespawnRemoved(saved.respawnId());
		}
		repository.delete(saved.respawnId());
		npcIdToRespawnId.remove(saved.currentNpcId());
	}

	private Optional<Npc> livingNpcForRespawn(long respawnId) {
		Optional<RespawnNpc> saved = repository.find(respawnId);
		if (saved.isEmpty()) {
			return Optional.empty();
		}
		Npc npc = World.getNpc(saved.get().currentNpcId());
		if (npc == null || npc.isDead()) {
			return Optional.empty();
		}
		return Optional.of(npc);
	}

	private void schedule(long respawnId, float delaySeconds) {
		cancelTimer(respawnId);
		float delay = Math.max(delaySeconds, 0.1f);
		Timer timer = new Timer(1f, delay, 0, () -> enqueue(() -> onRespawnDue(respawnId)));
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

	private void loadNpcMap() {
		for (RespawnNpc saved : repository.findAll()) {
			npcIdToRespawnId.put(saved.currentNpcId(), saved.respawnId());
		}
	}

	/** Startup: spawn overdue / missing NPCs, or schedule remaining delay. */
	private void sweepAndResume() {
		long now = System.currentTimeMillis();
		for (RespawnNpc saved : repository.findAll()) {
			Long next = saved.nextRespawn();
			if (next != null) {
				if (next <= now) {
					spawnReplacement(saved);
				} else {
					schedule(saved.respawnId(), (next - now) / 1000f);
				}
				continue;
			}
			Npc current = World.getNpc(saved.currentNpcId());
			if (current == null || current.isDead()) {
				spawnReplacement(saved);
			}
		}
	}

	private static String infoLine(RespawnNpc saved, Npc current) {
		boolean pending = saved.nextRespawn() != null;
		String rest = "-";
		if (pending) {
			rest = Math.max(0, (saved.nextRespawn() - System.currentTimeMillis()) / 1000) + "s";
		}
		String label = saved.typeName();
		if (saved.name() != null && !saved.name().isBlank()) {
			label = saved.typeName() + " \"" + saved.name() + "\"";
		}
		boolean alive = current != null && !current.isDead();
		String currentPos = alive ? fmtPos(current.getPosition()) : "-";
		return "#" + saved.respawnId() + " " + label
				+ " npc=" + saved.currentNpcId() + " " + (alive ? "alive" : "dead")
				+ " spawn=" + fmtPos(saved.posX(), saved.posY(), saved.posZ())
				+ " current=" + currentPos
				+ " interval=" + saved.intervalSeconds() + "s"
				+ " pending=" + (pending ? "yes " + rest : "no");
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

	@FunctionalInterface
	private interface NpcHandler {
		void handle(Player player, Npc npc);
	}

	@FunctionalInterface
	private interface SavedHandler {
		void handle(Player player, RespawnNpc saved);
	}

	private enum UpdateMode {
		SNAPSHOT, POSE, ALL, TIMER
	}

	private record UpdateRequest(Long respawnId, UpdateMode mode, Integer timerMinutes) {
	}
}
