package dev.halloween.client;

import dev.halloween.HwParticles;
import net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * Vecna's particles, soft and see-through instead of the game's square ones (textures from tools/vecna_particles.pl).
 * Each keeps the speed it was sent with and adds its own drift.
 */
public final class VecnaParticles extends SingleQuadParticle {
	private enum Kind {
		/** Dark smoke billowing out and up, near black to deep crimson. */
		SMOKE,
		/** A wide red haze, faint and lit by itself. */
		MIST,
		/** A glowing ember rising and flickering. */
		EMBER,
		/** A flake of ash turning as it drifts down. */
		ASH,
		/** A pale Upside Down spore floating slowly up. */
		SPORE,
		/** A red spark that keeps its speed and shrinks. */
		SPARK,
		/** A bead of the Grip's thread: lit, still, gone in a moment. */
		THREAD
	}

	private final Kind kind;
	private final float size;
	private final float peak;
	private final float spin;
	private final float phase;

	private VecnaParticles(ClientLevel level, double x, double y, double z, double xd, double yd, double zd, Kind kind, SpriteSet sprites) {
		super(level, x, y, z, sprites.get(level.getRandom()));
		this.kind = kind;
		RandomSource r = this.random;
		this.xd = xd;
		this.yd = yd;
		this.zd = zd;
		this.hasPhysics = false;
		this.gravity = 0;
		this.friction = 0.96f;
		this.phase = r.nextFloat() * 6.28f;
		this.roll = this.oRoll = r.nextFloat() * 6.28f;
		float s;
		float a;
		float sp = 0;
		switch (kind) {
			case SMOKE -> {
				s = 0.35f + r.nextFloat() * 0.35f;
				a = 0.55f;
				this.lifetime = 40 + r.nextInt(40);
				float red = 0.05f + r.nextFloat() * 0.18f;
				this.setColor(red, 0.02f + red * 0.12f, 0.03f + red * 0.1f);
				this.yd += 0.008;
				sp = (r.nextFloat() - 0.5f) * 0.05f;
			}
			case MIST -> {
				s = 1.2f + r.nextFloat() * 1.3f;
				a = 0.16f;
				this.lifetime = 40 + r.nextInt(40);
				this.setColor(0.62f, 0.05f, 0.07f);
				sp = (r.nextFloat() - 0.5f) * 0.02f;
			}
			case EMBER -> {
				s = 0.05f + r.nextFloat() * 0.05f;
				a = 1f;
				this.lifetime = 30 + r.nextInt(40);
				this.setColor(1f, 0.35f + r.nextFloat() * 0.3f, 0.12f);
				this.yd += 0.02 + r.nextFloat() * 0.03;
				this.friction = 0.98f;
			}
			case ASH -> {
				s = 0.04f + r.nextFloat() * 0.05f;
				a = 0.9f;
				this.lifetime = 80 + r.nextInt(80);
				float g = 0.12f + r.nextFloat() * 0.25f;
				this.setColor(g, g * 0.95f, g * 0.95f);
				this.gravity = 0.004f;
				sp = (r.nextFloat() - 0.5f) * 0.25f;
				this.friction = 0.98f;
			}
			case SPORE -> {
				s = 0.03f + r.nextFloat() * 0.04f;
				a = 0.75f;
				this.lifetime = 100 + r.nextInt(120);
				this.setColor(0.92f, 0.86f, 0.86f);
				this.yd += 0.004;
				this.friction = 0.99f;
			}
			case SPARK -> {
				s = 0.06f + r.nextFloat() * 0.05f;
				a = 1f;
				this.lifetime = 10 + r.nextInt(12);
				this.setColor(1f, 0.16f + r.nextFloat() * 0.18f, 0.1f);
				this.friction = 0.92f;
			}
			default -> {
				s = 0.07f;
				a = 0.85f;
				this.lifetime = 3;
				this.setColor(1f, 0.12f, 0.12f);
				this.xd = this.yd = this.zd = 0;
			}
		}
		this.size = s;
		this.quadSize = s;
		this.peak = a;
		this.spin = sp;
		this.setAlpha(kind == Kind.SMOKE || kind == Kind.MIST || kind == Kind.SPORE ? 0 : a);
	}

	@Override
	protected SingleQuadParticle.Layer getLayer() {
		return SingleQuadParticle.Layer.TRANSLUCENT;
	}

	@Override
	public int getLightCoords(float partial) {
		return switch (this.kind) {
			case MIST, EMBER, SPARK, THREAD -> LightCoordsUtil.FULL_BRIGHT;
			default -> super.getLightCoords(partial);
		};
	}

	@Override
	public void tick() {
		this.oRoll = this.roll;
		super.tick();
		if (this.removed) {
			return;
		}
		float t = (float) this.age / this.lifetime;
		this.roll += this.spin;
		switch (this.kind) {
			case SMOKE -> {
				// Swells and thins out as it rises.
				this.quadSize = this.size * (1 + t * 1.6f);
				this.setAlpha(this.peak * Math.min(1, t * 6) * (1 - t) * (1 - t));
			}
			case MIST -> this.setAlpha(this.peak * Mth.sin(t * Mth.PI));
			case EMBER -> {
				this.xd += Mth.sin(this.age * 0.3f + this.phase) * 0.002;
				float flick = 0.75f + 0.25f * Mth.sin(this.age * 1.7f + this.phase);
				this.setAlpha(this.peak * flick * (1 - t * t));
				this.gCol = Math.max(0.1f, this.gCol - 0.006f);
			}
			case ASH -> {
				this.xd += Mth.sin(this.age * 0.12f + this.phase) * 0.0015;
				this.zd += Mth.cos(this.age * 0.1f + this.phase) * 0.0015;
				this.setAlpha(this.peak * Math.min(1, (1 - t) * 4));
			}
			case SPORE -> {
				this.xd += Mth.sin(this.age * 0.05f + this.phase) * 0.0006;
				this.setAlpha(this.peak * Mth.sin(t * Mth.PI));
			}
			case SPARK -> {
				this.quadSize = this.size * (1 - t * 0.7f);
				this.setAlpha(this.peak * (1 - t * t));
			}
			default -> this.setAlpha(this.peak * (1 - t * 0.5f));
		}
	}

	private record Provider(SpriteSet sprites, Kind kind) implements ParticleProvider<SimpleParticleType> {
		@Override
		public Particle createParticle(SimpleParticleType options, ClientLevel level, double x, double y, double z,
				double xd, double yd, double zd, RandomSource random) {
			return new VecnaParticles(level, x, y, z, xd, yd, zd, this.kind, this.sprites);
		}
	}

	public static void register() {
		ParticleProviderRegistry reg = ParticleProviderRegistry.getInstance();
		reg.register(HwParticles.VECNA_SMOKE, s -> new Provider(s, Kind.SMOKE));
		reg.register(HwParticles.VECNA_MIST, s -> new Provider(s, Kind.MIST));
		reg.register(HwParticles.VECNA_EMBER, s -> new Provider(s, Kind.EMBER));
		reg.register(HwParticles.VECNA_ASH, s -> new Provider(s, Kind.ASH));
		reg.register(HwParticles.VECNA_SPORE, s -> new Provider(s, Kind.SPORE));
		reg.register(HwParticles.VECNA_SPARK, s -> new Provider(s, Kind.SPARK));
		reg.register(HwParticles.VECNA_THREAD, s -> new Provider(s, Kind.THREAD));
	}
}
