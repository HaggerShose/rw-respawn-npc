package de.mahagst.risingworld.respawnnpc;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import de.mahagst.risingworld.respawnnpc.guard.GuardPost;
import net.risingworld.api.database.Database;

/**
 * SQLite persistence for {@code respawn_npcs} + {@code guard_posts} (one DB file per world).
 * Callers own the {@link Database} lifecycle; this class only runs SQL.
 */
public final class RespawnRepository {
	private final Database database;

	RespawnRepository(Database database) {
		this.database = database;
	}

	/**
	 * Create tables if missing, enable foreign keys, ensure columns added in later versions.
	 * Safe to call on every plugin enable.
	 */
	void createSchema() {
		database.execute("PRAGMA foreign_keys = ON");
		database.execute("PRAGMA journal_mode=DELETE");
		database.execute("""
				CREATE TABLE IF NOT EXISTS respawn_npcs (
				  respawn_id INTEGER PRIMARY KEY AUTOINCREMENT,
				  current_npc_id INTEGER NOT NULL,
				  type_id INTEGER NOT NULL,
				  variant INTEGER NOT NULL,
				  type_name TEXT NOT NULL,
				  pos_x REAL NOT NULL,
				  pos_y REAL NOT NULL,
				  pos_z REAL NOT NULL,
				  yaw REAL NOT NULL,
				  interval_seconds INTEGER NOT NULL,
				  next_respawn INTEGER,
				  created_at INTEGER NOT NULL,
				  name TEXT,
				  health INTEGER NOT NULL,
				  hunger INTEGER NOT NULL,
				  thirst INTEGER NOT NULL,
				  taming REAL NOT NULL,
				  age INTEGER NOT NULL,
				  behaviour TEXT,
				  behaviour_overridden INTEGER NOT NULL,
				  attack_reaction TEXT,
				  attack_reaction_overridden INTEGER NOT NULL,
				  group_id INTEGER NOT NULL,
				  locked INTEGER NOT NULL,
				  npc_static INTEGER NOT NULL,
				  invincible INTEGER NOT NULL,
				  invisible INTEGER NOT NULL,
				  interactable INTEGER NOT NULL,
				  collider_enabled INTEGER NOT NULL,
				  footstep_sound INTEGER NOT NULL,
				  idle_sound INTEGER NOT NULL,
				  alert_sound INTEGER NOT NULL,
				  eq_type_id INTEGER,
				  eq_variant INTEGER,
				  eq_status INTEGER,
				  eq_value REAL,
				  eq_durability INTEGER,
				  eq_modifier TEXT,
				  clothes BLOB,
				  skin_gender TEXT,
				  skin_variation INTEGER,
				  skin_color INTEGER,
				  eye_color INTEGER,
				  hair_color INTEGER,
				  hairstyle INTEGER,
				  beard INTEGER,
				  sec_type_id INTEGER,
				  sec_variant INTEGER,
				  sec_status INTEGER,
				  sec_value REAL,
				  sec_durability INTEGER,
				  sec_modifier TEXT,
				  pregnant INTEGER NOT NULL
				)
				""");
		database.execute("""
				CREATE TABLE IF NOT EXISTS guard_posts (
				  respawn_id INTEGER PRIMARY KEY,
				  pos_x REAL NOT NULL,
				  pos_y REAL NOT NULL,
				  pos_z REAL NOT NULL,
				  yaw REAL NOT NULL DEFAULT 0,
				  FOREIGN KEY (respawn_id) REFERENCES respawn_npcs(respawn_id) ON DELETE CASCADE
				)
				""");
	}

	/**
	 * Full row by primary key, including clothes BLOB.
	 * {@link FindResult#ok()} is false if SQL failed (already logged);
	 * when ok, empty {@link FindResult#row()} means the id does not exist.
	 */
	public FindResult find(long respawnId) {
		var sql = "SELECT * FROM respawn_npcs WHERE respawn_id = ?";
		try (var prep = database.getConnection().prepareStatement(sql)) {
			prep.setLong(1, respawnId);
			try (var result = prep.executeQuery()) {
				if (result.next()) {
					return FindResult.hit(readNpc(result));
				}
			}
			return FindResult.miss();
		} catch (SQLException e) {
			e.printStackTrace();
			return FindResult.fail();
		}
	}

	/**
	 * All respawn rows (startup maps + list). Expensive if many rows / large clothes blobs.
	 *
	 * @return empty if SQL failed (already logged); present list may be empty
	 */
	Optional<List<RespawnNpc>> findAll() {
		var npcs = new ArrayList<RespawnNpc>();
		var sql = "SELECT * FROM respawn_npcs ORDER BY respawn_id";
		try (var prep = database.getConnection().prepareStatement(sql);
				var result = prep.executeQuery()) {
			while (result.next()) {
				npcs.add(readNpc(result));
			}
			return Optional.of(npcs);
		} catch (SQLException e) {
			e.printStackTrace();
			return Optional.empty();
		}
	}

	/**
	 * Insert a new row; returns generated {@code respawn_id}.
	 *
	 * @return empty on SQL failure
	 */
	Optional<Long> insert(RespawnNpc npc) {
		var sql = """
				INSERT INTO respawn_npcs (
				  current_npc_id, type_id, variant, type_name,
				  pos_x, pos_y, pos_z, yaw,
				  interval_seconds, next_respawn, created_at,
				  name, health, hunger, thirst, taming, age,
				  behaviour, behaviour_overridden, attack_reaction, attack_reaction_overridden,
				  group_id, locked, npc_static, invincible, invisible, interactable, collider_enabled,
				  footstep_sound, idle_sound, alert_sound,
				  eq_type_id, eq_variant, eq_status, eq_value, eq_durability, eq_modifier,
				  clothes,
				  skin_gender, skin_variation, skin_color, eye_color, hair_color, hairstyle, beard,
				  sec_type_id, sec_variant, sec_status, sec_value, sec_durability, sec_modifier,
				  pregnant
				) VALUES (
				  ?, ?, ?, ?, ?, ?, ?, ?,
				  ?, ?, ?,
				  ?, ?, ?, ?, ?, ?,
				  ?, ?, ?, ?,
				  ?, ?, ?, ?, ?, ?, ?,
				  ?, ?, ?,
				  ?, ?, ?, ?, ?, ?,
				  ?,
				  ?, ?, ?, ?, ?, ?, ?,
				  ?, ?, ?, ?, ?, ?,
				  ?
				)
				""";
		try (var prep = database.getConnection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
			bindInsert(prep, npc);
			if (prep.executeUpdate() <= 0) {
				return Optional.empty();
			}
			try (var keys = prep.getGeneratedKeys()) {
				if (keys.next()) {
					return Optional.of(keys.getLong(1));
				}
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		return Optional.empty();
	}

	/** Snapshot attributes only; spawn pose, interval, pending, and current npc id stay. */
	boolean replaceSnapshot(RespawnNpc npc) {
		var sql = """
				UPDATE respawn_npcs SET
				  type_id = ?, variant = ?, type_name = ?,
				  name = ?, health = ?, hunger = ?, thirst = ?, taming = ?, age = ?,
				  behaviour = ?, behaviour_overridden = ?, attack_reaction = ?, attack_reaction_overridden = ?,
				  group_id = ?, locked = ?, npc_static = ?, invincible = ?, invisible = ?,
				  interactable = ?, collider_enabled = ?,
				  footstep_sound = ?, idle_sound = ?, alert_sound = ?,
				  eq_type_id = ?, eq_variant = ?, eq_status = ?, eq_value = ?, eq_durability = ?, eq_modifier = ?,
				  clothes = ?,
				  skin_gender = ?, skin_variation = ?, skin_color = ?, eye_color = ?, hair_color = ?,
				  hairstyle = ?, beard = ?,
				  sec_type_id = ?, sec_variant = ?, sec_status = ?, sec_value = ?, sec_durability = ?, sec_modifier = ?,
				  pregnant = ?
				WHERE respawn_id = ?
				""";
		try (var prep = database.getConnection().prepareStatement(sql)) {
			int i = 1;
			prep.setInt(i++, npc.typeId());
			prep.setInt(i++, npc.variant());
			prep.setString(i++, npc.typeName());
			i = bindSnapshotBody(prep, i, npc);
			prep.setLong(i, npc.respawnId());
			return prep.executeUpdate() > 0;
		} catch (SQLException e) {
			e.printStackTrace();
			return false;
		}
	}

	/** Snapshot attributes and spawn pose in one statement ({@code /respawn-update all}). */
	boolean replaceSnapshotAndPose(RespawnNpc npc) {
		var sql = """
				UPDATE respawn_npcs SET
				  type_id = ?, variant = ?, type_name = ?,
				  pos_x = ?, pos_y = ?, pos_z = ?, yaw = ?,
				  name = ?, health = ?, hunger = ?, thirst = ?, taming = ?, age = ?,
				  behaviour = ?, behaviour_overridden = ?, attack_reaction = ?, attack_reaction_overridden = ?,
				  group_id = ?, locked = ?, npc_static = ?, invincible = ?, invisible = ?,
				  interactable = ?, collider_enabled = ?,
				  footstep_sound = ?, idle_sound = ?, alert_sound = ?,
				  eq_type_id = ?, eq_variant = ?, eq_status = ?, eq_value = ?, eq_durability = ?, eq_modifier = ?,
				  clothes = ?,
				  skin_gender = ?, skin_variation = ?, skin_color = ?, eye_color = ?, hair_color = ?,
				  hairstyle = ?, beard = ?,
				  sec_type_id = ?, sec_variant = ?, sec_status = ?, sec_value = ?, sec_durability = ?, sec_modifier = ?,
				  pregnant = ?
				WHERE respawn_id = ?
				""";
		try (var prep = database.getConnection().prepareStatement(sql)) {
			int i = 1;
			prep.setInt(i++, npc.typeId());
			prep.setInt(i++, npc.variant());
			prep.setString(i++, npc.typeName());
			prep.setFloat(i++, npc.posX());
			prep.setFloat(i++, npc.posY());
			prep.setFloat(i++, npc.posZ());
			prep.setFloat(i++, npc.yaw());
			i = bindSnapshotBody(prep, i, npc);
			prep.setLong(i, npc.respawnId());
			return prep.executeUpdate() > 0;
		} catch (SQLException e) {
			e.printStackTrace();
			return false;
		}
	}

	/** Update spawn pose columns only (admin position/yaw). */
	boolean setSpawnPose(long respawnId, float posX, float posY, float posZ, float yaw) {
		var sql = """
				UPDATE respawn_npcs SET
				  pos_x = ?, pos_y = ?, pos_z = ?, yaw = ?
				WHERE respawn_id = ?
				""";
		try (var prep = database.getConnection().prepareStatement(sql)) {
			prep.setFloat(1, posX);
			prep.setFloat(2, posY);
			prep.setFloat(3, posZ);
			prep.setFloat(4, yaw);
			prep.setLong(5, respawnId);
			return prep.executeUpdate() > 0;
		} catch (SQLException e) {
			e.printStackTrace();
			return false;
		}
	}

	/**
	 * Set or clear pending respawn due time.
	 *
	 * @param nextRespawn world-time ms when due ({@link net.risingworld.api.Server#getIngameTimestamp}),
	 *                    or null for idle (no timer pending)
	 * @return false on SQL failure or if no row was updated
	 */
	boolean setNextRespawn(long respawnId, Long nextRespawn) {
		var sql = "UPDATE respawn_npcs SET next_respawn = ? WHERE respawn_id = ?";
		try (var prep = database.getConnection().prepareStatement(sql)) {
			setNullableLong(prep, 1, nextRespawn);
			prep.setLong(2, respawnId);
			return prep.executeUpdate() > 0;
		} catch (SQLException e) {
			e.printStackTrace();
			return false;
		}
	}

	/** Persist death-to-respawn delay in seconds. */
	boolean setIntervalSeconds(long respawnId, int intervalSeconds) {
		var sql = "UPDATE respawn_npcs SET interval_seconds = ? WHERE respawn_id = ?";
		try (var prep = database.getConnection().prepareStatement(sql)) {
			prep.setInt(1, intervalSeconds);
			prep.setLong(2, respawnId);
			return prep.executeUpdate() > 0;
		} catch (SQLException e) {
			e.printStackTrace();
			return false;
		}
	}

	/**
	 * After a successful spawn: bind the new body and clear pending due time in one statement.
	 *
	 * @return false on SQL failure or if no row was updated
	 */
	boolean completeRespawn(long respawnId, long npcId) {
		var sql = """
				UPDATE respawn_npcs
				SET current_npc_id = ?, next_respawn = NULL
				WHERE respawn_id = ?
				""";
		try (var prep = database.getConnection().prepareStatement(sql)) {
			prep.setLong(1, npcId);
			prep.setLong(2, respawnId);
			return prep.executeUpdate() > 0;
		} catch (SQLException e) {
			e.printStackTrace();
			return false;
		}
	}

	/**
	 * Delete respawn row; {@code guard_posts} cascade via FK.
	 *
	 * @return false on SQL failure (0 rows still counts as success: already gone)
	 */
	boolean delete(long respawnId) {
		var sql = "DELETE FROM respawn_npcs WHERE respawn_id = ?";
		try (var prep = database.getConnection().prepareStatement(sql)) {
			prep.setLong(1, respawnId);
			prep.executeUpdate();
			return true;
		} catch (SQLException e) {
			e.printStackTrace();
			return false;
		}
	}

	/**
	 * All guard posts for enable-time RAM load.
	 *
	 * @return empty if SQL failed (already logged); present list may be empty
	 */
	public Optional<List<GuardPost>> findAllGuardPosts() {
		var list = new ArrayList<GuardPost>();
		var sql = """
				SELECT respawn_id, pos_x, pos_y, pos_z, yaw
				FROM guard_posts
				""";
		try (var prep = database.getConnection().prepareStatement(sql);
				var result = prep.executeQuery()) {
			while (result.next()) {
				list.add(readGuardPost(result));
			}
			return Optional.of(list);
		} catch (SQLException e) {
			e.printStackTrace();
			return Optional.empty();
		}
	}

	/**
	 * Insert or replace the guard post for a respawn id (UPSERT).
	 *
	 * @return false on SQL failure
	 */
	public boolean setGuardPost(long respawnId, float x, float y, float z, float yaw) {
		var sql = """
				INSERT INTO guard_posts (respawn_id, pos_x, pos_y, pos_z, yaw)
				VALUES (?, ?, ?, ?, ?)
				ON CONFLICT(respawn_id) DO UPDATE SET
				  pos_x = excluded.pos_x,
				  pos_y = excluded.pos_y,
				  pos_z = excluded.pos_z,
				  yaw = excluded.yaw
				""";
		try (var prep = database.getConnection().prepareStatement(sql)) {
			prep.setLong(1, respawnId);
			prep.setFloat(2, x);
			prep.setFloat(3, y);
			prep.setFloat(4, z);
			prep.setFloat(5, yaw);
			return prep.executeUpdate() > 0;
		} catch (SQLException e) {
			e.printStackTrace();
			return false;
		}
	}

	/** Remove guard post row; respawn NPC row stays. */
	public boolean clearGuardPost(long respawnId) {
		var sql = "DELETE FROM guard_posts WHERE respawn_id = ?";
		try (var prep = database.getConnection().prepareStatement(sql)) {
			prep.setLong(1, respawnId);
			return prep.executeUpdate() > 0;
		} catch (SQLException e) {
			e.printStackTrace();
			return false;
		}
	}

	private static GuardPost readGuardPost(ResultSet result) throws SQLException {
		return new GuardPost(
				result.getLong("respawn_id"),
				result.getFloat("pos_x"),
				result.getFloat("pos_y"),
				result.getFloat("pos_z"),
				result.getFloat("yaw"));
	}

	private static void bindInsert(PreparedStatement prep, RespawnNpc npc) throws SQLException {
		int i = 1;
		prep.setLong(i++, npc.currentNpcId());
		prep.setInt(i++, npc.typeId());
		prep.setInt(i++, npc.variant());
		prep.setString(i++, npc.typeName());
		prep.setFloat(i++, npc.posX());
		prep.setFloat(i++, npc.posY());
		prep.setFloat(i++, npc.posZ());
		prep.setFloat(i++, npc.yaw());
		prep.setInt(i++, npc.intervalSeconds());
		setNullableLong(prep, i++, npc.nextRespawn());
		prep.setLong(i++, npc.createdAt());
		bindSnapshotBody(prep, i, npc);
	}

	private static int bindSnapshotBody(PreparedStatement prep, int i, RespawnNpc npc) throws SQLException {
		prep.setString(i++, npc.name());
		prep.setInt(i++, npc.health());
		prep.setInt(i++, npc.hunger());
		prep.setInt(i++, npc.thirst());
		prep.setFloat(i++, npc.taming());
		prep.setInt(i++, npc.age());
		prep.setString(i++, npc.behaviour());
		setBool(prep, i++, npc.behaviourOverridden());
		prep.setString(i++, npc.attackReaction());
		setBool(prep, i++, npc.attackReactionOverridden());
		prep.setInt(i++, npc.groupId());
		setBool(prep, i++, npc.locked());
		setBool(prep, i++, npc.npcStatic());
		setBool(prep, i++, npc.invincible());
		setBool(prep, i++, npc.invisible());
		setBool(prep, i++, npc.interactable());
		setBool(prep, i++, npc.colliderEnabled());
		setBool(prep, i++, npc.footstepSoundEnabled());
		setBool(prep, i++, npc.idleSoundEnabled());
		setBool(prep, i++, npc.alertSoundEnabled());
		setNullableShort(prep, i++, npc.equippedTypeId());
		setNullableInt(prep, i++, npc.equippedVariant());
		setNullableInt(prep, i++, npc.equippedStatus());
		setNullableFloat(prep, i++, npc.equippedValue());
		setNullableInt(prep, i++, npc.equippedDurability());
		prep.setString(i++, npc.equippedModifier());
		setBytes(prep, i++, npc.clothes());
		prep.setString(i++, npc.skinGender());
		setNullableByte(prep, i++, npc.skinVariation());
		setNullableInt(prep, i++, npc.skinColor());
		setNullableInt(prep, i++, npc.eyeColor());
		setNullableInt(prep, i++, npc.hairColor());
		setNullableByte(prep, i++, npc.hairstyle());
		setNullableByte(prep, i++, npc.beard());
		setNullableShort(prep, i++, npc.secondaryTypeId());
		setNullableInt(prep, i++, npc.secondaryVariant());
		setNullableInt(prep, i++, npc.secondaryStatus());
		setNullableFloat(prep, i++, npc.secondaryValue());
		setNullableInt(prep, i++, npc.secondaryDurability());
		prep.setString(i++, npc.secondaryModifier());
		setBool(prep, i++, npc.pregnant());
		return i;
	}

	private static RespawnNpc readNpc(ResultSet result) throws SQLException {
		long nextRespawn = result.getLong("next_respawn");
		Long next = result.wasNull() ? null : nextRespawn;
		return new RespawnNpc(
				result.getLong("respawn_id"),
				result.getLong("current_npc_id"),
				(short) result.getInt("type_id"),
				result.getInt("variant"),
				result.getString("type_name"),
				result.getFloat("pos_x"),
				result.getFloat("pos_y"),
				result.getFloat("pos_z"),
				result.getFloat("yaw"),
				result.getInt("interval_seconds"),
				next,
				result.getLong("created_at"),
				result.getString("name"),
				result.getInt("health"),
				result.getInt("hunger"),
				result.getInt("thirst"),
				result.getFloat("taming"),
				result.getInt("age"),
				result.getString("behaviour"),
				getBool(result, "behaviour_overridden"),
				result.getString("attack_reaction"),
				getBool(result, "attack_reaction_overridden"),
				result.getInt("group_id"),
				getBool(result, "locked"),
				getBool(result, "npc_static"),
				getBool(result, "invincible"),
				getBool(result, "invisible"),
				getBool(result, "interactable"),
				getBool(result, "collider_enabled"),
				getBool(result, "footstep_sound"),
				getBool(result, "idle_sound"),
				getBool(result, "alert_sound"),
				getNullableShort(result, "eq_type_id"),
				getNullableInt(result, "eq_variant"),
				getNullableInt(result, "eq_status"),
				getNullableFloat(result, "eq_value"),
				getNullableInt(result, "eq_durability"),
				result.getString("eq_modifier"),
				result.getBytes("clothes"),
				result.getString("skin_gender"),
				getNullableByte(result, "skin_variation"),
				getNullableInt(result, "skin_color"),
				getNullableInt(result, "eye_color"),
				getNullableInt(result, "hair_color"),
				getNullableByte(result, "hairstyle"),
				getNullableByte(result, "beard"),
				getNullableShort(result, "sec_type_id"),
				getNullableInt(result, "sec_variant"),
				getNullableInt(result, "sec_status"),
				getNullableFloat(result, "sec_value"),
				getNullableInt(result, "sec_durability"),
				result.getString("sec_modifier"),
				getBool(result, "pregnant"));
	}

	private static void setBool(PreparedStatement prep, int index, boolean value) throws SQLException {
		prep.setInt(index, value ? 1 : 0);
	}

	private static boolean getBool(ResultSet result, String column) throws SQLException {
		return result.getInt(column) != 0;
	}

	private static void setNullableLong(PreparedStatement prep, int index, Long value) throws SQLException {
		if (value == null) {
			prep.setNull(index, Types.INTEGER);
		} else {
			prep.setLong(index, value);
		}
	}

	private static void setNullableInt(PreparedStatement prep, int index, Integer value) throws SQLException {
		if (value == null) {
			prep.setNull(index, Types.INTEGER);
		} else {
			prep.setInt(index, value);
		}
	}

	private static void setNullableShort(PreparedStatement prep, int index, Short value) throws SQLException {
		if (value == null) {
			prep.setNull(index, Types.INTEGER);
		} else {
			prep.setInt(index, value);
		}
	}

	private static void setNullableByte(PreparedStatement prep, int index, Byte value) throws SQLException {
		if (value == null) {
			prep.setNull(index, Types.INTEGER);
		} else {
			prep.setInt(index, value);
		}
	}

	private static void setNullableFloat(PreparedStatement prep, int index, Float value) throws SQLException {
		if (value == null) {
			prep.setNull(index, Types.FLOAT);
		} else {
			prep.setFloat(index, value);
		}
	}

	private static void setBytes(PreparedStatement prep, int index, byte[] value) throws SQLException {
		if (value == null) {
			prep.setNull(index, Types.BLOB);
		} else {
			prep.setBytes(index, value);
		}
	}

	private static Integer getNullableInt(ResultSet result, String column) throws SQLException {
		int value = result.getInt(column);
		return result.wasNull() ? null : value;
	}

	private static Short getNullableShort(ResultSet result, String column) throws SQLException {
		int value = result.getInt(column);
		return result.wasNull() ? null : (short) value;
	}

	private static Byte getNullableByte(ResultSet result, String column) throws SQLException {
		int value = result.getInt(column);
		return result.wasNull() ? null : (byte) value;
	}

	private static Float getNullableFloat(ResultSet result, String column) throws SQLException {
		float value = result.getFloat(column);
		return result.wasNull() ? null : value;
	}

	/**
	 * Single-row load. {@code ok} is false if SQL failed (already logged).
	 * When ok, empty {@code row} means the id does not exist.
	 */
	public record FindResult(boolean ok, Optional<RespawnNpc> row) {
		static FindResult fail() {
			return new FindResult(false, Optional.empty());
		}

		static FindResult miss() {
			return new FindResult(true, Optional.empty());
		}

		static FindResult hit(RespawnNpc npc) {
			return new FindResult(true, Optional.of(npc));
		}
	}
}
