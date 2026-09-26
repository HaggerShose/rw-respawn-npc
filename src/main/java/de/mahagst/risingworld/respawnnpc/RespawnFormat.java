package de.mahagst.risingworld.respawnnpc;

import java.util.Locale;

import net.risingworld.api.objects.Npc;
import net.risingworld.api.objects.Player;
import net.risingworld.api.utils.Vector3f;

/**
 * Chat lines for {@code /respawn-info} and {@code /respawn-list}.
 * Guard labels and post coords are passed in; this class does not talk to {@code GuardService}.
 */
final class RespawnFormat {
	private RespawnFormat() {
	}

	/**
	 * One coloured chat line: state, interval, spawn/post/now positions.
	 *
	 * @param hasTimer     RAM one-shot timer is live for this id
	 * @param worldNowMs   {@code Server.getIngameTimestamp()}
	 * @param guardStatus  {@code GuardService#statusOf}, or blank if not a guard
	 * @param guardPostPos formatted post coords, or blank if none
	 */
	static String formatEntry(
			RespawnNpc saved,
			Npc current,
			boolean hasTimer,
			long worldNowMs,
			String guardStatus,
			String guardPostPos) {
		boolean dueDb = saved.nextRespawn() != null;
		boolean alive = current != null && !current.isDead();
		String state;
		String color;
		if (dueDb || hasTimer) {
			if (hasTimer) {
				if (!dueDb || saved.nextRespawn() <= worldNowMs) {
					state = "pending (retry)";
				} else {
					long rest = Math.max(0, (saved.nextRespawn() - worldNowMs) / 1000);
					state = "pending " + rest + "s";
				}
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
		} else if (guardStatus == null || guardStatus.isBlank()) {
			state = "alive";
			color = "#cccccc";
		} else {
			state = guardStatus;
			color = switch (guardStatus) {
				case "at post" -> "#66ff88";
				case "walking" -> "#66aaff";
				case "away" -> "#ccaa66";
				case "combat" -> "#ff5555";
				default -> "#cccccc";
			};
		}
		String label = saved.typeName();
		if (saved.name() != null && !saved.name().isBlank()) {
			label = saved.typeName() + " \"" + saved.name() + "\"";
		}
		String now = alive ? fmtPos(current.getPosition()) : "-";
		String locked = alive && current.isLocked() ? "  <color=#ffaa44>locked</color>" : "";
		String postPart = (guardPostPos == null || guardPostPos.isBlank()) ? "" : "   post " + guardPostPos;
		return "<color=#ffffff>#" + saved.respawnId() + "</color>  " + label
				+ "  <color=" + color + ">" + state + "</color>" + locked
				+ "\n  " + saved.intervalSeconds() + "s"
				+ "   spawn " + fmtPos(saved.posX(), saved.posY(), saved.posZ())
				+ postPart
				+ "   now " + now;
	}

	static String fmtPos(Vector3f pos) {
		if (pos == null) {
			return "-";
		}
		return fmtPos(pos.x, pos.y, pos.z);
	}

	static String fmtPos(float x, float y, float z) {
		return String.format(Locale.US, "(%.1f, %.1f, %.1f)", x, y, z);
	}

	/** Coloured command failure (same look as {@code RespawnService} failures). */
	static void fail(Player player, String operation, String detail) {
		player.sendTextMessage("<color=#ff6666>Failed:</color> " + operation
				+ "\n  <color=#aaaaaa>" + detail + "</color>");
	}
}
