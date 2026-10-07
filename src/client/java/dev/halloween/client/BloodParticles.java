package dev.halloween.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.RandomSource;
import org.joml.Quaternionf;

/**
 * Blood. Drops fly from a wound and fall; where one lands it leaves a splat on the floor or the wall. The
 * server adds pools that spread under the dead and drips behind the wounded. Splats and pools stay until the
 * match ends, drips dry up after a while.
 */
public final class BloodParticles {
	/** Splat sprites, for the stains drops leave (set when the sprites load). */
	private static SpriteSet splats;
	private static final List<Stain> STAINS = new ArrayList<>();
	private static int lastState;
	/** For tests: how many drops have flown and stains have been laid since the start. */
	public static int dropsSeen;
	public static int stainsLaid;

	private BloodParticles() {
	}

	/** A new match or its end: the blood is washed away. */
	public static void tick(Minecraft mc) {
		int state = ClientState.match.state();
		if ((state == 0) != (lastState == 0)) {
			clear();
		}
		lastState = state;
	}

	public static void clear() {
		for (Stain s : STAINS) {
			s.remove();
		}
		STAINS.clear();
	}

	public static int stains() {
		STAINS.removeIf(s -> !s.isAlive());
		return STAINS.size();
	}

	/** A drop of blood in the air: falls, and leaves a splat where it lands. */
	public static final class Drop extends SingleQuadParticle {
		private Drop(ClientLevel level, double x, double y, double z, double xd, double yd, double zd, SpriteSet sprites) {
			super(level, x, y, z, sprites.first());
			this.xd = xd;
			this.yd = yd + 0.05;
			this.zd = zd;
			this.quadSize = 0.035f + this.random.nextFloat() * 0.035f;
			this.gravity = 0.9f;
			this.friction = 0.98f;
			this.hasPhysics = true;
			this.lifetime = 60;
			this.setSize(0.02f, 0.02f);
			dropsSeen++;
		}

		@Override
		public SingleQuadParticle.Layer getLayer() {
			return SingleQuadParticle.Layer.TRANSLUCENT;
		}

		@Override
		public void tick() {
			double vx = this.xd;
			double vz = this.zd;
			super.tick();
			if (!this.isAlive()) {
				return;
			}
			Direction face = null;
			if (this.onGround) {
				face = Direction.UP;
			} else if (vx != 0 && this.xd == 0) {
				face = vx > 0 ? Direction.WEST : Direction.EAST;
			} else if (vz != 0 && this.zd == 0) {
				face = vz > 0 ? Direction.NORTH : Direction.SOUTH;
			}
			if (face != null) {
				if (splats != null && this.random.nextFloat() < 0.7f) {
					float size = 0.08f + this.random.nextFloat() * 0.16f;
					add(new Stain(this.level, this.x, this.y, this.z, face, size, splats.get(this.random), 0, -1));
				}
				this.remove();
			}
		}
	}

	/** A stain lying flat on a surface: it can spread out (pools), dries darker, and may dry up for good. */
	public static final class Stain extends SingleQuadParticle {
		private final Quaternionf rotation;
		private final float size;
		private final int growTicks;
		private final int fadeAt;

		/** {@code seconds} < 0: stays until the match ends. */
		Stain(ClientLevel level, double x, double y, double z, Direction face, float size, net.minecraft.client.renderer.texture.TextureAtlasSprite sprite,
				int growTicks, int seconds) {
			super(level, x + face.getStepX() * 0.012, y + face.getStepY() * 0.012, z + face.getStepZ() * 0.012, sprite);
			this.size = size;
			this.growTicks = growTicks;
			this.quadSize = growTicks > 0 ? size * 0.2f : size;
			this.gravity = 0;
			this.hasPhysics = false;
			this.xd = this.yd = this.zd = 0;
			this.lifetime = seconds < 0 ? Integer.MAX_VALUE / 2 : seconds * 20;
			this.fadeAt = seconds < 0 ? Integer.MAX_VALUE : Math.max(0, this.lifetime - 100);
			float roll = this.random.nextFloat() * (float) Math.PI * 2;
			this.rotation = switch (face) {
				case UP -> new Quaternionf().rotateY(roll).rotateX((float) -Math.PI / 2);
				case DOWN -> new Quaternionf().rotateY(roll).rotateX((float) Math.PI / 2);
				case EAST -> new Quaternionf().rotateY((float) Math.PI / 2).rotateZ(roll);
				case WEST -> new Quaternionf().rotateY((float) -Math.PI / 2).rotateZ(roll);
				case SOUTH -> new Quaternionf().rotateZ(roll);
				case NORTH -> new Quaternionf().rotateY((float) Math.PI).rotateZ(roll);
			};
		}

		@Override
		public SingleQuadParticle.Layer getLayer() {
			return SingleQuadParticle.Layer.TRANSLUCENT;
		}

		@Override
		public SingleQuadParticle.FacingCameraMode getFacingCameraMode() {
			return (q, camera, partial) -> q.set(this.rotation);
		}

		@Override
		public void tick() {
			this.age++;
			if (this.age >= this.lifetime) {
				this.remove();
				return;
			}
			if (this.age < this.growTicks) {
				float t = (float) this.age / this.growTicks;
				this.quadSize = this.size * (0.2f + 0.8f * (1 - (1 - t) * (1 - t)));
			}
			// Fresh blood darkens as it dries (over two minutes); drips fade out before they go.
			float dry = Math.min(1f, this.age / 2400f);
			float c = 1f - 0.3f * dry;
			this.setColor(c, c, c);
			if (this.age > this.fadeAt) {
				this.setAlpha(Math.max(0, 1f - (this.age - this.fadeAt) / 100f));
			}
		}
	}

	private static void add(Stain stain) {
		Minecraft.getInstance().particleEngine.add(stain);
		STAINS.add(stain);
		stainsLaid++;
	}

	public record DropProvider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
		@Override
		public Particle createParticle(SimpleParticleType options, ClientLevel level, double x, double y, double z, double xd, double yd, double zd,
				RandomSource random) {
			return new Drop(level, x, y, z, xd, yd, zd, this.sprites);
		}
	}

	/** Splats are laid by drops; this only hands their sprites over. */
	public record SplatProvider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
		public SplatProvider {
			splats = sprites;
		}

		@Override
		public Particle createParticle(SimpleParticleType options, ClientLevel level, double x, double y, double z, double size, double yd, double zd,
				RandomSource random) {
			Stain stain = new Stain(level, x, y, z, Direction.UP, (float) Math.max(0.05, size), this.sprites.get(random), 0, -1);
			STAINS.add(stain);
			stainsLaid++;
			return stain;
		}
	}

	/** A pool under a body: spreads over five seconds to {@code size}, stays. */
	public record PoolProvider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
		@Override
		public Particle createParticle(SimpleParticleType options, ClientLevel level, double x, double y, double z, double size, double yd, double zd,
				RandomSource random) {
			Stain stain = new Stain(level, x, y, z, Direction.UP, (float) Math.max(0.2, size), this.sprites.get(random), 100, -1);
			STAINS.add(stain);
			stainsLaid++;
			return stain;
		}
	}

	/** A drip behind the wounded: {@code size}, and how many seconds before it dries up. */
	public record DripProvider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
		@Override
		public Particle createParticle(SimpleParticleType options, ClientLevel level, double x, double y, double z, double size, double seconds, double zd,
				RandomSource random) {
			Stain stain = new Stain(level, x, y, z, Direction.UP, (float) Math.max(0.04, size), this.sprites.get(random), 0, (int) Math.max(5, seconds));
			STAINS.add(stain);
			stainsLaid++;
			return stain;
		}
	}
}
