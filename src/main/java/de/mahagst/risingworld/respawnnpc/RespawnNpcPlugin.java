package de.mahagst.risingworld.respawnnpc;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import de.mahagst.risingworld.respawnnpc.RespawnService.UpdateMode;
import de.mahagst.risingworld.respawnnpc.guard.GuardService;
import net.risingworld.api.Plugin;
import net.risingworld.api.World;
import net.risingworld.api.database.Database;
import net.risingworld.api.events.EventMethod;
import net.risingworld.api.events.Listener;
import net.risingworld.api.events.player.PlayerCommandEvent;
import net.risingworld.api.objects.Npc;
import net.risingworld.api.objects.Player;
import net.risingworld.api.utils.Vector3f;

/**
 * Plugin entry: lifecycle, admin gate, all chat commands, LoS/#id focus helpers.
 * Domain logic: {@link RespawnService}. Guard walk/arrive: {@link GuardService}.
 */
public class RespawnNpcPlugin extends Plugin implements Listener {
	/** Max LoS / nearest-NPC focus distance in world units. */
	static final float LOS_DISTANCE = 10f;
	private static final Set<String> ALLOWED_UIDS = Set.of(
			"76561198002368372");

	private Database database;
	private RespawnService respawn;
	private GuardService guard;

	/**
	 * Open world DB, create schema, wire respawn + guard.
	 * Load both RAM stores, then start respawns/timers and guard walks.
	 */
	@Override
	public void onEnable() {
		String dbFile = worldDbFileName();
		database = getSQLiteConnection(getPath() + "/" + dbFile);
		if (database == null) {
			System.out.println("[RespawnNpc] Failed to open SQLite database: " + dbFile);
			return;
		}
		RespawnRepository repository = new RespawnRepository(database);
		repository.createSchema();
		respawn = new RespawnService(this, repository);
		guard = new GuardService(this, repository, respawn::livingNpcForRespawn);
		respawn.setGuardHooks(guard::onBodyReplaced, guard::onRespawnRemoved, guard::onPending);
		respawn.setGuardStatus(guard::statusOf);
		respawn.setGuardPostPos(guard::postPosOf);
		if (!respawn.loadMaps()) {
			System.out.println("[RespawnNpc] Failed to load respawn rows; plugin not started");
			abortEnable();
			return;
		}
		if (!guard.loadPosts()) {
			System.out.println("[RespawnNpc] Failed to load guard posts; plugin not started");
			abortEnable();
			return;
		}
		respawn.start();
		guard.startWalks();
		registerEventListener(this);
		System.out.println("[RespawnNpc] enabled (" + dbFile + ")");
	}

	/** Close DB and drop RAM if load failed before start. */
	private void abortEnable() {
		if (guard != null) {
			guard.disable();
		}
		if (respawn != null) {
			respawn.disable();
		}
		if (database != null) {
			database.close();
			database = null;
		}
		guard = null;
		respawn = null;
	}

	/** Tear down guard, cancel respawn timers, close SQLite. */
	@Override
	public void onDisable() {
		if (guard != null) {
			guard.disable();
		}
		if (respawn != null) {
			respawn.disable();
		}
		if (database != null) {
			database.close();
		}
		System.out.println("[RespawnNpc] disabled");
	}

	/**
	 * Chat command router. Non-plugin commands are ignored.
	 * Non-allowed players get no reply (command not cancelled for others' plugins).
	 * Own commands are cancelled after handling.
	 */
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
			case "/respawn-now" -> withOptionalIdOrFocus(player, args, "/respawn-now [#id]", respawn::now);
			case "/respawn-remove" -> withOptionalIdOrFocus(player, args, "/respawn-remove [#id]", respawn::remove);
			case "/respawn-info" -> withOptionalIdOrFocus(player, args, "/respawn-info [#id]", respawn::info);
			case "/respawn-list" -> respawnList(player, args);
			case "/make-guard" -> guardById(player, args, "/make-guard <id>", guard::makeGuard);
			case "/guard-remove" -> guardById(player, args, "/guard-remove <id>", guard::removeGuard);
			default -> {
			}
		}
	}

	/** True if {@code cmd} is one of this plugin's slash commands. */
	private static boolean isOurs(String cmd) {
		return cmd.equals("/make-respawn")
				|| cmd.equals("/respawn-update")
				|| cmd.equals("/respawn-now")
				|| cmd.equals("/respawn-remove")
				|| cmd.equals("/respawn-info")
				|| cmd.equals("/respawn-list")
				|| cmd.equals("/make-guard")
				|| cmd.equals("/guard-remove");
	}

	/**
	 * Admin gate: {@link Player#isAdmin()} or UID in {@link #ALLOWED_UIDS}.
	 */
	private static boolean isAllowed(Player player) {
		if (player.isAdmin()) {
			return true;
		}
		String uid = player.getUID();
		return uid != null && ALLOWED_UIDS.contains(uid);
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
		int intervalSeconds = RespawnService.intervalSecondsFromMinutes(minutes);
		withFocused(player, (p, npc) -> respawn.register(p, npc, intervalSeconds));
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
			Optional<RespawnNpc> savedOpt = respawn.require(player, request.respawnId());
			if (savedOpt.isEmpty()) {
				return;
			}
			respawn.applyUpdate(player, savedOpt.get(), null, request.mode(), request.timerMinutes());
			return;
		}
		withFocused(player, (p, npc) -> {
			Optional<RespawnNpc> saved = respawn.requireByNpc(p, npc);
			if (saved.isEmpty()) {
				return;
			}
			respawn.applyUpdate(p, saved.get(), npc, request.mode(), request.timerMinutes());
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

	private void guardById(Player player, String[] args, String usage, GuardIdHandler handler) {
		if (args.length != 2) {
			player.sendTextMessage("Usage: " + usage);
			return;
		}
		Long respawnId = parseRespawnIdToken(args[1]);
		if (respawnId == null) {
			player.sendTextMessage("Usage: " + usage);
			return;
		}
		handler.handle(player, respawnId);
	}

	/**
	 * {@code /respawn-list} or {@code /respawn-list timer}.
	 */
	private void respawnList(Player player, String[] args) {
		if (args.length >= 2) {
			if (args[1].equalsIgnoreCase("timer")) {
				guard.listTimers(player);
				return;
			}
			player.sendTextMessage("Usage: /respawn-list [timer]");
			return;
		}
		respawn.list(player);
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
			Optional<RespawnNpc> savedOpt = respawn.require(player, respawnId);
			if (savedOpt.isEmpty()) {
				return;
			}
			handler.handle(player, savedOpt.get());
			return;
		}
		withFocused(player, (p, npc) -> {
			Optional<RespawnNpc> saved = respawn.requireByNpc(p, npc);
			if (saved.isEmpty()) {
				return;
			}
			handler.handle(p, saved.get());
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
		for (Npc candidate : World.getAllNpcsInRange(pos, maxDistance)) {
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

	/**
	 * One SQLite file per world: {@code <World.getName()>.db}.
	 * Path-unsafe characters become {@code _}.
	 */
	private static String worldDbFileName() {
		String name = World.getName();
		if (name == null || name.isBlank()) {
			return "world.db";
		}
		String safe = name.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
		if (safe.isBlank()) {
			return "world.db";
		}
		return safe + ".db";
	}

	@FunctionalInterface
	private interface NpcHandler {
		void handle(Player player, Npc npc);
	}

	@FunctionalInterface
	private interface SavedHandler {
		void handle(Player player, RespawnNpc saved);
	}

	@FunctionalInterface
	private interface GuardIdHandler {
		void handle(Player player, long respawnId);
	}

	private record UpdateRequest(Long respawnId, UpdateMode mode, Integer timerMinutes) {
	}
}
