package dev.halloween.client;

import dev.halloween.HwSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;

/**
 * Michael breathes all match long, slow and heavy behind the mask. He hears it himself, very quietly; anyone
 * else only right next to him: it comes from him and dies out within two blocks (sounds.json).
 */
public final class ShapeBreath {
	/** Volume for Michael himself, and at his side for anyone else (falls to nothing at two blocks). */
	private static final float OWN_VOLUME = 0.16f;
	private static final float NEAR_VOLUME = 0.3f;
	private static Breath playing;

	private ShapeBreath() {
	}

	public static void tick(Minecraft mc) {
		Entity shape = mc.level == null || ClientState.match.state() == 0 || ClientState.shapeEntityId < 0 ? null
			: mc.level.getEntity(ClientState.shapeEntityId);
		boolean own = shape != null && shape == mc.player;
		if (playing != null && (playing.isStopped() || shape == null || playing.entityId != shape.getId() || playing.own != own)) {
			mc.getSoundManager().stop(playing);
			playing = null;
		}
		if (shape != null && playing == null) {
			playing = new Breath(shape.getId(), own);
			mc.getSoundManager().play(playing);
		}
	}

	/** For tests: whose breathing plays now (-1 none), and whether as his own. */
	public static int playingFor() {
		return playing == null || playing.isStopped() ? -1 : playing.entityId;
	}

	private static final class Breath extends AbstractTickableSoundInstance {
		final int entityId;
		final boolean own;

		Breath(int entityId, boolean own) {
			super(HwSounds.SHAPE_BREATH, SoundSource.HOSTILE, RandomSource.create());
			this.entityId = entityId;
			this.own = own;
			this.looping = true;
			this.delay = 0;
			this.volume = own ? OWN_VOLUME : NEAR_VOLUME;
			this.relative = own;
			this.attenuation = own ? SoundInstance.Attenuation.NONE : SoundInstance.Attenuation.LINEAR;
			this.follow();
		}

		private void follow() {
			if (this.own) {
				this.x = this.y = this.z = 0;
				return;
			}
			Entity shape = Minecraft.getInstance().level == null ? null : Minecraft.getInstance().level.getEntity(this.entityId);
			if (shape != null) {
				this.x = shape.getX();
				this.y = shape.getEyeY();
				this.z = shape.getZ();
			}
		}

		@Override
		public void tick() {
			Entity shape = Minecraft.getInstance().level == null ? null : Minecraft.getInstance().level.getEntity(this.entityId);
			if (shape == null || shape.isRemoved()) {
				this.stop();
				return;
			}
			this.follow();
		}

		@Override
		public boolean canStartSilent() {
			return true;
		}
	}
}
