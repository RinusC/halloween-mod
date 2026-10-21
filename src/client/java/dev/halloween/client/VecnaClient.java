package dev.halloween.client;

import dev.halloween.HwConfig;
import dev.halloween.Role;
import dev.halloween.net.HwNet;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.phys.Vec3;

/**
 * Vecna on the client. For him: the Anchor key held and let go (HwNet.VecnaAnchorKey), and the Eyes (his camera
 * on the survivor the server names). For a survivor his curse has got to the second stage: phantoms of him (a
 * mannequin only this client has, drawn as the killer by MichaelRenderer) standing off to one side at a distance,
 * gone when looked at, come too close to, or after a few seconds.
 */
public final class VecnaClient {
	private static final class Phantom {
		final Mannequin body;
		final int born;

		Phantom(Mannequin body, int born) {
			this.body = body;
			this.born = born;
		}
	}

	private static final List<Phantom> PHANTOMS = new ArrayList<>();
	private static int nextPhantom = -1;
	private static int phantomIds = -7_000_000;
	private static boolean sentAnchor;
	/** The camera was put on someone else for the Eyes (and must come back). */
	private static boolean eyesCamera;
	/** A test's phantom stands this long whoever this client is. */
	private static int testUntil = -1;

	private VecnaClient() {
	}

	public static void tick(Minecraft mc) {
		if (mc.player == null || mc.level == null) {
			return;
		}
		tickPhantoms(mc);
		tickEyes(mc);
		tickRift(mc);
		VecnaLightning.tick(mc);
	}

	// ---------------------------------------------------------------- his keys and his Eyes

	/** In place of the Impulse press: the Anchor key down and up (true while consumed here). */
	static void tickAnchorKey(Minecraft mc) {
		boolean held = HalloweenClient.KEY_IMPULSE.isDown() && mc.gui.screen() == null;
		if (held != sentAnchor) {
			sentAnchor = held;
			ClientPlayNetworking.send(new HwNet.VecnaAnchorKey(held));
		}
	}

	/** Looking through someone's eyes right now? */
	public static boolean eyesActive() {
		return eyesCamera;
	}

	private static void tickEyes(Minecraft mc) {
		int id = MichaelRenderer.vecna() && ClientState.myRole() == Role.SHAPE ? ClientState.vecna.eyesTarget() : -1;
		Entity target = id < 0 ? null : mc.level.getEntity(id);
		if (target != null) {
			if (mc.getCameraEntity() != target) {
				mc.setCameraEntity(target);
			}
			eyesCamera = true;
		} else if (eyesCamera) {
			eyesCamera = false;
			mc.setCameraEntity(mc.player);
		}
	}

	// ---------------------------------------------------------------- the rift: the town gone under

	private static long riftFlashAt = -1000;
	private static long riftNextFlash;
	private static long riftNextDrone;
	/** Glowing crack cells near this player (looked for once a second). */
	private static final List<BlockPos> CRACKS = new ArrayList<>();

	/** The rift is open: everyone in the match lives in the Upside Down now. */
	public static boolean riftOpen() {
		return ClientState.ritual.rift() && ClientState.myRole() != Role.NONE;
	}

	/** Red lightning's glow in the murk now, 0..1 (a double flicker). */
	public static float riftFlash(float partial) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return 0f;
		}
		float t = mc.level.getGameTime() - riftFlashAt + partial;
		if (t < 0 || t > 12) {
			return 0f;
		}
		return t < 3 ? 1f - t / 3f : t > 5 && t < 8 ? 0.7f * (1f - (t - 5) / 3f) : 0f;
	}

	private static void tickRift(Minecraft mc) {
		if (!riftOpen()) {
			return;
		}
		long now = mc.level.getGameTime();
		var r = mc.level.getRandom();
		if (now >= riftNextFlash) {
			riftFlashAt = now;
			riftNextFlash = now + 70 + r.nextInt(120);
			// Orange lightning forking across the sky with it.
			VecnaLightning.strike(mc);
			mc.level.playLocalSound(mc.player.getX(), mc.player.getY() + 20, mc.player.getZ(), SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.AMBIENT,
				0.6f, 0.45f + r.nextFloat() * 0.15f, false);
		}
		if (now >= riftNextDrone) {
			riftNextDrone = now + 100 + r.nextInt(80);
			mc.level.playLocalSound(mc.player.getX(), mc.player.getY(), mc.player.getZ(), SoundEvents.AMBIENT_BASALT_DELTAS_MOOD.value(), SoundSource.AMBIENT, 0.7f, 0.5f, false);
		}
		// Over the cracks near by: embers, a red haze, smoke curling up.
		if (now % 20 == 0) {
			CRACKS.clear();
			BlockPos me = mc.player.blockPosition();
			BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
			for (int dx = -20; dx <= 20; dx++) {
				for (int dz = -20; dz <= 20; dz++) {
					for (int dy = -6; dy <= 3; dy++) {
						p.set(me.getX() + dx, me.getY() + dy, me.getZ() + dz);
						if (mc.level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.SHROOMLIGHT) && mc.level.getBlockState(p.above()).getCollisionShape(mc.level, p.above()).isEmpty()) {
							CRACKS.add(p.immutable());
							break;
						}
					}
				}
			}
		}
		for (int i = 0; i < Math.min(8, CRACKS.size()); i++) {
			BlockPos c = CRACKS.get(r.nextInt(CRACKS.size()));
			double x = c.getX() + r.nextDouble();
			double z = c.getZ() + r.nextDouble();
			double y = c.getY() + 1.02;
			switch (r.nextInt(6)) {
				case 0 -> mc.level.addParticle(net.minecraft.core.particles.ParticleTypes.SMALL_FLAME, x, y, z, 0, 0.02, 0);
				case 1 -> mc.level.addParticle(net.minecraft.core.particles.ParticleTypes.SMOKE, x, y, z, 0, 0.04, 0);
				case 2 -> {
					if (r.nextInt(6) == 0) {
						mc.level.addParticle(net.minecraft.core.particles.ParticleTypes.LAVA, x, y, z, 0, 0, 0);
					}
				}
				default -> mc.level.addParticle(new net.minecraft.core.particles.DustParticleOptions(0xD02A10, 2.2f), x, y + r.nextDouble() * 1.5, z, 0, 0.01, 0);
			}
		}
		// Spores and ash drifting in the air all round.
		for (int i = 0; i < 6; i++) {
			double x = mc.player.getX() + (r.nextDouble() - 0.5) * 24;
			double y = mc.player.getY() + r.nextDouble() * 8 - 1;
			double z = mc.player.getZ() + (r.nextDouble() - 0.5) * 24;
			mc.level.addParticle(i % 3 == 0 ? net.minecraft.core.particles.ParticleTypes.WHITE_ASH : net.minecraft.core.particles.ParticleTypes.CRIMSON_SPORE,
				x, y, z, 0, -0.01, 0);
		}
	}

	// ---------------------------------------------------------------- phantoms

	public static boolean isPhantom(Entity e) {
		for (Phantom p : PHANTOMS) {
			if (p.body == e) {
				return true;
			}
		}
		return false;
	}

	private static void tickPhantoms(Minecraft mc) {
		int now = (int) mc.level.getGameTime();
		if (now < testUntil) {
			return;
		}
		boolean cursed = ClientState.curse.stage() >= 2 && ClientState.myRole().survivor() && !mc.player.isSpectator();
		java.util.Iterator<Phantom> it = PHANTOMS.iterator();
		while (it.hasNext()) {
			Phantom p = it.next();
			Vec3 eye = mc.player.getEyePosition();
			Vec3 at = p.body.position().add(0, 1.2, 0);
			double angle = Math.toDegrees(Math.acos(Mth.clamp(mc.player.getViewVector(1f).dot(at.subtract(eye).normalize()), -1, 1)));
			int age = now - p.born;
			// Gone the moment it is looked at (after a beat to be glimpsed), come close to, or after a few seconds.
			boolean seen = age > 8 && angle < 14 && dev.halloween.game.Sight.canSee(mc.level, eye, at, mc.player);
			if (!cursed || seen || age > 120 || eye.distanceTo(at) < 4.5) {
				if (seen) {
					mc.level.playLocalSound(at.x, at.y, at.z, SoundEvents.SOUL_ESCAPE.value(), SoundSource.HOSTILE, 1.2f, 0.5f, false);
				}
				mc.level.removeEntity(p.body.getId(), Entity.RemovalReason.DISCARDED);
				it.remove();
			}
		}
		if (!cursed) {
			nextPhantom = -1;
			return;
		}
		HwConfig cfg = HwConfig.get();
		if (nextPhantom < 0) {
			nextPhantom = now + 100 + mc.level.getRandom().nextInt(200);
		}
		if (now >= nextPhantom && PHANTOMS.isEmpty()) {
			nextPhantom = now + cfg.vecnaPhantomMinTicks + mc.level.getRandom().nextInt(Math.max(1, cfg.vecnaPhantomMaxTicks - cfg.vecnaPhantomMinTicks));
			spawnPhantom(mc, now);
		}
	}

	/** Off to one side or behind, ten-odd blocks away, standing where it can be seen from here, facing them. */
	private static void spawnPhantom(Minecraft mc, int now) {
		var random = mc.level.getRandom();
		Vec3 eye = mc.player.getEyePosition();
		float yaw = mc.player.getYRot();
		for (int tries = 0; tries < 24; tries++) {
			float a = yaw + (random.nextBoolean() ? 1 : -1) * (60 + random.nextInt(100));
			double dist = 9 + random.nextInt(8);
			Vec3 dir = Vec3.directionFromRotation(0, a);
			BlockPos base = BlockPos.containing(mc.player.getX() + dir.x * dist, mc.player.getY(), mc.player.getZ() + dir.z * dist);
			for (int dy = 2; dy >= -2; dy--) {
				BlockPos feet = base.above(dy);
				if (!mc.level.getBlockState(feet).getCollisionShape(mc.level, feet).isEmpty()
					|| !mc.level.getBlockState(feet.above()).getCollisionShape(mc.level, feet.above()).isEmpty()
					|| !mc.level.getBlockState(feet.below()).isCollisionShapeFullBlock(mc.level, feet.below())) {
					continue;
				}
				Vec3 at = Vec3.atBottomCenterOf(feet);
				if (!dev.halloween.game.Sight.canSee(mc.level, eye, at.add(0, 1.4, 0), mc.player)) {
					continue;
				}
				// (Through create: on the client that is the client's own kind, with a skin and a renderer.)
				Mannequin m = Mannequin.create(EntityTypes.MANNEQUIN, mc.level);
				if (m == null) {
					return;
				}
				// No "NPC" line under it, and it stays where it is put.
				net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
				tag.putBoolean("hide_description", true);
				tag.putBoolean("immovable", true);
				tag.putBoolean("NoGravity", true);
				m.load(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, mc.level.registryAccess(), tag));
				m.setId(phantomIds--);
				Vec3 to = eye.subtract(at);
				float face = (float) (Math.atan2(-to.x, to.z) * 180 / Math.PI);
				m.snapTo(at.x, at.y, at.z, face, 0f);
				m.setYHeadRot(face);
				m.setYBodyRot(face);
				m.setNoGravity(true);
				mc.level.addEntity(m);
				PHANTOMS.add(new Phantom(m, now));
				// A tick of the clock where it stands.
				mc.level.playLocalSound(at.x, at.y + 1, at.z, SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.HOSTILE, 1f, 0.5f, false);
				return;
			}
		}
	}

	/** For tests: a phantom now, wherever this client is (not only for the cursed); true if one stands. */
	public static boolean testPhantom() {
		Minecraft mc = Minecraft.getInstance();
		int now = (int) mc.level.getGameTime();
		testUntil = now + 100;
		spawnPhantom(mc, now);
		return !PHANTOMS.isEmpty();
	}

	public static void reset() {
		VecnaLightning.clear();
		Minecraft mc = Minecraft.getInstance();
		for (Phantom p : PHANTOMS) {
			if (mc.level != null) {
				mc.level.removeEntity(p.body.getId(), Entity.RemovalReason.DISCARDED);
			}
		}
		PHANTOMS.clear();
		nextPhantom = -1;
		if (eyesCamera && mc.player != null) {
			mc.setCameraEntity(mc.player);
		}
		eyesCamera = false;
		sentAnchor = false;
	}
}
