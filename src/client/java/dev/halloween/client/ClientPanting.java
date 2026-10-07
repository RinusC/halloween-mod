package dev.halloween.client;

import dev.halloween.HwSounds;
import dev.halloween.net.HwNet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;

/**
 * Someone out of breath (game.Stamina): one long take of panting that follows them (a man's or a woman's),
 * no short loop. When they catch their breath it fades out over a couple of seconds (in the same voice: nothing
 * else follows). Should the panting outlast the take, it starts over a little quieter and pitched a touch apart.
 */
public final class ClientPanting {
	/** Take lengths (ticks at pitch 1): a man's, a woman's panting. */
	private static final int MALE_TICKS = 121;
	private static final int FEMALE_TICKS = 334;
	private static final int FADE_TICKS = 50;
	private static final Map<Integer, Breath> PANTING = new HashMap<>();
	private static final RandomSource RANDOM = RandomSource.create();

	private ClientPanting() {
	}

	public static void receive(HwNet.Pant pant) {
		Minecraft mc = Minecraft.getInstance();
		Breath playing = PANTING.get(pant.entityId());
		if (pant.on()) {
			if (playing == null || playing.fading()) {
				start(mc, pant.entityId(), pant.female(), pant.volume(), 1f);
			}
			return;
		}
		if (playing != null) {
			PANTING.remove(pant.entityId());
			playing.fadeOut();
		}
	}

	private static void start(Minecraft mc, int entityId, boolean female, float volume, float pitchScale) {
		float pitch = (female ? 1.0f : 0.98f) * pitchScale;
		Breath b = new Breath(female ? HwSounds.VOICE_PANT_FEMALE : HwSounds.VOICE_PANT_MALE, entityId, female, volume, pitch,
			female ? FEMALE_TICKS : MALE_TICKS);
		PANTING.put(entityId, b);
		mc.getSoundManager().play(b);
	}

	public static void tick(Minecraft mc) {
		Iterator<Map.Entry<Integer, Breath>> it = PANTING.entrySet().iterator();
		java.util.List<Breath> again = new java.util.ArrayList<>();
		while (it.hasNext()) {
			Breath b = it.next().getValue();
			if (b.isStopped()) {
				it.remove();
			} else if (b.overAt()) {
				// Still out of breath after the whole take: once more, quieter and a little different.
				it.remove();
				again.add(b);
			}
		}
		for (Breath b : again) {
			start(mc, b.entityId, b.female, b.base * 0.75f, 0.96f + RANDOM.nextFloat() * 0.08f);
		}
	}

	public static void clear() {
		Minecraft mc = Minecraft.getInstance();
		PANTING.values().forEach(b -> mc.getSoundManager().stop(b));
		PANTING.clear();
	}

	/** For tests: is this entity heard panting now? */
	public static boolean panting(int entityId) {
		Breath b = PANTING.get(entityId);
		return b != null && !b.isStopped() && !b.fading();
	}

	private static final class Breath extends AbstractTickableSoundInstance {
		final int entityId;
		final boolean female;
		final float base;
		final int length;
		int age;
		int fade = -1;

		Breath(SoundEvent sound, int entityId, boolean female, float volume, float pitch, int length) {
			super(sound, SoundSource.VOICE, RandomSource.create());
			this.entityId = entityId;
			this.female = female;
			this.base = volume;
			this.volume = volume;
			this.pitch = pitch;
			this.length = (int) (length / pitch);
			this.looping = false;
			this.delay = 0;
			this.attenuation = SoundInstance.Attenuation.LINEAR;
			this.follow();
		}

		boolean fading() {
			return this.fade >= 0;
		}

		void fadeOut() {
			if (this.fade < 0) {
				this.fade = 0;
			}
		}

		/** The take has played through (and isn't fading out). */
		boolean overAt() {
			return this.fade < 0 && this.age >= this.length;
		}

		private void follow() {
			Entity who = Minecraft.getInstance().level == null ? null : Minecraft.getInstance().level.getEntity(this.entityId);
			if (who != null) {
				this.x = who.getX();
				this.y = who.getEyeY();
				this.z = who.getZ();
			}
		}

		@Override
		public void tick() {
			Entity who = Minecraft.getInstance().level == null ? null : Minecraft.getInstance().level.getEntity(this.entityId);
			if (who == null || who.isRemoved()) {
				this.stop();
				return;
			}
			this.follow();
			this.age++;
			if (this.fade >= 0) {
				this.fade++;
				float t = this.fade / (float) FADE_TICKS;
				this.volume = this.base * Math.max(0f, 1f - t * t);
				if (this.fade >= FADE_TICKS) {
					this.stop();
				}
			} else if (this.age > this.length + 5) {
				this.stop();
			}
		}
	}
}
