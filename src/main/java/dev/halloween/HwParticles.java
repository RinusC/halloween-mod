package dev.halloween;

import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;

public final class HwParticles {
	/** A soft glowing puff of the flashlight beam. Sent with count 0: the x offset carries its size. */
	public static final SimpleParticleType BEAM = Registry.register(BuiltInRegistries.PARTICLE_TYPE, Halloween.id("beam"), FabricParticleTypes.simple(true));

	private HwParticles() {
	}

	public static void init() {
	}
}
