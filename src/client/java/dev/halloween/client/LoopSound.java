package dev.halloween.client;

import java.util.function.DoubleSupplier;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * A non-positional looping sound whose volume follows a supplier and fades smoothly.
 * Stops itself once the target stays at zero and the fade is done.
 */
public class LoopSound extends AbstractTickableSoundInstance {
	private final DoubleSupplier target;
	private final float maxVolume;

	public LoopSound(SoundEvent event, SoundSource source, float maxVolume, DoubleSupplier target) {
		super(event, source, RandomSource.create());
		this.target = target;
		this.maxVolume = maxVolume;
		this.looping = true;
		this.delay = 0;
		this.relative = true;
		this.attenuation = SoundInstance.Attenuation.NONE;
		this.volume = 0.01f;
	}

	@Override
	public void tick() {
		float goal = (float) Math.max(0, Math.min(1, this.target.getAsDouble())) * this.maxVolume;
		this.volume += (goal - this.volume) * 0.15f;
		if (goal <= 0 && this.volume < 0.02f) {
			this.stop();
		}
	}

	@Override
	public boolean canStartSilent() {
		return true;
	}
}
