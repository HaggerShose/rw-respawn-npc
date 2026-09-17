package de.mahagst.risingworld.respawnnpc;

/**
 * Immutable row for one registered respawn NPC (DB + snapshot apply).
 * <p>
 * {@code nextRespawn == null} means idle (no pending death timer).
 * Spawn pose ({@code posX/Y/Z}, {@code yaw}) is set at register / {@code /respawn-update pose};
 * snapshot attribute fields change only on register / snapshot update -- never on death.
 * {@code yaw} is degrees, horizontal facing only.
 *
 * @param respawnId           primary key (identity)
 * @param currentNpcId       living body global id (rebound after each spawn)
 * @param typeId              NPC type for {@code World.spawnNpc}
 * @param variant             type variant
 * @param typeName            display/type label
 * @param posX                spawn pose X (admin pose, not death location)
 * @param posY                spawn pose Y
 * @param posZ                spawn pose Z
 * @param yaw                 spawn facing degrees
 * @param intervalSeconds     delay after death until respawn
 * @param nextRespawn         epoch ms when due, or null if idle
 * @param createdAt           row creation epoch ms
 * @param name                optional custom name
 * @param health              snapshot health
 * @param hunger              snapshot hunger
 * @param thirst              snapshot thirst
 * @param taming              snapshot taming
 * @param age                 snapshot age
 * @param behaviour           behaviour enum name if overridden
 * @param behaviourOverridden whether to {@code setBehaviour} vs reset
 * @param attackReaction      attack reaction enum name if overridden
 * @param attackReactionOverridden whether to set vs reset
 * @param groupId             group id
 * @param locked              locked flag at capture (guard may change at runtime)
 * @param npcStatic           static flag
 * @param invincible          invincible flag
 * @param invisible           invisible flag
 * @param interactable        interactable flag
 * @param colliderEnabled     collider flag
 * @param footstepSoundEnabled sound toggles
 * @param idleSoundEnabled    sound toggles
 * @param alertSoundEnabled   sound toggles
 * @param equippedTypeId      primary hand item (nullable fields)
 * @param equippedVariant     primary hand
 * @param equippedStatus      primary hand
 * @param equippedValue       primary hand
 * @param equippedDurability  primary hand
 * @param equippedModifier    primary hand
 * @param clothes             serialized clothes blob
 * @param skinGender          human skin (nullable for animals)
 * @param skinVariation       skin
 * @param skinColor           skin
 * @param eyeColor            skin
 * @param hairColor           skin
 * @param hairstyle           skin
 * @param beard               skin
 * @param secondaryTypeId     secondary item capture-only (no apply setter yet)
 * @param secondaryVariant    secondary capture-only
 * @param secondaryStatus     secondary capture-only
 * @param secondaryValue      secondary capture-only
 * @param secondaryDurability secondary capture-only
 * @param secondaryModifier   secondary capture-only
 * @param pregnant            capture-only
 */
record RespawnNpc(
		long respawnId,
		long currentNpcId,
		short typeId,
		int variant,
		String typeName,
		float posX,
		float posY,
		float posZ,
		float yaw,
		int intervalSeconds,
		Long nextRespawn,
		long createdAt,
		String name,
		int health,
		int hunger,
		int thirst,
		float taming,
		int age,
		String behaviour,
		boolean behaviourOverridden,
		String attackReaction,
		boolean attackReactionOverridden,
		int groupId,
		boolean locked,
		boolean npcStatic,
		boolean invincible,
		boolean invisible,
		boolean interactable,
		boolean colliderEnabled,
		boolean footstepSoundEnabled,
		boolean idleSoundEnabled,
		boolean alertSoundEnabled,
		Short equippedTypeId,
		Integer equippedVariant,
		Integer equippedStatus,
		Float equippedValue,
		Integer equippedDurability,
		String equippedModifier,
		byte[] clothes,
		String skinGender,
		Byte skinVariation,
		Integer skinColor,
		Integer eyeColor,
		Integer hairColor,
		Byte hairstyle,
		Byte beard,
		Short secondaryTypeId,
		Integer secondaryVariant,
		Integer secondaryStatus,
		Float secondaryValue,
		Integer secondaryDurability,
		String secondaryModifier,
		boolean pregnant) {
}
