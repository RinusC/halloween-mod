package dev.halloween.client;

import dev.halloween.game.Weather;
import net.minecraft.util.Mth;

/** The match's weather as the server says it; fog thickens and thins smoothly (drawn by the halloween_atmos mod). */
public final class ClientWeather {
	public static int kind = Weather.CLEAR;
	private static float thickness;

	private ClientWeather() {
	}

	public static void tick() {
		float target = kind == Weather.FOG ? 1f : 0f;
		thickness += Mth.clamp(target - thickness, -0.02f, 0.02f);
	}

	/** How thick the fog is now (0..1). */
	public static float thickness() {
		return thickness;
	}
}
