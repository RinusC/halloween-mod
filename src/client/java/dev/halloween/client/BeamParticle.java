package dev.halloween.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;

/** The flashlight beam: a still, self-lit, see-through puff that lives a few ticks. */
public final class BeamParticle extends SingleQuadParticle {
	private static final float ALPHA = 0.32f;

	private BeamParticle(ClientLevel level, double x, double y, double z, float size, SpriteSet sprites) {
		super(level, x, y, z, sprites.first());
		this.quadSize = size;
		this.lifetime = 3;
		this.gravity = 0;
		this.hasPhysics = false;
		this.xd = this.yd = this.zd = 0;
		this.setColor(1f, 0.95f, 0.8f);
		this.setAlpha(ALPHA);
	}

	@Override
	public SingleQuadParticle.Layer getLayer() {
		return SingleQuadParticle.Layer.TRANSLUCENT;
	}

	@Override
	public int getLightCoords(float partial) {
		return LightCoordsUtil.FULL_BRIGHT;
	}

	@Override
	public void tick() {
		super.tick();
		this.setAlpha(ALPHA * (1f - (float) this.age / this.lifetime * 0.6f));
	}

	public record Provider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
		@Override
		public Particle createParticle(SimpleParticleType options, ClientLevel level, double x, double y, double z,
				double size, double yAux, double zAux, RandomSource random) {
			return new BeamParticle(level, x, y, z, (float) Math.max(0.05, size), this.sprites);
		}
	}
}
