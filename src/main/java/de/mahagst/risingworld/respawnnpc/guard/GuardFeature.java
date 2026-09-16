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
import net.risingworld.api.events.EventMethod;
import net.risingworld.api.events.Listener;
import net.risingworld.api.events.npc.NpcDeathEvent;
import net.risingworld.api.events.player.PlayerCommandEvent;
import net.risingworld.api.objects.Npc;
import net.risingworld.api.objects.Player;
import net.risingworld.api.utils.Vector3f;

/**
 * Guard aspect: uses RespawnRepository.guard_posts + RAM map.
 * Spawn pose stays on respawn_npcs; this only stores a walk-to post.
 *
 * v0.1: /make-guard &lt;id&gt;, /remove-guard &lt;id&gt;; one moveTo, no rotation.
 * After respawn, moveTo is delayed so the NPC AI can initialize (same-tick moveTo looks like a slide).
 */
public final class GuardFeature implements Listener {
	/** Delay after spawn before moveTo; same-frame walk often slides/teleports. */
	private static final float WALK_AFTER_SPAWN_SECONDS = 0.5f;

	private final Plugin plugin;
	private final RespawnRepository repository;
	private final LongFunction<Optional<Npc>> livingNpcByRespawnId;

	private final GuardService service = new GuardService();
	private final Map<Long, Timer> pendingWalks = new HashMap<>();
	private boolean enabled;

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
				service.onBodyReplaced(post.respawnId(), live.get());
			}
		}
		plugin.registerEventListener(this);
		enabled = true;
		System.out.println("[RespawnNpc/Guard] enabled");
	}

	public void disable() {
		cancelAllPendingWalks();
		service.stop();
		plugin.unregisterEventListener(this);
		enabled = false;
	}

	/**
	 * After respawn body swap: schedule walk shortly later.
	 * Calling moveTo in the same tick as spawnNpc tends to slide the NPC instead of walking.
	 */
	public void onBodyReplaced(long respawnId, Npc npc) {
		if (!service.hasPost(respawnId)) {
			cancelPendingWalk(respawnId);
			service.onBodyReplaced(respawnId, npc);
			return;
		}
		scheduleWalkToPost(respawnId, npc.getGlobalID());
	}

	/**
	 * Clear RAM. DB row is removed by FK CASCADE when respawn_npcs row is deleted,
	 * or explicitly via /remove-guard.
	 */
	public void onRespawnRemoved(long respawnId) {
		cancelPendingWalk(respawnId);
		service.removePost(respawnId);
	}

	private void scheduleWalkToPost(long respawnId, long npcId) {
		cancelPendingWalk(respawnId);
		Timer timer = new Timer(1f, WALK_AFTER_SPAWN_SECONDS, 0, () -> plugin.enqueue(() -> {
			pendingWalks.remove(respawnId);
			Npc live = World.getNpc(npcId);
			if (live == null || live.isDead()) {
				return;
			}
			service.onBodyReplaced(respawnId, live);
		}));
		pendingWalks.put(respawnId, timer);
		timer.start();
	}

	private void cancelPendingWalk(long respawnId) {
		Timer timer = pendingWalks.remove(respawnId);
		if (timer != null && !timer.isKilled()) {
			timer.kill();
		}
	}

	private void cancelAllPendingWalks() {
		for (Timer timer : pendingWalks.values()) {
			if (!timer.isKilled()) {
				timer.kill();
			}
		}
		pendingWalks.clear();
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
			case "/make-guard" -> makeGuard(player, args);
			case "/remove-guard" -> removeGuard(player, args);
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
		service.unbindByNpcId(npc.getGlobalID());
	}

	private static boolean isOurs(String cmd) {
		return cmd.equals("/make-guard") || cmd.equals("/remove-guard");
	}

	private static boolean isAllowed(Player player) {
		if (player.isAdmin()) {
			return true;
		}
		String uid = player.getUID();
		return uid != null && uid.equals("76561198002368372");
	}

	private void makeGuard(Player player, String[] args) {
		if (!enabled) {
			player.sendTextMessage("Guard is not available.");
			return;
		}
		Long respawnId = parseIdArg(args);
		if (respawnId == null) {
			player.sendTextMessage("Usage: /make-guard <id>");
			return;
		}
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
		var rot = player.getRotation();
		if (pos == null || rot == null) {
			player.sendTextMessage("Could not read player position.");
			return;
		}
		if (!repository.setGuardPost(respawnId, pos.x, pos.y, pos.z, rot.x, rot.y, rot.z, rot.w)) {
			player.sendTextMessage("Could not save guard post.");
			return;
		}
		service.putAndBind(
				new GuardPost(respawnId, pos.x, pos.y, pos.z, rot.x, rot.y, rot.z, rot.w),
				live);
		player.sendTextMessage("Guard post set (#" + respawnId + ") at "
				+ fmtPos(pos.x, pos.y, pos.z) + ". NPC walking there.");
	}

	private void removeGuard(Player player, String[] args) {
		if (!enabled) {
			player.sendTextMessage("Guard is not available.");
			return;
		}
		Long respawnId = parseIdArg(args);
		if (respawnId == null) {
			player.sendTextMessage("Usage: /remove-guard <id>");
			return;
		}
		if (!service.hasPost(respawnId)) {
			player.sendTextMessage("Respawn #" + respawnId + " has no guard post.");
			return;
		}
		service.removePost(respawnId);
		repository.clearGuardPost(respawnId);
		player.sendTextMessage("Guard removed (#" + respawnId + ").");
	}

	/** Accepts `1` or `#1`. */
	private static Long parseIdArg(String[] args) {
		if (args.length != 2) {
			return null;
		}
		String raw = args[1];
		if (raw.startsWith("#")) {
			raw = raw.substring(1);
		}
		try {
			long id = Long.parseLong(raw);
			return id > 0 ? id : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static String fmtPos(float x, float y, float z) {
		return String.format(Locale.US, "(%.1f, %.1f, %.1f)", x, y, z);
	}
}
