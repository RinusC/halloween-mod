package dev.halloween.client;

import dev.halloween.net.HwNet;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * A kill seen from the side for a few seconds (Michael smashing someone's head against a wardrobe): the camera
 * off to one side of them (game.GrabSession picks where), easing in a little, letterboxed; then back to the player's
 * own camera.
 */
public final class KillCam {
	private static Vec3 from;
	private static Vec3 target;
	private static long start;
	private static int ticks;

	private KillCam() {
	}

	public static void begin(HwNet.Cutscene payload) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		from = payload.a();
		target = payload.b();
		ticks = payload.ticks();
		start = mc.level.getGameTime();
	}

	public static void clear() {
		from = null;
	}

	/** Share of the shot played (0..1), or -1 when there is none. */
	private static float progress(float partial) {
		Minecraft mc = Minecraft.getInstance();
		if (from == null || mc.level == null) {
			return -1;
		}
		float f = (mc.level.getGameTime() - start + partial) / ticks;
		if (f >= 1f || f < -0.05f) {
			from = null;
			return -1;
		}
		return Math.max(0f, f);
	}

	public static boolean active() {
		return progress(0f) >= 0;
	}

	/** The camera for this frame: position, yaw, pitch; null to leave it be. */
	public static double[] camera(float partial) {
		float f = progress(partial);
		if (f < 0) {
			return null;
		}
		// Easing in towards them as it goes on, a slight drift sideways.
		float k = f * f * (3 - 2 * f);
		Vec3 pos = from.lerp(target, 0.18 * k).add(0, -0.15 * k, 0);
		Vec3 d = target.subtract(pos);
		float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
		return new double[]{pos.x, pos.y, pos.z, yaw, pitch};
	}

	/** Bars top and bottom, sliding in and out; true while the shot plays (the HUD stays hidden). */
	public static boolean overlay(GuiGraphicsExtractor g, DeltaTracker delta) {
		float f = progress(delta.getGameTimeDeltaPartialTick(false));
		if (f < 0) {
			return false;
		}
		float in = Mth.clamp(Math.min(f / 0.08f, (1f - f) / 0.08f), 0f, 1f);
		int bar = Math.round(g.guiHeight() * 0.11f * in);
		g.fill(0, 0, g.guiWidth(), bar, 0xFF000000);
		g.fill(0, g.guiHeight() - bar, g.guiWidth(), g.guiHeight(), 0xFF000000);
		return true;
	}
}
