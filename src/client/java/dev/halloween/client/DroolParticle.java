package dev.halloween.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.RandomSource;

/**
 * Slaver from the Demogorgon's mouth (game.GrabSession sends it while he eats someone): a pale, glassy drop that
 * gathers, stretches and slowly falls, gone where it lands.
 */
public final class DroolParticle extends SingleQuadParticle {
	private final float size;

	private DroolParticle(ClientLevel level, double x, double y, double z, SpriteSet sprites) {
		super(level, x, y, z, sprites.first());
		this.xd = 0;
		this.yd = 0;
		this.zd = 0;
		this.size = 0.07f + this.random.nextFloat() * 0.05f;
		this.quadSize = this.size * 0.4f;
		this.gravity = 0.3f;
		this.friction = 0.9f;
		this.hasPhysics = true;
		this.lifetime = 34 + this.random.nextInt(14);
		this.setSize(0.01f, 0.01f);
		this.setAlpha(0.85f);
	}

	@Override
	public SingleQuadParticle.Layer getLayer() {
		return SingleQuadParticle.Layer.TRANSLUCENT;
	}

	@Override
	public void tick() {
		// Gathering at the lip first (growing, hardly moving), then letting go.
		if (this.age < 8) {
			this.quadSize = this.size * (0.4f + 0.6f * this.age / 8f);
			this.yd = 0;
		}
		super.tick();
		if (this.onGround) {
			this.remove();
		}
	}

	public record Provider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
		@Override
		public Particle createParticle(SimpleParticleType options, ClientLevel level, double x, double y, double z, double xd, double yd, double zd,
				RandomSource random) {
			return new DroolParticle(level, x, y, z, this.sprites);
		}
	}
}
