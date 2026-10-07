package dev.halloween.client;

import dev.halloween.HwSounds;
import dev.halloween.client.mixin.ChannelAccessor;
import dev.halloween.client.mixin.SoundEngineAccessor;
import dev.halloween.client.mixin.SoundManagerAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.EXTEfx;

/**
 * How Shape Jump sounds to the Shape: a deep boom going in, a rush of air coming out, a low drone all along,
 * a muffled wind that rises with speed, and the world around heard through a low-pass filter (OpenAL EFX).
 * Music, the interface and these sounds themselves stay clear.
 */
public final class ShapeJumpAudio {
	/** How muffled the world is at full effect: overall gain and gain of the highs. */
	private static final float MUFFLE_GAIN = 0.75f;
	private static final float MUFFLE_GAIN_HF = 0.12f;

	private static boolean wasShadow;
	private static float muffle;
	/** OpenAL sources that carry the filter now: a source outlives its sound and gets reused, so it must be cleared. */
	private static final java.util.Set<Integer> FILTERED = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private static int filter = -1;
	private static volatile boolean efxBroken;
	private static LoopSound drone;
	private static LoopSound wind;
	private static float speed;

	private ShapeJumpAudio() {
	}

	public static void tick(Minecraft mc) {
		boolean shadow = ClientState.shadowActive();
		if (shadow && !wasShadow) {
			mc.getSoundManager().play(SimpleSoundInstance.forUI(HwSounds.SHADOW_ENTER, 1f, 1f));
		} else if (!shadow && wasShadow) {
			mc.getSoundManager().play(SimpleSoundInstance.forUI(HwSounds.SHADOW_EXIT, 1f, 0.8f));
		}
		wasShadow = shadow;
		if (mc.player != null) {
			double dx = mc.player.getX() - mc.player.xo;
			double dz = mc.player.getZ() - mc.player.zo;
			speed += ((float) Math.sqrt(dx * dx + dz * dz) - speed) * 0.25f;
		}
		if (shadow && (drone == null || !mc.getSoundManager().isActive(drone))) {
			drone = new LoopSound(HwSounds.SHADOW_DRONE, SoundSource.PLAYERS, 0.55f, () -> ClientState.shadowActive() ? 1 : 0);
			mc.getSoundManager().play(drone);
		}
		if (shadow && (wind == null || !mc.getSoundManager().isActive(wind))) {
			// Standing still it barely whispers; the faster the shadow moves, the louder the wind.
			wind = new LoopSound(HwSounds.SHADOW_WIND, SoundSource.PLAYERS, 0.8f,
				() -> ClientState.shadowActive() ? Math.max(0.15, Math.min(1, (speed - 0.08) / 0.22)) : 0);
			mc.getSoundManager().play(wind);
		}
		muffle += ((shadow ? 1f : 0f) - muffle) * 0.2f;
		if (muffle < 0.01f) {
			muffle = 0;
		}
		applyMuffle(mc);
	}

	/** Sounds the filter leaves alone: music, the interface, and Shape Jump's own. */
	private static boolean clear(SoundInstance sound) {
		if (sound.getSource() == SoundSource.MUSIC || sound.getSource() == SoundSource.UI) {
			return true;
		}
		Identifier id = sound.getIdentifier();
		return id.getNamespace().equals("halloween") && id.getPath().startsWith("shape.shadow_");
	}

	/**
	 * Sets the low-pass on every playing world sound (new ones get it on the next tick), and takes it off any
	 * source that still carries it once the sound there is one that should be clear.
	 */
	private static void applyMuffle(Minecraft mc) {
		if (efxBroken || muffle == 0 && FILTERED.isEmpty()) {
			return;
		}
		SoundEngineAccessor engine = (SoundEngineAccessor) ((SoundManagerAccessor) mc.getSoundManager()).halloween$soundEngine();
		if (!engine.halloween$loaded()) {
			return;
		}
		boolean on = muffle > 0;
		float gain = 1 - (1 - MUFFLE_GAIN) * muffle;
		float gainHf = 1 - (1 - MUFFLE_GAIN_HF) * muffle;
		List<Map.Entry<SoundInstance, ChannelAccess.ChannelHandle>> playing = new ArrayList<>(engine.halloween$channels().entrySet());
		for (Map.Entry<SoundInstance, ChannelAccess.ChannelHandle> e : playing) {
			boolean clear = clear(e.getKey());
			e.getValue().execute(channel -> {
				try {
					int source = ((ChannelAccessor) channel).halloween$source();
					if (on && !clear) {
						if (filter < 0) {
							filter = EXTEfx.alGenFilters();
							EXTEfx.alFilteri(filter, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
						}
						EXTEfx.alFilterf(filter, EXTEfx.AL_LOWPASS_GAIN, gain);
						EXTEfx.alFilterf(filter, EXTEfx.AL_LOWPASS_GAINHF, gainHf);
						AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, filter);
						FILTERED.add(source);
					} else if (FILTERED.remove(source)) {
						AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, EXTEfx.AL_FILTER_NULL);
					}
				} catch (Throwable t) {
					// No EFX on this sound device: Shape Jump just isn't muffled.
					efxBroken = true;
				}
			});
		}
	}

	/** For tests: how muffled the world is now (0..1), or -1 if the sound device has no filters. */
	public static float muffle() {
		return efxBroken ? -1 : muffle;
	}

	/** For tests: the filter is made and set up (the device supports it). */
	public static boolean filterReady() {
		return filter >= 0 && !efxBroken;
	}
}
