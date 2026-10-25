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
	/** Getting away from him: the chase music cut off, not faded. */
	private static final int FADE_CUT = 3;

	private static int mood = Music.OFF;
	private static int variant;
	private static Track current;
	/** The grandfather clock over the music while the rift is open. */
	private static Track clock;
	private static final float CLOCK_GAIN = 0.3f;
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
		float gain = 1f;
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
			this.volume = Math.max(0.001f, this.level * this.gain);
			if (this.target <= 0 && this.level <= 0.001f) {
				this.stop();
			}
		}

		@Override
		public boolean canStartSilent() {
			return true;
		}
	}

	/** For tests: the composition playing now, or null. */
	public static SoundInstance track() {
		return current;
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
		if (clock != null) {
			mc.getSoundManager().stop(clock);
			clock = null;
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
		// A playlist part that ran out: on to the next one. A loop that stopped (its stream broke): start it again.
		if (current != null && current.age > 60 && !mc.getSoundManager().isActive(current)) {
			if (current.isLooping()) {
				// Same part again, below.
			} else if (current.mood == Music.AMBIENCE) {
				ambiencePart = (ambiencePart + 1) % HwSounds.MUSIC_AMBIENCE.length;
			} else if (current.mood == Music.BEING_STALKED) {
				stalkedPart = (stalkedPart + 1) % HwSounds.MUSIC_BEING_STALKED.length;
			}
			current = null;
		}
		// The rift open: the clock ticks on, quietly, under whatever else plays (not through a kill's silence).
		boolean clockWanted = VecnaClient.riftOpen() && mood > Music.SILENT;
		if (clock != null && (clock.isStopped() || !clockWanted && clock.target > 0)) {
			if (!clock.isStopped()) {
				clock.fadeTo(0f, FADE);
				FADING.add(clock);
			}
			clock = null;
		}
		if (clock == null && clockWanted) {
			clock = new Track(HwSounds.MUSIC_VECNA_CLOCK, mood, true, FADE);
			clock.gain = CLOCK_GAIN;
			mc.getSoundManager().play(clock);
		}
		SoundEvent want = wanted();
		if (want != null && current != null && current.event == want) {
			return;
		}
		if (want == null && current == null) {
			return;
		}
		int fade = mood == Music.SILENT ? (variant == 1 ? FADE_CUT : FADE_SILENCE) : current != null && current.mood == mood || mood == Music.VECNA_CHASE ? FADE_LEVEL : FADE;
		if (current != null) {
			current.fadeTo(0f, fade);
			FADING.add(current);
			current = null;
		}
		if (want != null) {
			boolean loop = mood == Music.STALKING || mood == Music.PROXIMITY || mood >= Music.VECNA_SURVIVOR;
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
			case Music.VECNA_SURVIVOR -> HwSounds.MUSIC_VECNA_SURVIVOR;
			case Music.VECNA_CALM -> HwSounds.MUSIC_VECNA_CALM;
			case Music.VECNA_CHASE -> HwSounds.MUSIC_VECNA_CHASE[clamp(variant, HwSounds.MUSIC_VECNA_CHASE.length)];
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
