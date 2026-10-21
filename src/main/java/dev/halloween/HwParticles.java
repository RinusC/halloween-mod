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
	/** Slaver running from the Demogorgon's mouth (over the one he is eating). */
	public static final SimpleParticleType DROOL = Registry.register(BuiltInRegistries.PARTICLE_TYPE, Halloween.id("drool"), FabricParticleTypes.simple(true));

	/** Vecna (client.VecnaParticles): soft smoke, a red haze, embers, ash flakes, Upside Down spores, sparks that keep their speed, the Grip's thread. */
	public static final SimpleParticleType VECNA_SMOKE = vecna("smoke");
	public static final SimpleParticleType VECNA_MIST = vecna("mist");
	public static final SimpleParticleType VECNA_EMBER = vecna("ember");
	public static final SimpleParticleType VECNA_ASH = vecna("ash");
	public static final SimpleParticleType VECNA_SPORE = vecna("spore");
	public static final SimpleParticleType VECNA_SPARK = vecna("spark");
	public static final SimpleParticleType VECNA_THREAD = vecna("thread");

	private HwParticles() {
	}

	private static SimpleParticleType vecna(String name) {
		return Registry.register(BuiltInRegistries.PARTICLE_TYPE, Halloween.id("vecna_" + name), FabricParticleTypes.simple(true));
	}

	public static void init() {
	}
}
