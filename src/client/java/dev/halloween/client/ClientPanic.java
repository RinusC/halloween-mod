package dev.halloween.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;

/**
 * This survivor's panic (game.Panic) as they feel it: a heartbeat that beats faster and louder the worse it
 * gets, and the view shaking (see CameraMixin). The heavy breathing comes from the server, like after a run.
 */
public final class ClientPanic {
	private static long nextBeat;

	private ClientPanic() {
	}

	/** Panic felt now (0 when not a survivor in a match). */
	public static float level() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.player.isSpectator() || !ClientState.myRole().survivor()) {
			return 0f;
		}
		return ClientState.panic;
	}

	public static void tick(Minecraft mc) {
		float panic = level();
		if (panic < 0.15f || mc.level == null) {
			return;
		}
		long now = mc.level.getGameTime();
		if (now >= nextBeat) {
			nextBeat = now + Math.round(Mth.lerp(panic, 26f, 11f));
			mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.WARDEN_HEARTBEAT, 1f, 0.25f + 0.75f * panic));
		}
	}

	/** The view's shake (degrees of yaw, pitch) at this moment. */
	public static float[] shake(float time) {
		float panic = level();
		if (panic < 0.05f) {
			return null;
		}
		float amp = panic * panic * 0.8f;
		float yaw = amp * (Mth.sin(time * 0.83f) * 0.6f + Mth.sin(time * 2.17f) * 0.4f);
		float pitch = amp * 0.7f * (Mth.sin(time * 1.13f + 1.3f) * 0.6f + Mth.sin(time * 2.71f) * 0.4f);
		return new float[]{yaw, pitch};
	}
}
