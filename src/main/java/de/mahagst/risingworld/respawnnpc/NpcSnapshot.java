package de.mahagst.risingworld.respawnnpc;

import net.risingworld.api.definitions.Items;
import net.risingworld.api.definitions.Npcs;
import net.risingworld.api.objects.Clothes;
import net.risingworld.api.objects.Item;
import net.risingworld.api.objects.Npc;
import net.risingworld.api.objects.Skin;
import net.risingworld.api.utils.Vector3f;

/**
 * Capture every readable NPC field. Apply only fields that have setters.
 * Secondary item and pregnancy are stored, not restored.
 */
final class NpcSnapshot {
	private NpcSnapshot() {
	}

	/**
	 * @param spawnPos spawn pose position (typically the admin's position)
	 * @param yaw horizontal facing in degrees (typically the admin's yaw)
	 */
	static RespawnNpc capture(
			Npc npc,
			Vector3f spawnPos,
			float yaw,
			long respawnId,
			int intervalSeconds,
			Long nextRespawn,
			long createdAt) {
		Vector3f pos = spawnPos != null ? spawnPos : npc.getPosition();
		if (pos == null) {
			pos = new Vector3f(0f, 0f, 0f);
		}
		Npcs.NpcDefinition def = npc.getDefinition();
		String typeName = Short.toString(npc.getTypeID());
		if (def != null && def.name != null && !def.name.isBlank()) {
			typeName = def.name;
		}
		Npcs.Behaviour behaviour = npc.getBehaviour();
		Npcs.AttackReaction attackReaction = npc.getAttackReaction();
		Item equipped = npc.getEquippedItem();
		Item secondary = npc.getSecondaryItem();
		byte[] clothesBytes = null;
		Clothes clothes = npc.getClothes();
		if (clothes != null) {
			clothesBytes = clothes.serialize();
		}
		String skinGender = null;
		Byte skinVariation = null;
		Integer skinColor = null;
		Integer eyeColor = null;
		Integer hairColor = null;
		Byte hairstyle = null;
		Byte beard = null;
		Skin skin = npc.getSkin();
		if (skin != null) {
			Skin.Gender gender = skin.getGender();
			if (gender != null) {
				skinGender = gender.name();
			}
			skinVariation = skin.getVariation();
			skinColor = skin.getSkinColor();
			eyeColor = skin.getEyeColor();
			hairColor = skin.getHairColor();
			hairstyle = skin.getHairstyle();
			beard = skin.getBeard();
		}
		return new RespawnNpc(
				respawnId,
				npc.getGlobalID(),
				npc.getTypeID(),
				npc.getVariant(),
				typeName,
				pos.x,
				pos.y,
				pos.z,
				yaw,
				intervalSeconds,
				nextRespawn,
				createdAt,
				npc.getName(),
				npc.getHealth(),
				npc.getHunger(),
				npc.getThirst(),
				npc.getTaming(),
				npc.getAge(),
				behaviour == null ? null : behaviour.name(),
				npc.isBehaviourOverridden(),
				attackReaction == null ? null : attackReaction.name(),
				npc.isAttackReactionOverridden(),
				npc.getGroupID(),
				npc.isLocked(),
				npc.isStatic(),
				npc.isInvincible(),
				npc.isInvisible(),
				npc.isInteractable(),
				npc.isColliderEnabled(),
				npc.isFootstepSoundEnabled(),
				npc.isIdleSoundEnabled(),
				npc.isAlertSoundEnabled(),
				itemTypeId(equipped),
				itemVariant(equipped),
				itemStatus(equipped),
				itemValue(equipped),
				itemDurability(equipped),
				itemModifier(equipped),
				clothesBytes,
				skinGender,
				skinVariation,
				skinColor,
				eyeColor,
				hairColor,
				hairstyle,
				beard,
				itemTypeId(secondary),
				itemVariant(secondary),
				itemStatus(secondary),
				itemValue(secondary),
				itemDurability(secondary),
				itemModifier(secondary),
				npc.isPregnant());
	}

	static void apply(Npc npc, RespawnNpc saved) {
		npc.setName(saved.name());
		npc.setHealth(saved.health());
		npc.setHunger(saved.hunger());
		npc.setThirst(saved.thirst());
		npc.setTaming(saved.taming());
		npc.setAge(saved.age());
		npc.setGroupID(saved.groupId());
		if (saved.behaviourOverridden()) {
			Npcs.Behaviour behaviour = parseEnum(Npcs.Behaviour.class, saved.behaviour());
			if (behaviour != null) {
				npc.setBehaviour(behaviour);
			}
		} else {
			npc.resetBehaviour();
		}
		if (saved.attackReactionOverridden()) {
			Npcs.AttackReaction reaction = parseEnum(Npcs.AttackReaction.class, saved.attackReaction());
			if (reaction != null) {
				npc.setAttackReaction(reaction);
			}
		} else {
			npc.resetAttackReaction();
		}
		Clothes clothes = npc.getClothes();
		if (clothes != null && saved.clothes() != null) {
			clothes.deserialize(saved.clothes());
		}
		Skin skin = npc.getSkin();
		if (skin != null && saved.skinGender() != null) {
			Skin.Gender gender = parseEnum(Skin.Gender.class, saved.skinGender());
			if (gender != null) {
				skin.setGender(gender);
			}
			if (saved.skinVariation() != null) {
				skin.setVariation(saved.skinVariation());
			}
			if (saved.skinColor() != null) {
				skin.setSkinColor(saved.skinColor());
			}
			if (saved.eyeColor() != null) {
				skin.setEyeColor(saved.eyeColor());
			}
			if (saved.hairColor() != null) {
				skin.setHairColor(saved.hairColor());
			}
			if (saved.hairstyle() != null) {
				skin.setHairstyle(saved.hairstyle());
			}
			if (saved.beard() != null) {
				skin.setBeard(saved.beard());
			}
		}
		if (saved.equippedTypeId() != null) {
			Items.Modifier modifier = parseEnum(Items.Modifier.class, saved.equippedModifier());
			if (modifier == null) {
				modifier = Items.Modifier.Normal;
			}
			int variant = saved.equippedVariant() == null ? 0 : saved.equippedVariant();
			int status = saved.equippedStatus() == null ? 0 : saved.equippedStatus();
			float value = saved.equippedValue() == null ? 0f : saved.equippedValue();
			int durability = saved.equippedDurability() == null ? -1 : saved.equippedDurability();
			npc.setEquippedItem(saved.equippedTypeId(), variant, status, value, durability, modifier);
		}
		npc.setSoundsEnabled(
				saved.footstepSoundEnabled(),
				saved.idleSoundEnabled(),
				saved.alertSoundEnabled());
		npc.setInteractable(saved.interactable());
		npc.setColliderEnabled(saved.colliderEnabled());
		npc.setInvisible(saved.invisible());
		npc.setInvincible(saved.invincible());
		npc.setLocked(saved.locked());
		npc.setStatic(saved.npcStatic());
	}

	private static Short itemTypeId(Item item) {
		return item == null ? null : item.getTypeID();
	}

	private static Integer itemVariant(Item item) {
		return item == null ? null : item.getVariant();
	}

	private static Integer itemStatus(Item item) {
		return item == null ? null : (int) item.getStatus();
	}

	private static Float itemValue(Item item) {
		return item == null ? null : item.getValue();
	}

	private static Integer itemDurability(Item item) {
		return item == null ? null : item.getDurability();
	}

	private static String itemModifier(Item item) {
		if (item == null) {
			return null;
		}
		Items.Modifier modifier = item.getModifier();
		return modifier == null ? null : modifier.name();
	}

	private static <T extends Enum<T>> T parseEnum(Class<T> type, String name) {
		if (name == null || name.isBlank()) {
			return null;
		}
		try {
			return Enum.valueOf(type, name);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
