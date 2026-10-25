package dev.halloween.client;

import dev.halloween.game.Music;
import dev.halloween.game.Weather;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.util.Mth;

/**
 * The match's weather as the server says it, and the fog of a match night (drawn by halloween:atmos.glsl, switched
 * on by the fog colour's alpha): a cold blue-grey haze over the town, never a solid wall (silhouettes and lamps show
 * through), thicker in a foggy night, warmer and redder in a chase, a red murk in the Upside Down and under
 * Vecna's rift. Everything it goes by drifts towards where it should be over a second or so, so no change jumps;
 * only the lightning's flashes come at once.
 */
public final class ClientWeather {
	/** How thick the fog gets (the shader's limit): what is left of the world shows through it. */
	private static final float LIMIT = 0.9f;
	/** Where nothing shows any more (blocks). The dense part is near its limit at about 60% of this. */
	private static final float CLEAR_END = 96f;
	private static final float RAIN_END = 74f;
	private static final float FOG_END = 40f;
	/** The haze's colour on a clear night, in a foggy one (thicker fog catches more of what light there is) and in a chase. */
	private static final float NIGHT_R = 0.1f, NIGHT_G = 0.115f, NIGHT_B = 0.145f;
	private static final float FOG_R = 0.14f, FOG_G = 0.155f, FOG_B = 0.19f;
	private static final float CHASE_R = 0.16f, CHASE_G = 0.095f, CHASE_B = 0.09f;
	/** Seconds for a change of fog to get most of the way there. */
	private static final float EASE = 0.9f;
	public static int kind = Weather.CLEAR;
	/** For tests and comparisons: vanilla's fog in a match. */
	public static boolean enabled = true;
	private static float thickness;
	private static float chase;
	private static boolean eased;
	private static long lastNanos;
	private static float start, end, limit, red, green, blue;

	private ClientWeather() {
	}

	public static void tick() {
		float target = kind == Weather.FOG ? 1f : 0f;
		thickness += Mth.clamp(target - thickness, -0.02f, 0.02f);
		int mood = ClientMusic.mood();
		float chasing = mood == Music.PROXIMITY || mood == Music.VECNA_CHASE ? 1f : 0f;
		chase += Mth.clamp(chasing - chase, -0.02f, 0.04f);
	}

	public static void apply(FogData data) {
		if (!enabled || ClientState.match.state() == 0) {
			eased = false;
			return;
		}
		float wantStart;
		float wantEnd;
		float wantLimit = LIMIT;
		float r;
		float g;
		float b;
		if (ClientUpsideDown.active()) {
			// The Upside Down: a thick red murk.
			r = 0.16f;
			g = 0.015f;
			b = 0.02f;
			wantStart = 0f;
			wantEnd = 30f;
			wantLimit = 0.94f;
		} else if (VecnaClient.riftOpen()) {
			// Vecna's rift: the whole town in a red murk (not as thick as the Demogorgon's below).
			r = 0.17f;
			g = 0.025f;
			b = 0.03f;
			wantStart = 2f;
			wantEnd = 80f;
		} else {
			r = Mth.lerp(chase, Mth.lerp(thickness, NIGHT_R, FOG_R), CHASE_R);
			g = Mth.lerp(chase, Mth.lerp(thickness, NIGHT_G, FOG_G), CHASE_G);
			b = Mth.lerp(chase, Mth.lerp(thickness, NIGHT_B, FOG_B), CHASE_B);
			float redness = ClientEvents.redness() * 0.85f;
			// Samhain night: the haze over town goes blood red.
			r = Mth.lerp(redness, r, 0.30f);
			g = Mth.lerp(redness, g, 0.02f);
			b = Mth.lerp(redness, b, 0.03f);
			wantStart = 0f;
			wantEnd = Mth.lerp(thickness, kind == Weather.RAIN ? RAIN_END : CLEAR_END, FOG_END) * (1f - 0.1f * chase);
		}
		long now = System.nanoTime();
		float k = eased ? 1f - (float) Math.exp(-Math.min(0.25, (now - lastNanos) * 1e-9) / EASE) : 1f;
		lastNanos = now;
		eased = true;
		start = Mth.lerp(k, start, wantStart);
		end = Mth.lerp(k, end, wantEnd);
		limit = Mth.lerp(k, limit, wantLimit);
		red = Mth.lerp(k, red, r);
		green = Mth.lerp(k, green, g);
		blue = Mth.lerp(k, blue, b);
		// The lightning lights the murk from inside, at once.
		float flash = ClientUpsideDown.active() ? ClientUpsideDown.flash(0f) : VecnaClient.riftOpen() ? VecnaClient.riftFlash(0f) : 0f;
		data.color.set(red + 0.4f * flash, green + 0.05f * flash, blue + 0.04f * flash, limit);
		data.environmentalStart = start;
		data.environmentalEnd = end + 16f * flash;
		data.skyEnd = data.environmentalEnd;
		data.cloudEnd = Math.min(data.cloudEnd, data.environmentalEnd * 1.5f);
	}

	/** How much of the stars and the moon shows through the haze overhead (1 outside matches): none in a thick fog or a murk. */
	public static float skyClarity() {
		if (!enabled || !eased) {
			return 1f;
		}
		float clear = Mth.clamp((end - 35f) / 70f, 0f, 1f);
		return ClientUpsideDown.active() || VecnaClient.riftOpen() ? clear * 0.3f : clear;
	}

	/** For tests: how thick the fog is now (0..1). */
	public static float thickness() {
		return thickness;
	}
}
