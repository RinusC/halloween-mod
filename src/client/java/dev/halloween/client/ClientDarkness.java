package dev.halloween.client;

import net.minecraft.client.renderer.state.LightmapRenderState;
import org.joml.Vector3f;

/**
 * A match night is really dark, with no shaders needed: the lightmap (a 16x16 table, redrawn anyway) gets
 * almost no ambient light and a dim sky, so out in the street only silhouettes show a few blocks off; lamps
 * and flashlights light warm patches with an edge (the steep curve is in our lightmap.fsh, switched on by a
 * negative night vision factor). The brightness setting does nothing during a match, so nobody turns the
 * night into day; outside matches the game looks as usual.
 */
public final class ClientDarkness {
	/** How much of the night sky's light is left. */
	public static float skyScale = 0.3f;
	private static final Vector3f AMBIENT = new Vector3f(0.010f, 0.010f, 0.016f);
	private static final Vector3f RED_AMBIENT = new Vector3f(0.075f, 0.008f, 0.01f);
	/** For tests and comparisons: switch it off. */
	public static boolean enabled = true;

	private ClientDarkness() {
	}

	public static boolean active() {
		return enabled && ClientState.match.state() != 0;
	}

	public static void apply(LightmapRenderState state) {
		if (!state.needsUpdate || !active() || state.nightVisionEffectIntensity > 0f) {
			return;
		}
		state.skyFactor *= skyScale;
		// The cursed one's lights failing as he shows (CurseVisions).
		state.blockFactor *= CurseVisions.lights();
		float red = ClientEvents.redness();
		// Samhain night (see ClientEvents): what little light there is turns red.
		state.ambientColor = red <= 0.001f ? AMBIENT : new Vector3f(AMBIENT).lerp(RED_AMBIENT, red);
		state.brightness = 0f;
		// The flag for lightmap.fsh: the match night's curve.
		state.nightVisionEffectIntensity = -1f;
	}
}
