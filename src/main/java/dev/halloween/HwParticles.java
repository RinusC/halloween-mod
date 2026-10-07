package dev.halloween;

import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;

public final class HwParticles {
	/** A soft glowing puff of the flashlight beam. Sent with count 0: the x offset carries its size. */
	public static final SimpleParticleType BEAM = Registry.register(BuiltInRegistries.PARTICLE_TYPE, Halloween.id("beam"), FabricParticleTypes.simple(true));

	/** Blood (see game.Blood): a flying drop; splats it leaves where it lands; a pool under a body (x = size); a drip behind the wounded (x = size, y = seconds). */
	public static final SimpleParticleType BLOOD = Registry.register(BuiltInRegistries.PARTICLE_TYPE, Halloween.id("blood"), FabricParticleTypes.simple(true));
	public static final SimpleParticleType BLOOD_SPLAT = Registry.register(BuiltInRegistries.PARTICLE_TYPE, Halloween.id("blood_splat"), FabricParticleTypes.simple(true));
	public static final SimpleParticleType BLOOD_POOL = Registry.register(BuiltInRegistries.PARTICLE_TYPE, Halloween.id("blood_pool"), FabricParticleTypes.simple(true));
	public static final SimpleParticleType BLOOD_DRIP = Registry.register(BuiltInRegistries.PARTICLE_TYPE, Halloween.id("blood_drip"), FabricParticleTypes.simple(true));

	private HwParticles() {
	}

	public static void init() {
	}
}
