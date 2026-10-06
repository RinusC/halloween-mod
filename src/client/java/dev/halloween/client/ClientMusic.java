package dev.halloween.client;

import dev.halloween.HwSounds;
import dev.halloween.game.Music;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * Plays the match music the server picks ({@link Music}) and crossfades from one composition to the next.
 * Ambience and Being Stalked run as playlists (one part after another); Stalking and Proximity loop the
 * part for the current level. Vanilla music keeps quiet while match music is on.
 */
public final class ClientMusic {
	/** Fades, in ticks: between moods, between levels of the same music, into a kill's silence. */
	private static final int FADE = 50;
	private static final int FADE_LEVEL = 25;
	private static final int FADE_SILENCE = 15;

	private static int mood = Music.OFF;
	private static int variant;
	private static Track current;
	private static final List<Track> FADING = new ArrayList<>();
	private static final RandomSource RANDOM = RandomSource.create();
	private static int ambiencePart = RANDOM.nextInt(4);
	private static int stalkedPart = RANDOM.nextInt(3);

	private ClientMusic() {
	}

	/** One composition playing (or fading out). */
	private static final class Track extends AbstractTickableSoundInstance {
		final SoundEvent event;
		final int mood;
		float level;
		float target;
		float step;
		int age;

		Track(SoundEvent event, int mood, boolean loop, int fadeTicks) {
			super(event, SoundSource.MUSIC, RandomSource.create());
			this.event = event;
			this.mood = mood;
			this.looping = loop;
			this.delay = 0;
			this.relative = true;
			this.attenuation = SoundInstance.Attenuation.NONE;
			this.level = 0.001f;
			this.volume = this.level;
			this.fadeTo(1f, fadeTicks);
		}

		void fadeTo(float goal, int ticks) {
			this.target = goal;
			this.step = Math.max(0.005f, Math.abs(goal - this.level) / Math.max(1, ticks));
		}

		@Override
		public void tick() {
			this.age++;
			if (this.level < this.target) {
				this.level = Math.min(this.target, this.level + this.step);
			} else if (this.level > this.target) {
				this.level = Math.max(this.target, this.level - this.step);
			}
			this.volume = Math.max(0.001f, this.level);
			if (this.target <= 0 && this.level <= 0.001f) {
				this.stop();
			}
		}

		@Override
		public boolean canStartSilent() {
			return true;
		}
	}

	public static void set(int newMood, int newVariant) {
		mood = newMood;
		variant = newVariant;
	}

	public static void reset() {
		mood = Music.OFF;
		Minecraft mc = Minecraft.getInstance();
		if (current != null) {
			mc.getSoundManager().stop(current);
			current = null;
		}
		FADING.forEach(t -> mc.getSoundManager().stop(t));
		FADING.clear();
	}

	public static void tick(Minecraft mc) {
		FADING.removeIf(t -> t.isStopped() || !mc.getSoundManager().isActive(t) && t.age > 40);
		if (mood != Music.OFF) {
			// Our music only: no vanilla tune over it.
			mc.getMusicManager().stopPlaying();
		}
		// A playlist part that ran out: on to the next one.
		if (current != null && !current.isLooping() && current.age > 60 && !mc.getSoundManager().isActive(current)) {
			if (current.mood == Music.AMBIENCE) {
				ambiencePart = (ambiencePart + 1) % HwSounds.MUSIC_AMBIENCE.length;
			} else if (current.mood == Music.BEING_STALKED) {
				stalkedPart = (stalkedPart + 1) % HwSounds.MUSIC_BEING_STALKED.length;
			}
			current = null;
		}
		SoundEvent want = wanted();
		if (want != null && current != null && current.event == want) {
			return;
		}
		if (want == null && current == null) {
			return;
		}
		int fade = mood == Music.SILENT ? FADE_SILENCE : current != null && current.mood == mood ? FADE_LEVEL : FADE;
		if (current != null) {
			current.fadeTo(0f, fade);
			FADING.add(current);
			current = null;
		}
		if (want != null) {
			boolean loop = mood == Music.STALKING || mood == Music.PROXIMITY;
			current = new Track(want, mood, loop, fade);
			mc.getSoundManager().play(current);
		}
	}

	private static SoundEvent wanted() {
		return switch (mood) {
			case Music.AMBIENCE -> HwSounds.MUSIC_AMBIENCE[ambiencePart];
			case Music.BEING_STALKED -> HwSounds.MUSIC_BEING_STALKED[stalkedPart];
			case Music.PROXIMITY -> HwSounds.MUSIC_PROXIMITY[clamp(variant, HwSounds.MUSIC_PROXIMITY.length)];
			case Music.STALKING -> HwSounds.MUSIC_STALKING[clamp(variant, HwSounds.MUSIC_STALKING.length)];
			default -> null;
		};
	}

	private static int clamp(int level, int count) {
		return Math.max(0, Math.min(count - 1, level - 1));
	}

	/** What is playing (tests, debugging): the sound's id, or "none". */
	public static String playing() {
		return current == null ? "none" : current.event.location().toString();
	}
}
