package dev.halloween.client;

import dev.halloween.HwConfig;
import dev.halloween.HwSounds;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.phys.Vec3;

/**
 * What the cursed survivor sees and hears that isn't there (game.VecnaCurse, from its second stage), on their
 * screen only. Never smoke and mirrors: he is drawn as solid as the real one, stands where someone could stand, and
 * the sounds are the town's own (footsteps on what is underfoot there, a door, the clock). The deeper the curse, the
 * oftener and the closer:
 * <ul>
 * <li>a glimpse (stage 2): him standing off to one side down the street or in a doorway, watching, his head turning
 * after them; gone in a blink once really looked at, or come too near;</li>
 * <li>behind them (stage 3): steps coming up from behind, and if they turn round he is there, a few strides off,
 * for a heartbeat, the lights going with him; turn too late and there is nobody;</li>
 * <li>right beside them (stage 4): at the edge of the view an arm's length away, for a moment, with a whisper;</li>
 * <li>sounds without a body: steps behind, a whisper over the shoulder, a knock on a door near by, the clock
 * ticking from somewhere it can't be;</li>
 * <li>the lights failing (stage 3): every lamp and the flashlight in hand flicker and die for a moment.</li>
 * </ul>
 */
public final class CurseVisions {
	private enum Kind {
		GLIMPSE, BEHIND, BESIDE
	}

	private static final class Phantom {
		final Mannequin body;
		final Kind kind;
		final int born;
		/** Ticks it has been looked at, and when it was turned round to (BEHIND), or -1. */
		int looked;
		int caught = -1;
		/** BEHIND: the steps still to take towards them, and the next one. */
		int steps;
		int nextStep;

		Phantom(Mannequin body, Kind kind, int born) {
			this.body = body;
			this.kind = kind;
			this.born = born;
		}
	}

	/** A sound without a body: what, where (relative to the listener's facing: ahead, right), how far, the next of a few. */
	private static final class Phantasm {
		int left;
		int next;
		double ahead;
		double right;
		final boolean steps;

		Phantasm(boolean steps, int count, double ahead, double right, int now) {
			this.steps = steps;
			this.left = count;
			this.ahead = ahead;
			this.right = right;
			this.next = now;
		}
	}

	private static final List<Phantom> PHANTOMS = new ArrayList<>();
	private static Phantasm phantasm;
	private static int nextVision = -1;
	private static int nextSound = -1;
	private static int phantomIds = -7_000_000;
	/** The lights failing: since when (ticks), and the startle (the view jerking) since when. */
	private static float flickerAt = -1000;
	private static float startleAt = -1000;
	private static final float FLICKER = 26f;
	private static final float STARTLE = 10f;
	/** A test's phantom stands this long whoever this client is. */
	private static int testUntil = -1;

	private CurseVisions() {
	}

	private static int stage() {
		Minecraft mc = Minecraft.getInstance();
		return ClientState.myRole().survivor() && mc.player != null && !mc.player.isSpectator() ? ClientState.curse.stage() : 0;
	}

	/** The gaps between visions, shorter as the curse deepens. */
	private static int gap(RandomSource r, int stage) {
		HwConfig cfg = HwConfig.get();
		float k = stage >= 4 ? 0.45f : stage == 3 ? 0.7f : 1f;
		return Math.round((cfg.vecnaPhantomMinTicks + r.nextInt(Math.max(1, cfg.vecnaPhantomMaxTicks - cfg.vecnaPhantomMinTicks))) * k);
	}

	static void tick(Minecraft mc) {
		int now = (int) mc.level.getGameTime();
		if (now < testUntil) {
			return;
		}
		int stage = stage();
		tickPhantoms(mc, now, stage);
		tickPhantasm(mc, now);
		if (stage < 2) {
			nextVision = -1;
			nextSound = -1;
			phantasm = null;
			return;
		}
		RandomSource r = mc.level.getRandom();
		if (nextVision < 0) {
			nextVision = now + 100 + r.nextInt(200);
			nextSound = now + 200 + r.nextInt(300);
		}
		if (now >= nextVision && PHANTOMS.isEmpty()) {
			nextVision = now + gap(r, stage);
			// Deeper in, the nearer ones come oftener.
			float roll = r.nextFloat();
			Kind kind = stage >= 4 && roll < 0.3f ? Kind.BESIDE : stage >= 3 && roll < 0.65f ? Kind.BEHIND : Kind.GLIMPSE;
			if (!spawn(mc, kind, now) && kind != Kind.GLIMPSE) {
				spawn(mc, Kind.GLIMPSE, now);
			}
		}
		if (now >= nextSound && phantasm == null) {
			nextSound = now + gap(r, stage) * 2 / 3;
			sound(mc, now, stage, r);
		}
	}

	// ---------------------------------------------------------------- him

	private static void tickPhantoms(Minecraft mc, int now, int stage) {
		var it = PHANTOMS.iterator();
		Vec3 eye = mc.player.getEyePosition();
		while (it.hasNext()) {
			Phantom p = it.next();
			Vec3 head = p.body.position().add(0, 1.7, 0);
			double angle = Math.toDegrees(Math.acos(Mth.clamp(mc.player.getViewVector(1f).dot(head.subtract(eye).normalize()), -1, 1)));
			boolean visible = dev.halloween.game.Sight.canSee(mc.level, eye, head, mc.player);
			int age = now - p.born;
			// His head follows them.
			Vec3 to = eye.subtract(head);
			float face = (float) (Math.atan2(-to.x, to.z) * 180 / Math.PI);
			p.body.setYHeadRot(Mth.approachDegrees(p.body.getYHeadRot(), face, 4f));
			p.body.setXRot((float) -Math.toDegrees(Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z))));
			boolean gone = stage < 2 && now >= testUntil;
			boolean loud = false;
			switch (p.kind) {
				case GLIMPSE -> {
					if (visible && angle < 13) {
						p.looked++;
					}
					// Looked straight at for a moment (long enough to be sure of him), walked up to, or bored of waiting.
					gone |= p.looked > 12 || eye.distanceTo(head) < 6 || age > 220;
					loud = p.looked > 12;
				}
				case BEHIND -> {
					if (p.steps > 0) {
						if (now >= p.nextStep) {
							// Steps coming up behind them, each a little nearer.
							Vec3 step = to.multiply(1, 0, 1).normalize().scale(0.55);
							p.body.snapTo(p.body.getX() + step.x, p.body.getY(), p.body.getZ() + step.z, face, 0f);
							footstep(mc, p.body.position(), 0.9f);
							p.steps--;
							p.nextStep = now + 9;
						}
					}
					if (p.caught < 0 && visible && angle < 40) {
						// Turned round: there he is.
						p.caught = now;
						flickerAt = now;
						startleAt = now;
						mc.level.playLocalSound(head.x, head.y, head.z, HwSounds.VECNA_ANCHOR_REVEAL, SoundSource.HOSTILE, 1.1f, 0.8f, false);
					}
					gone |= p.caught >= 0 && now - p.caught > 7 || p.caught < 0 && age > 110;
				}
				case BESIDE -> {
					if (age == 2) {
						mc.level.playLocalSound(head.x, head.y, head.z, HwSounds.VECNA_WHISPER, SoundSource.HOSTILE, 0.9f, 0.9f, false);
					}
					if (visible && angle < 25 && p.caught < 0) {
						p.caught = now;
						startleAt = now;
					}
					gone |= age > 14 || p.caught >= 0 && now - p.caught > 4;
				}
			}
			if (gone) {
				if (loud) {
					// Gone between one blink and the next: only the air where he stood.
					mc.level.playLocalSound(head.x, head.y, head.z, HwSounds.VECNA_PHANTOM_VANISH, SoundSource.HOSTILE, 0.6f, 0.9f, false);
					var r = mc.level.getRandom();
					for (int i = 0; i < 5; i++) {
						mc.level.addParticle(dev.halloween.HwParticles.VECNA_ASH, p.body.getX() + (r.nextDouble() - 0.5) * 0.6, p.body.getY() + r.nextDouble() * 2,
							p.body.getZ() + (r.nextDouble() - 0.5) * 0.6, 0, 0.01, 0);
					}
				}
				mc.level.removeEntity(p.body.getId(), Entity.RemovalReason.DISCARDED);
				it.remove();
			}
		}
	}

	/**
	 * Him, put where {@code kind} wants him: a glimpse off to the side or ahead 10-18 blocks away where he can be seen
	 * from here, behind them 6-8 blocks off (to come up on them), beside them at the edge of the view. False if
	 * nowhere fits.
	 */
	private static boolean spawn(Minecraft mc, Kind kind, int now) {
		RandomSource random = mc.level.getRandom();
		Vec3 eye = mc.player.getEyePosition();
		float yaw = mc.player.getYRot();
		for (int tries = 0; tries < 30; tries++) {
			float side = random.nextBoolean() ? 1 : -1;
			float a;
			double dist;
			switch (kind) {
				case BEHIND -> {
					a = yaw + 180 + (random.nextFloat() - 0.5f) * 50;
					dist = 6 + random.nextDouble() * 2;
				}
				case BESIDE -> {
					a = yaw + side * (42 + random.nextInt(14));
					dist = 2.2 + random.nextDouble() * 0.8;
				}
				default -> {
					a = yaw + side * (35 + random.nextInt(75));
					dist = 10 + random.nextInt(9);
				}
			}
			Vec3 dir = Vec3.directionFromRotation(0, a);
			BlockPos base = BlockPos.containing(mc.player.getX() + dir.x * dist, mc.player.getY(), mc.player.getZ() + dir.z * dist);
			for (int dy = 2; dy >= -2; dy--) {
				BlockPos feet = base.above(dy);
				if (!mc.level.getBlockState(feet).getCollisionShape(mc.level, feet).isEmpty()
					|| !mc.level.getBlockState(feet.above()).getCollisionShape(mc.level, feet.above()).isEmpty()
					|| !mc.level.getBlockState(feet.above(2)).getCollisionShape(mc.level, feet.above(2)).isEmpty()
					|| !mc.level.getBlockState(feet.below()).isCollisionShapeFullBlock(mc.level, feet.below())) {
					continue;
				}
				Vec3 at = Vec3.atBottomCenterOf(feet);
				if (!dev.halloween.game.Sight.canSee(mc.level, eye, at.add(0, 1.6, 0), mc.player)) {
					continue;
				}
				if (kind == Kind.GLIMPSE && random.nextFloat() < 0.6f && !sheltered(mc, feet)) {
					// Rather in a doorway, under a porch, in the dark between houses than out in the open.
					continue;
				}
				Phantom p = new Phantom(body(mc, at, eye), kind, now);
				if (p.body == null) {
					return false;
				}
				if (kind == Kind.BEHIND) {
					p.steps = 3 + random.nextInt(2);
					p.nextStep = now + 4;
				}
				PHANTOMS.add(p);
				if (kind == Kind.GLIMPSE && stage() >= 3 && random.nextFloat() < 0.5f) {
					// The lights go as he comes.
					flickerAt = now;
				}
				if (kind == Kind.GLIMPSE) {
					// The clock, where he stands.
					mc.level.playLocalSound(at.x, at.y + 1.6, at.z, HwSounds.VECNA_CLOCK_TICK, SoundSource.HOSTILE, 0.8f, 1f, false);
				}
				return true;
			}
		}
		return false;
	}

	/** A roof or a wall close over the spot: a doorway, a porch, a gap between houses. */
	private static boolean sheltered(Minecraft mc, BlockPos feet) {
		for (int y = 2; y <= 5; y++) {
			if (!mc.level.getBlockState(feet.above(y)).isAir()) {
				return true;
			}
		}
		int walls = 0;
		for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
			walls += mc.level.getBlockState(feet.above().relative(d)).isAir() ? 0 : 1;
		}
		return walls >= 2;
	}

	private static Mannequin body(Minecraft mc, Vec3 at, Vec3 eye) {
		// (Through create: on the client that is the client's own kind, with a skin and a renderer.)
		Mannequin m = Mannequin.create(EntityTypes.MANNEQUIN, mc.level);
		if (m == null) {
			return null;
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
		return m;
	}

	// ---------------------------------------------------------------- sounds

	/** A sound without anybody there. */
	private static void sound(Minecraft mc, int now, int stage, RandomSource r) {
		Vec3 eye = mc.player.getEyePosition();
		int roll = r.nextInt(stage >= 3 ? 5 : 3);
		switch (roll) {
			// Steps behind, coming nearer (and nobody there when they turn).
			case 0, 3 -> phantasm = new Phantasm(true, 3 + r.nextInt(3), -(7 + r.nextInt(3)), (r.nextDouble() - 0.5) * 4, now);
			// The clock ticking from somewhere off to one side, four or five strokes.
			case 1 -> phantasm = new Phantasm(false, 4 + r.nextInt(2), 2 + r.nextDouble() * 6, (r.nextBoolean() ? 1 : -1) * (6 + r.nextDouble() * 4), now);
			// A knock on the nearest door.
			case 2 -> {
				BlockPos door = nearestDoor(mc, BlockPos.containing(eye), 12);
				if (door != null) {
					mc.level.playLocalSound(door.getX() + 0.5, door.getY() + 1, door.getZ() + 0.5, SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR, SoundSource.BLOCKS,
						0.3f, 0.7f, false);
				} else {
					whisper(mc, r);
				}
			}
			default -> whisper(mc, r);
		}
	}

	private static void whisper(Minecraft mc, RandomSource r) {
		// Over one shoulder, very close.
		Vec3 at = relative(mc, -0.8, (r.nextBoolean() ? 1 : -1) * 0.9).add(0, 0.2, 0);
		mc.level.playLocalSound(at.x, at.y, at.z, HwSounds.VECNA_WHISPER, SoundSource.HOSTILE, 0.7f, 0.85f + r.nextFloat() * 0.2f, false);
	}

	private static void tickPhantasm(Minecraft mc, int now) {
		Phantasm s = phantasm;
		if (s == null || now < s.next) {
			return;
		}
		if (s.left-- <= 0) {
			phantasm = null;
			return;
		}
		if (s.steps) {
			Vec3 at = relative(mc, s.ahead, s.right);
			footstep(mc, at, 0.8f);
			s.ahead = Math.min(-2.5, s.ahead + 1.2);
			s.next = now + 8 + mc.level.getRandom().nextInt(3);
		} else {
			Vec3 at = relative(mc, s.ahead, s.right).add(0, 1, 0);
			SoundEvent tick = s.left % 2 == 0 ? HwSounds.VECNA_CLOCK_TICK : HwSounds.VECNA_CLOCK_TOCK;
			mc.level.playLocalSound(at.x, at.y, at.z, tick, SoundSource.HOSTILE, 0.9f, 1f, false);
			s.next = now + 21;
		}
	}

	/** A point {@code ahead} blocks along their facing and {@code right} to their right, at their feet. */
	private static Vec3 relative(Minecraft mc, double ahead, double right) {
		Vec3 f = Vec3.directionFromRotation(0, mc.player.getYRot());
		Vec3 r = new Vec3(-f.z, 0, f.x);
		return mc.player.position().add(f.scale(ahead)).add(r.scale(right));
	}

	/** A footstep on whatever is underfoot there. */
	private static void footstep(Minecraft mc, Vec3 at, float volume) {
		BlockPos under = BlockPos.containing(at.x, at.y - 0.2, at.z);
		for (int i = 0; i < 3 && mc.level.getBlockState(under).isAir(); i++) {
			under = under.below();
		}
		SoundType type = mc.level.getBlockState(under).getSoundType();
		mc.level.playLocalSound(at.x, at.y, at.z, type.getStepSound(), SoundSource.HOSTILE, type.getVolume() * volume, type.getPitch() * 0.9f, false);
	}

	private static BlockPos nearestDoor(Minecraft mc, BlockPos from, int range) {
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (BlockPos p : BlockPos.betweenClosed(from.offset(-range, -2, -range), from.offset(range, 2, range))) {
			if (mc.level.getBlockState(p).getBlock() instanceof DoorBlock && p.distSqr(from) < bestD && p.distSqr(from) > 9) {
				bestD = p.distSqr(from);
				best = p.immutable();
			}
		}
		return best;
	}

	// ---------------------------------------------------------------- the lights, the view

	/** How much of the lamps' light is left now (the lights failing as he shows), 0..1. */
	public static float lights() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return 1f;
		}
		float age = mc.level.getGameTime() + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false) - flickerAt;
		if (age < 0 || age > FLICKER) {
			return 1f;
		}
		// Out at once, a sputter back, out again, then up slowly.
		if (age < 4) {
			return 0.05f;
		}
		if (age < 7) {
			return 0.7f;
		}
		if (age < 15) {
			return 0.08f;
		}
		return Mth.lerp((age - 15) / (FLICKER - 15), 0.2f, 1f);
	}

	/** The view's jerk when he is suddenly there (yaw, pitch degrees), or null. */
	static float[] startle(float partial) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || ClientVr.active()) {
			return null;
		}
		float age = mc.level.getGameTime() + partial - startleAt;
		if (age < 0 || age > STARTLE) {
			return null;
		}
		float k = (float) Math.exp(-age * 0.45f);
		return new float[] {Mth.sin(age * 3.1f) * 2.5f * k, -5f * k + Mth.sin(age * 4.7f) * 1.2f * k};
	}

	// ---------------------------------------------------------------- for the renderer, tests

	public static boolean isPhantomId(int id) {
		for (Phantom p : PHANTOMS) {
			if (p.body.getId() == id) {
				return true;
			}
		}
		return false;
	}

	public static boolean isPhantom(Entity e) {
		for (Phantom p : PHANTOMS) {
			if (p.body == e) {
				return true;
			}
		}
		return false;
	}

	/** For tests: a glimpse of him now, wherever this client is (not only for the cursed); true if one stands. */
	static boolean testPhantom() {
		Minecraft mc = Minecraft.getInstance();
		int now = (int) mc.level.getGameTime();
		testUntil = now + 100;
		return spawn(mc, Kind.GLIMPSE, now) || spawn(mc, Kind.BESIDE, now);
	}

	/** For tests: one of each (0 a glimpse, 1 behind, 2 beside) now, standing still for a few seconds; true if it stands. */
	public static boolean testVision(int kind) {
		Minecraft mc = Minecraft.getInstance();
		reset();
		int now = (int) mc.level.getGameTime();
		testUntil = now + 60;
		return spawn(mc, Kind.values()[kind], now);
	}

	/** For tests: the player turned to the vision, {@code off} degrees to one side of it. */
	public static void testFace(float off) {
		Minecraft mc = Minecraft.getInstance();
		if (!PHANTOMS.isEmpty()) {
			Vec3 d = PHANTOMS.getFirst().body.position().add(0, 1.6, 0).subtract(mc.player.getEyePosition());
			mc.player.setYRot((float) (Math.atan2(-d.x, d.z) * 180 / Math.PI) + off);
			mc.player.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z))));
		}
	}

	/** For tests: the lights failing now. */
	public static void testFlicker() {
		flickerAt = Minecraft.getInstance().level.getGameTime();
	}

	static void reset() {
		Minecraft mc = Minecraft.getInstance();
		for (Phantom p : PHANTOMS) {
			if (mc.level != null) {
				mc.level.removeEntity(p.body.getId(), Entity.RemovalReason.DISCARDED);
			}
		}
		PHANTOMS.clear();
		phantasm = null;
		nextVision = -1;
		nextSound = -1;
		flickerAt = -1000;
		startleAt = -1000;
	}
}
