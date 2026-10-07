package dev.halloween.client;

import dev.halloween.game.Weather;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.util.Mth;

/** The match's weather as the server says it; fog thickens and thins smoothly. */
public final class ClientWeather {
	/** How near the fog closes in at full thickness (blocks): it starts here and nothing shows past the end. */
	private static final float FOG_START = 8f;
	private static final float FOG_END = 31f;
	public static int kind = Weather.CLEAR;
	private static float thickness;

	private ClientWeather() {
	}

	public static void tick() {
		float target = kind == Weather.FOG ? 1f : 0f;
		thickness += Mth.clamp(target - thickness, -0.02f, 0.02f);
	}

	public static void apply(FogData data) {
		if (thickness <= 0.001f) {
			return;
		}
		data.environmentalStart = Mth.lerp(thickness, data.environmentalStart, Math.min(data.environmentalStart, FOG_START));
		data.environmentalEnd = Mth.lerp(thickness, data.environmentalEnd, Math.min(data.environmentalEnd, FOG_END));
		data.renderDistanceStart = Mth.lerp(thickness, data.renderDistanceStart, Math.min(data.renderDistanceStart, FOG_START));
		data.renderDistanceEnd = Mth.lerp(thickness, data.renderDistanceEnd, Math.min(data.renderDistanceEnd, FOG_END));
		data.skyEnd = Mth.lerp(thickness, data.skyEnd, Math.min(data.skyEnd, FOG_END));
		data.cloudEnd = Mth.lerp(thickness, data.cloudEnd, Math.min(data.cloudEnd, FOG_END));
	}

	/** For tests: how thick the fog is now (0..1). */
	public static float thickness() {
		return thickness;
	}
}
