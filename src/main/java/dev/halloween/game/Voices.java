package dev.halloween.game;

import dev.halloween.HwSounds;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;

/**
 * People's voices: a scream when they see him or he grabs them, a cry of pain when his knife finds them, a
 * death scream. Women and men sound different: survivors by the character they play, townsfolk by name
 * ("Mrs."), officers are men. Each call picks one of the recordings, so they vary.
 */
public final class Voices {
	public enum Kind {
		SCREAM, HURT, DEATH
	}

	private Voices() {
	}

	public static void play(LivingEntity who, Kind kind) {
		if (!(who.level() instanceof ServerLevel level)) {
			return;
		}
		boolean female = female(who);
		SoundEvent event = switch (kind) {
			case SCREAM -> female ? HwSounds.VOICE_FEMALE_SCREAM : HwSounds.VOICE_MALE_SCREAM;
			case HURT -> female ? HwSounds.VOICE_FEMALE_HURT : HwSounds.VOICE_MALE_HURT;
			case DEATH -> female ? HwSounds.VOICE_FEMALE_DEATH : HwSounds.VOICE_MALE_DEATH;
		};
		float volume = (kind == Kind.HURT ? 1.4f : 3f) * (Events.muffled(who.position()) ? 0.35f : 1f);
		float pitch = 0.95f + level.getRandom().nextFloat() * 0.1f;
		level.playSound(null, who.getX(), who.getEyeY(), who.getZ(), event, SoundSource.PLAYERS, volume, pitch);
		AiStats.add(who, "voice." + (female ? "female." : "male.") + kind.name().toLowerCase(java.util.Locale.ROOT));
	}

	/** Does this person scream with a woman's voice? */
	public static boolean female(LivingEntity who) {
		if (Police.isOfficer(who)) {
			return false;
		}
		if (who instanceof ServerPlayer player) {
			String skin = Skins.skinOf(player);
			int index = skin == null ? -1 : Skins.SURVIVORS.indexOf(skin);
			if (index >= 0) {
				return index < Skins.SLIM_COUNT;
			}
			return Math.floorMod(player.getUUID().hashCode(), 2) == 0;
		}
		String name = who.getPlainTextName();
		if (name.startsWith("Mrs") || name.startsWith("Miss") || name.startsWith("Ms")) {
			return true;
		}
		if (name.startsWith("Mr")) {
			return false;
		}
		return Math.floorMod(who.getUUID().hashCode(), 2) == 0;
	}
}
