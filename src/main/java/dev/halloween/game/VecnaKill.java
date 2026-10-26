package dev.halloween.game;

import dev.halloween.HwParticles;
import dev.halloween.HwSounds;
import dev.halloween.net.HwNet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Vecna's kills, in place of a knife-killer's (from {@link GrabSession}): the vines of the Upside Down out of his back
 * and palm, or out of the ground, do it. Seen from a slow camera off to the side by him and by them (KillCam), the
 * rest of the town sees it where it happens. The server moves the bodies and makes the sounds, blood and dust; the
 * poses, his moves and the vines are client.VecnaKillScene's, from the {@link HwNet.VecnaKill} everyone near gets.
 * <ul>
 * <li>{@link #ASCEND}: lifted overhead by the throat, let go to hang in the air, run through by the vines, the neck
 * broken with a twist of his hand, dropped.
 * <li>{@link #DRAG}: the ground splits under them, vines out of the pit wind round their legs and drag them in to the
 * waist; he wrenches their head round; the vines heave the body out and the pit closes to a burning scar.
 * <li>At a wardrobe: {@link #LOCKER_OPEN} (it shakes, the vines go in through the door, burst it open and drag them
 * out, held up against it: {@link #LOCKER_HOLD} for the struggle), then {@link #LOCKER_KILL} (his claw over their face,
 * the vines through them into the door, flung back inside, the doors slam; blood runs out from under them; the doors
 * creak open and the body slumps out).
 * </ul>
 */
public final class VecnaKill {
	public static final int NONE = 0;
	public static final int ASCEND = 1;
	public static final int DRAG = 2;
	public static final int LOCKER_OPEN = 3;
	public static final int LOCKER_HOLD = 4;
	public static final int LOCKER_KILL = 5;

	/** Ascend: lifted by the throat until A_LIFT, let go by A_FREE, the vines out at A_LAUNCH and through them at A_IMPALE, the neck, out, down, dead. */
	public static final int ASCEND_TICKS = 100;
	public static final int A_LIFT = 20;
	public static final int A_FREE = 32;
	public static final int A_LAUNCH = 34;
	public static final int A_IMPALE = 40;
	public static final int A_COIL = 46;
	public static final int A_SNAP = 68;
	public static final int A_RIP = 74;
	public static final int A_LAND = 84;
	private static final int A_KILL = 96;

	/** Drag: his claw down at D_SLAM (the ground splits), the vines up and round the legs, let go, three heaves, the neck, the body out, the pit shut. */
	public static final int DRAG_TICKS = 112;
	public static final int D_SLAM = 12;
	public static final int D_RISE = 14;
	public static final int D_LET_GO = 26;
	public static final int[] D_TUGS = {30, 44, 56};
	public static final double[] D_DEPTH = {0.45, 0.85, 1.15};
	public static final int D_SNAP = 76;
	public static final int D_HEAVE = 80;
	public static final int D_LAID = 90;
	public static final int D_CLOSE = 100;
	private static final int D_KILL = 108;

	/** The wardrobe: the vines in at O_VINES, the doors burst at O_BURST, dragged out by O_OUT; the struggle at OPEN_TICKS. */
	public static final int OPEN_TICKS = 40;
	public static final int O_VINES = 10;
	public static final int O_BURST = 22;
	public static final int O_OUT = 30;

	/** The kill at the wardrobe: the claw over the face by L_GRIP, the vines through at L_SPEARS, out and flung inside at L_RIP, the doors at L_SLAM and L_OPEN. */
	public static final int LOCKER_KILL_TICKS = 92;
	public static final int L_GRIP = 12;
	public static final int[] L_SPEARS = {22, 28, 34};
	public static final int L_RIP = 56;
	public static final int L_SLAM = 61;
	public static final int L_OPEN = 78;
	public static final int L_OUT = 86;
	private static final int L_KILL = 90;

	/** How long the held pose at a wardrobe is sent for (it is broken off by a NONE when the struggle ends). */
	private static final int HOLD_TICKS = 20 * 120;

	private final GrabSession grab;
	private final ServerPlayer vecna;
	private final LivingEntity victim;
	private int kind;
	private int t;
	/** Where he stays and which way he faces them; where they stood when it began; their floor. */
	private Vec3 vecnaSpot;
	private float yaw;
	private Vec3 from;
	private double floorY;
	/** Out from him towards them (level). */
	private Vec3 ahead;
	/** Ascend: how high they can be lifted (a ceiling), how far out from him they hang. */
	private double lift = 1.95;
	private double reach = 1.75;
	/** Drag: the cell under them torn open, what was there (the one below too), and where the body is laid. */
	private BlockPos pit;
	private BlockState pitWas;
	private BlockState pitBelowWas;
	private Vec3 laid;
	/** A wardrobe: its base, out of its door, the door's middle (at the floor), held up against it. */
	private BlockPos locker;
	private Direction facing;
	private Vec3 door;
	private Vec3 held;
	/** The kill at a wardrobe: where he stood for the struggle, and where he comes in close to. */
	private Vec3 stand;
	private Vec3 close;
	private boolean sentEnd;

	private VecnaKill(GrabSession grab, ServerPlayer vecna, LivingEntity victim) {
		this.grab = grab;
		this.vecna = vecna;
		this.victim = victim;
	}

	private ServerLevel level() {
		return this.vecna.level();
	}

	// ---------------------------------------------------------------- the finish of a grab

	/** His kill once the struggle is won (not at a wardrobe): one of the two at random; dragged under only where the ground can open. */
	static VecnaKill finisher(GrabSession grab, ServerPlayer vecna, LivingEntity victim) {
		VecnaKill k = new VecnaKill(grab, vecna, victim);
		k.vecnaSpot = vecna.position();
		k.from = victim.position();
		k.floorY = k.from.y;
		Vec3 d = k.from.subtract(k.vecnaSpot).multiply(1, 0, 1);
		k.ahead = d.lengthSqr() < 1e-4 ? Vec3.directionFromRotation(0, vecna.getYRot()) : d.normalize();
		k.yaw = (float) Math.toDegrees(Math.atan2(-k.ahead.x, k.ahead.z));
		boolean drag = forceKind == DRAG || forceKind != ASCEND && vecna.getRandom().nextBoolean();
		if (drag && k.canOpen()) {
			k.kind = DRAG;
		} else {
			k.kind = ASCEND;
			k.room();
		}
		k.start(k.kind == DRAG ? DRAG_TICKS : ASCEND_TICKS);
		return k;
	}

	/** For tests: the next finisher is this one (0: at random). */
	public static int forceKind;

	/** Can the ground under them open (two whole blocks down, nothing built into them, open above)? */
	private boolean canOpen() {
		ServerLevel level = this.level();
		BlockPos feet = BlockPos.containing(this.from.x, this.from.y + 0.2, this.from.z);
		BlockPos ground = feet.below();
		for (BlockPos p : new BlockPos[]{ground, ground.below()}) {
			BlockState s = level.getBlockState(p);
			if (s.hasBlockEntity() || s.is(Blocks.BARRIER) || !s.isCollisionShapeFullBlock(level, p) || !s.getFluidState().isEmpty()) {
				return false;
			}
		}
		if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty() || !level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) {
			return false;
		}
		this.pit = ground.immutable();
		return true;
	}

	/** Ascend: how high there is room to lift them (under a ceiling), and how far out from him (before a wall). */
	private void room() {
		ServerLevel level = this.level();
		AABB body = this.victim.getBoundingBox();
		double up = 1.95;
		while (up > 0.4 && !level.noCollision(this.victim, body.move(this.ahead.scale(0.15)).move(0, up, 0))) {
			up -= 0.1;
		}
		this.lift = up;
		double out = 1.75;
		while (out > 1.0 && !level.noCollision(this.victim, body.move(this.ahead.scale(out - this.from.subtract(this.vecnaSpot).horizontalDistance())).move(0, up, 0))) {
			out -= 0.1;
		}
		this.reach = out;
	}

	// ---------------------------------------------------------------- a wardrobe

	/** Pulled out of a wardrobe: where he stands at it, and where they are held against its door (GrabSession's). */
	static VecnaKill lockerOpen(GrabSession grab, ServerPlayer vecna, LivingEntity victim, BlockPos locker, Direction facing) {
		VecnaKill k = new VecnaKill(grab, vecna, victim);
		ServerLevel level = vecna.level();
		k.locker = locker.immutable();
		k.facing = facing;
		k.ahead = new Vec3(-facing.getStepX(), 0, -facing.getStepZ());
		k.door = Vec3.atBottomCenterOf(locker).add(k.out().scale(0.5));
		k.held = k.door.add(k.out().scale(0.36)).add(0, 0.22, 0);
		// Well back from it (the vines reach), nearer only if something is in the way.
		k.vecnaSpot = vecna.position();
		for (double off : new double[]{1.85, 1.5, 1.2}) {
			Vec3 at = k.door.add(k.out().scale(off));
			at = new Vec3(at.x, vecna.getY(), at.z);
			if (level.noCollision(vecna, vecna.getBoundingBox().move(at.subtract(vecna.position())))) {
				k.vecnaSpot = at;
				break;
			}
		}
		k.yaw = facing.getOpposite().toYRot();
		k.from = Vec3.atBottomCenterOf(locker);
		k.floorY = k.from.y;
		k.kind = LOCKER_OPEN;
		dev.halloween.Vr.place(vecna, level, k.vecnaSpot.x, k.vecnaSpot.y, k.vecnaSpot.z, Set.of(), k.yaw, 10f);
		k.start(OPEN_TICKS + 4);
		return k;
	}

	/** Out of the wardrobe's door. */
	private Vec3 out() {
		return new Vec3(this.facing.getStepX(), 0, this.facing.getStepZ());
	}

	/** Where they are held against the door during the struggle, and where he stands. */
	Vec3 held() {
		return this.held;
	}

	Vec3 vecnaSpot() {
		return this.vecnaSpot;
	}

	/** The scene at the wardrobe before the struggle; true once it is over (the struggle starts). */
	boolean tickOpen() {
		ServerLevel level = this.level();
		int t = this.t++;
		this.holdVecna();
		Vec3 c = Vec3.atCenterOf(this.locker).add(0, 0.5, 0);
		BlockState wood = level.getBlockState(this.locker);
		if (t == 0) {
			level.playSound(null, c.x, c.y, c.z, HwSounds.VECNA_LOCKER_RATTLE, SoundSource.HOSTILE, 1.6f, 1f);
			level.playSound(null, this.vecna.getX(), this.vecna.getEyeY(), this.vecna.getZ(), HwSounds.VECNA_GRIP_CHARGE, SoundSource.HOSTILE, 1.4f, 0.8f);
			this.camera(OPEN_TICKS + 2, this.door.lerp(this.vecnaSpot, 0.5).add(0, 1.5, 0), 6.4, 1.2, 20f);
		}
		if (t < O_BURST && t % 3 == 0) {
			// It shakes: dust off it, red light leaking out at the seams of the door.
			Vec3 side = this.out().cross(new Vec3(0, 1, 0));
			level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, wood), this.door.x, this.door.y + 1.0, this.door.z, 4, Math.abs(side.x) * 0.4, 0.8, Math.abs(side.z) * 0.4, 0.05);
			for (int i = 0; i < 2; i++) {
				double h = 0.2 + level.getRandom().nextDouble() * 1.7;
				double s = (level.getRandom().nextBoolean() ? 1 : -1) * (0.05 + level.getRandom().nextDouble() * 0.4);
				Vec3 seam = this.door.add(side.scale(s)).add(0, h, 0).add(this.out().scale(0.04));
				level.sendParticles(HwParticles.VECNA_SPARK, seam.x, seam.y, seam.z, 0, this.out().x * 0.04, 0.01, this.out().z * 0.04, 1);
			}
			this.shake(this.victim, 4, 0.3f);
		}
		if (t == O_VINES) {
			this.burst(1f);
		}
		if (t == 16) {
			level.playSound(null, c.x, c.y, c.z, SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR, SoundSource.HOSTILE, 1.4f, 0.7f);
			Voices.play(this.victim, Voices.Kind.SCREAM);
		}
		if (t == O_BURST) {
			// The doors burst open and they come out on the vines.
			Lockers.leave(this.victim, false);
			LockerBlock.setOpen(level, this.locker, true);
			level.playSound(null, c.x, c.y, c.z, HwSounds.LOCKER_SLAM, SoundSource.HOSTILE, 2.2f, 0.6f);
			level.playSound(null, c.x, c.y, c.z, SoundEvents.ZOMBIE_BREAK_WOODEN_DOOR, SoundSource.HOSTILE, 1.4f, 0.8f);
			level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, wood), this.door.x, this.door.y + 1.0, this.door.z, 50, 0.4, 0.7, 0.4, 0.2);
			Voices.play(this.victim, Voices.Kind.SCREAM);
			this.shake(this.victim, 12, 0.8f);
			this.shake(this.vecna, 8, 0.4f);
			GrabSession.anim(this.victim, HwNet.Anim.STRUGGLE);
		}
		if (t >= O_BURST) {
			// Dragged out of it up against its door, off the floor.
			float k = Mth.clamp((t - O_BURST) / (float) (O_OUT - O_BURST), 0f, 1f);
			k = k * k * (3 - 2 * k);
			Vec3 in = Vec3.atBottomCenterOf(this.locker).add(this.out().scale(0.1));
			Vec3 at = in.lerp(this.held, k).add(0, Mth.sin(k * Mth.PI) * 0.25, 0);
			this.grab.placeVictim(at.x, at.y, at.z, this.facing.toYRot());
			this.victim.setDeltaMovement(Vec3.ZERO);
		}
		if (t == O_OUT) {
			LockerBlock.setOpen(level, this.locker, false);
			level.playSound(null, c.x, c.y, c.z, HwSounds.LOCKER_SLAM, SoundSource.HOSTILE, 1.6f, 0.8f);
			GrabSession.anim(this.vecna, HwNet.Anim.LOCKER_HOLD);
			level.playSound(null, this.held.x, this.held.y + 1, this.held.z, HwSounds.VECNA_VINE_SLITHER, SoundSource.HOSTILE, 1.2f, 1f);
		}
		if (t >= OPEN_TICKS) {
			this.kind = LOCKER_HOLD;
			this.t = 0;
			this.start(HOLD_TICKS);
			return true;
		}
		return false;
	}

	/** The struggle won at the wardrobe: the kill there. */
	void lockerFinisher() {
		this.kind = LOCKER_KILL;
		this.t = 0;
		this.start(LOCKER_KILL_TICKS);
	}

	// ---------------------------------------------------------------- playing it

	/** Tells everyone near which scene now (from this tick), and gives the two of them the camera. */
	private void start(int ticks) {
		ServerLevel level = this.level();
		this.sentEnd = false;
		Vec3 a = this.locker != null ? this.door : this.pit != null ? Vec3.atBottomCenterOf(this.pit).add(0, 1, 0) : this.from;
		Vec3 b = this.locker != null ? Vec3.atBottomCenterOf(this.locker) : this.vecnaSpot;
		send(new HwNet.VecnaKill(this.kind, this.vecna.getId(), this.victim.getId(), level.getGameTime(), ticks, this.yaw, a, b), this.victim);
		if (this.kind != LOCKER_OPEN && this.kind != LOCKER_HOLD) {
			GrabSession.anim(this.vecna, HwNet.Anim.NONE);
		}
		switch (this.kind) {
			case ASCEND -> {
				// Between him and where they will hang, low down, looking up.
				Vec3 look = this.vecnaSpot.add(this.ahead.scale(this.reach * 0.5)).add(0, 1.3 + this.lift * 0.55, 0);
				this.camera(ticks, look, 7.4, -0.5, 40f);
			}
			case DRAG -> this.camera(ticks, Vec3.atBottomCenterOf(this.pit).add(0, 1, 0).lerp(this.vecnaSpot, 0.5).add(0, 1.0, 0), 6.2, 2.4, 24f);
			case LOCKER_KILL -> this.camera(ticks, this.held.lerp(this.vecnaSpot, 0.5).add(0, 1.4, 0), 6.0, 0.9, 16f);
			default -> {
			}
		}
	}

	private static void send(HwNet.VecnaKill payload, LivingEntity near) {
		if (!(near.level() instanceof ServerLevel level)) {
			return;
		}
		for (ServerPlayer p : level.players()) {
			if (!Bots.isBot(p) && p.distanceToSqr(near) < 160 * 160) {
				ServerPlayNetworking.send(p, payload);
			}
		}
	}

	/** Broken off (torn free, the match over): the vines let go on every client. */
	void stop() {
		if (!this.sentEnd) {
			this.sentEnd = true;
			send(new HwNet.VecnaKill(NONE, this.vecna.getId(), this.victim.getId(), this.level().getGameTime(), 0, this.yaw, Vec3.ZERO, Vec3.ZERO), this.victim);
		}
		if (this.locker != null && this.kind == LOCKER_HOLD) {
			this.level().playSound(null, this.held.x, this.held.y + 1, this.held.z, HwSounds.VECNA_GRIP_SNAP, SoundSource.HOSTILE, 1.2f, 1.3f);
		}
	}

	/** Every tick of the kill; true once they are dead. */
	boolean tick() {
		int t = ++this.t;
		this.holdVecna();
		boolean dead = switch (this.kind) {
			case ASCEND -> this.tickAscend(t);
			case DRAG -> this.tickDrag(t);
			case LOCKER_KILL -> this.tickLocker(t);
			default -> true;
		};
		// Played out to the end on the clients: nothing to break off.
		this.sentEnd |= dead;
		return dead;
	}

	/** He stays where the scene has him, turned to them, whatever the player does with the keys. */
	private void holdVecna() {
		if (this.vecna.position().distanceToSqr(this.vecnaSpot) > 0.0025) {
			dev.halloween.Vr.place(this.vecna, this.level(), this.vecnaSpot.x, this.vecnaSpot.y, this.vecnaSpot.z, Set.of(Relative.X_ROT, Relative.Y_ROT), 0f, 0f);
		}
		this.vecna.setDeltaMovement(Vec3.ZERO);
		this.vecna.setYBodyRot(this.yaw);
	}

	private static float smooth(float x) {
		x = Mth.clamp(x, 0f, 1f);
		return x * x * (3 - 2 * x);
	}

	// ---------------------------------------------------------------- ascend

	private boolean tickAscend(int t) {
		ServerLevel level = this.level();
		double d0 = this.from.subtract(this.vecnaSpot).horizontalDistance();
		double out;
		double up;
		if (t <= A_LIFT) {
			float k = smooth(t / (float) A_LIFT);
			out = Mth.lerp(k, d0, 1.0);
			up = this.lift * 0.6 * k;
		} else if (t <= A_FREE) {
			float k = smooth((t - A_LIFT) / (float) (A_FREE - A_LIFT));
			out = Mth.lerp(k, 1.0, this.reach);
			up = Mth.lerp(k, this.lift * 0.6, this.lift);
		} else if (t < A_RIP + 2) {
			out = this.reach;
			up = this.lift + 0.05 * Mth.sin(t * 0.3f);
			if (t >= A_IMPALE && t < A_IMPALE + 6) {
				// Knocked back on the vines.
				out += 0.14 * (1 - (t - A_IMPALE) / 6.0);
				up += 0.08 * (1 - (t - A_IMPALE) / 6.0);
			}
			if (t >= A_SNAP) {
				up -= 0.08;
			}
		} else {
			// Dropped: down like a stone.
			float k = Mth.clamp((t - A_RIP - 2) / (float) (A_LAND - A_RIP - 2), 0f, 1f);
			out = this.reach + 0.15 * k;
			up = (this.lift - 0.08) * (1 - k * k);
		}
		Vec3 at = this.vecnaSpot.add(this.ahead.scale(out));
		this.grab.placeVictim(at.x, this.floorY + up, at.z, this.yaw + 180f);
		this.victim.setDeltaMovement(Vec3.ZERO);
		Vec3 chest = new Vec3(at.x, this.floorY + up + 1.15, at.z);
		if (t == 1) {
			Voices.play(this.victim, Voices.Kind.SCREAM);
			level.playSound(null, chest.x, chest.y, chest.z, HwSounds.VECNA_GRIP_PULL, SoundSource.HOSTILE, 1.2f, 0.8f);
		}
		if (t == A_LIFT) {
			level.playSound(null, chest.x, chest.y, chest.z, HwSounds.VECNA_CLOCK_CHIME, SoundSource.HOSTILE, 1.8f, 1f);
			level.playSound(null, chest.x, chest.y, chest.z, HwSounds.VECNA_CURSE_FLOAT, SoundSource.HOSTILE, 1.4f, 1.1f);
		}
		if (t > A_LIFT && t < A_RIP && t % 2 == 0) {
			// The air round them stirring: sparks circling, ash drifting up.
			double a = t * 0.45;
			for (int i = 0; i < 2; i++) {
				double b = a + i * Math.PI;
				level.sendParticles(HwParticles.VECNA_SPARK, chest.x + Math.cos(b) * 0.8, chest.y - 0.6 + (t % 20) * 0.06, chest.z + Math.sin(b) * 0.8, 1, 0, 0, 0, 0);
			}
			level.sendParticles(HwParticles.VECNA_ASH, chest.x, chest.y - 1.2, chest.z, 1, 0.4, 0.1, 0.4, 0.02);
		}
		if (t == A_LAUNCH) {
			this.burst(1f);
		}
		if (t == A_IMPALE) {
			this.impale(chest, 1f);
			Voices.play(this.victim, Voices.Kind.HURT);
		}
		if (t == A_COIL || t == 58) {
			level.playSound(null, chest.x, chest.y, chest.z, HwSounds.VECNA_VINE_SLITHER, SoundSource.HOSTILE, 1.3f, 0.9f + level.getRandom().nextFloat() * 0.2f);
		}
		if (t == 50 || t == 60) {
			level.playSound(null, chest.x, chest.y, chest.z, HwSounds.VECNA_BONE_CRACK, SoundSource.HOSTILE, 1.6f, 0.9f);
			Voices.play(this.victim, Voices.Kind.HURT);
			this.shake(this.victim, 6, 0.5f);
		}
		if (t > A_IMPALE && t < A_RIP && t % 3 == 0) {
			level.sendParticles(HwParticles.BLOOD_DRIP, chest.x, chest.y - 0.3, chest.z, 1, 0.12, 0.1, 0.12, 0);
		}
		if (t == A_SNAP) {
			this.snapNeck(chest.add(0, 0.5, 0));
		}
		if (t == A_RIP) {
			this.rip(chest);
		}
		if (t == A_LAND) {
			this.land(at);
		}
		return t >= A_KILL;
	}

	// ---------------------------------------------------------------- dragged under

	private boolean tickDrag(int t) {
		ServerLevel level = this.level();
		Vec3 hole = Vec3.atBottomCenterOf(this.pit).add(0, 1, 0);
		double depth = 0;
		for (int i = 0; i < D_TUGS.length; i++) {
			float k = smooth((t - D_TUGS[i]) / 5f);
			depth = Mth.lerp(k, depth, D_DEPTH[i]);
		}
		Vec3 at;
		if (t < D_SLAM) {
			at = this.from;
		} else if (t < D_HEAVE) {
			// Over to the middle of the hole (the ground gone under them), then in.
			float k = smooth((t - D_SLAM) / 10f);
			at = this.from.lerp(hole, k).add(0, -depth, 0);
			if (t > D_LET_GO && t < D_SNAP && t % 4 == 0) {
				// Struggling in it: a jerk this way and that.
				at = at.add((level.getRandom().nextDouble() - 0.5) * 0.06, 0, (level.getRandom().nextDouble() - 0.5) * 0.06);
			}
		} else {
			// Heaved out by the vines and laid down beside it.
			if (this.laid == null) {
				this.laid = this.layOut(hole);
			}
			float k = smooth((t - D_HEAVE) / (float) (D_LAID - D_HEAVE));
			at = hole.add(0, -D_DEPTH[D_DEPTH.length - 1], 0).lerp(this.laid, k).add(0, Mth.sin(k * Mth.PI) * 0.9, 0);
		}
		this.grab.placeVictim(at.x, at.y, at.z, this.yaw + 180f);
		this.victim.setDeltaMovement(Vec3.ZERO);
		if (t == 1) {
			level.playSound(null, at.x, at.y + 1, at.z, HwSounds.VECNA_GRIP_PULL, SoundSource.HOSTILE, 1.0f, 0.7f);
		}
		if (t == D_SLAM) {
			this.split(hole);
		}
		if (t > D_SLAM && t < D_CLOSE && t % 2 == 0) {
			// The pit breathes red light and embers.
			level.sendParticles(HwParticles.VECNA_EMBER, hole.x, hole.y + 0.1, hole.z, 1, 0.25, 0.05, 0.25, 0.03);
			if (t % 6 == 0) {
				level.sendParticles(HwParticles.VECNA_SMOKE, hole.x, hole.y + 0.2, hole.z, 1, 0.3, 0.05, 0.3, 0.01);
			}
		}
		if (t == D_RISE) {
			level.playSound(null, hole.x, hole.y, hole.z, HwSounds.VECNA_VINE_BURST, SoundSource.HOSTILE, 1.8f, 0.8f);
			level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, this.pitWas), hole.x, hole.y + 0.1, hole.z, 30, 0.3, 0.1, 0.3, 0.15);
			Voices.play(this.victim, Voices.Kind.SCREAM);
		}
		if (t == D_LET_GO) {
			level.playSound(null, at.x, at.y + 1, at.z, HwSounds.VECNA_VINE_SLITHER, SoundSource.HOSTILE, 1.4f, 0.85f);
		}
		for (int tug : D_TUGS) {
			if (t == tug) {
				level.playSound(null, hole.x, hole.y, hole.z, HwSounds.VECNA_GRIP_PULL, SoundSource.HOSTILE, 1.4f, 0.6f);
				level.playSound(null, hole.x, hole.y, hole.z, SoundEvents.ROOTED_DIRT_BREAK, SoundSource.HOSTILE, 1.6f, 0.6f);
				level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, this.pitWas), hole.x, hole.y + 0.05, hole.z, 14, 0.35, 0.05, 0.35, 0.1);
				Voices.play(this.victim, tug == D_TUGS[0] ? Voices.Kind.SCREAM : Voices.Kind.HURT);
				this.shake(this.victim, 8, 0.6f);
				this.shake(this.vecna, 4, 0.25f);
			}
		}
		if (t == 62) {
			level.playSound(null, at.x, at.y + 1, at.z, HwSounds.VECNA_VINE_SLITHER, SoundSource.HOSTILE, 1.2f, 0.75f);
		}
		if (t == D_SNAP) {
			this.snapNeck(at.add(0, 1.5, 0));
		}
		if (t == D_HEAVE) {
			level.playSound(null, hole.x, hole.y, hole.z, HwSounds.VECNA_VINE_BURST, SoundSource.HOSTILE, 1.6f, 1.1f);
			level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, this.pitWas), hole.x, hole.y + 0.1, hole.z, 24, 0.3, 0.1, 0.3, 0.2);
			GrabSession.anim(this.victim, HwNet.Anim.LIMP);
		}
		if (t == D_LAID) {
			this.land(this.laid);
		}
		if (t == D_CLOSE) {
			// The pit shuts: a burning seam left in the ground.
			Map<BlockPos, BlockState> cells = new HashMap<>();
			cells.put(this.pit, Blocks.SHROOMLIGHT.defaultBlockState());
			cells.put(this.pit.below(), this.pitBelowWas);
			for (Map.Entry<BlockPos, BlockState> c : cells.entrySet()) {
				level.setBlock(c.getKey(), c.getValue(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
			}
			level.playSound(null, hole.x, hole.y, hole.z, HwSounds.VECNA_GROUND_SPLIT, SoundSource.HOSTILE, 0.8f, 1.6f);
			level.sendParticles(HwParticles.VECNA_SMOKE, hole.x, hole.y + 0.2, hole.z, 6, 0.3, 0.1, 0.3, 0.02);
		}
		return t >= D_KILL;
	}

	/** Where the body is laid when heaved out: beyond the pit from him, or to a side of it, wherever a body fits. */
	private Vec3 layOut(Vec3 hole) {
		ServerLevel level = this.level();
		Vec3 side = this.ahead.cross(new Vec3(0, 1, 0));
		for (Vec3 d : new Vec3[]{this.ahead, side, side.scale(-1), this.ahead.scale(-1)}) {
			Vec3 at = hole.add(d.scale(1.05));
			AABB box = new AABB(at.x - 0.3, at.y + 0.05, at.z - 0.3, at.x + 0.3, at.y + 1.0, at.z + 0.3);
			if (level.noCollision(box) && !level.getBlockState(BlockPos.containing(at).below()).getCollisionShape(level, BlockPos.containing(at).below()).isEmpty()) {
				return at;
			}
		}
		return hole;
	}

	/** His claw down at the ground: it splits under them, a burnt scar round a pit two blocks deep. */
	private void split(Vec3 hole) {
		ServerLevel level = this.level();
		this.pitWas = level.getBlockState(this.pit);
		this.pitBelowWas = level.getBlockState(this.pit.below());
		Map<BlockPos, BlockState> cells = new HashMap<>();
		cells.put(this.pit, Blocks.AIR.defaultBlockState());
		cells.put(this.pit.below(), Blocks.AIR.defaultBlockState());
		BlockPos bottom = this.pit.below(2);
		if (level.getBlockState(bottom).isCollisionShapeFullBlock(level, bottom)) {
			cells.put(bottom, Blocks.SHROOMLIGHT.defaultBlockState());
		}
		// The scar: charred ground and burning seams out from the pit, only where there is open ground at its level.
		net.minecraft.util.RandomSource r = level.getRandom();
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				if (dx == 0 && dz == 0) {
					continue;
				}
				double d = Math.sqrt(dx * dx + dz * dz);
				if (d > 2.3 || d > 1.5 && r.nextFloat() < 0.55f) {
					continue;
				}
				BlockPos p = this.pit.offset(dx, 0, dz);
				BlockState s = level.getBlockState(p);
				if (!s.isCollisionShapeFullBlock(level, p) || !level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()) {
					continue;
				}
				cells.put(p, d > 1.5 && r.nextFloat() < 0.3f ? Blocks.CRIMSON_NYLIUM.defaultBlockState() : Blocks.BLACKSTONE.defaultBlockState());
			}
		}
		// (The cracks burning in it are drawn on the clients: client.VecnaKillScene.)
		VecnaCracks.scar(level, cells);
		level.playSound(null, hole.x, hole.y, hole.z, HwSounds.VECNA_GROUND_SPLIT, SoundSource.HOSTILE, 2.2f, 1f);
		level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, this.pitWas), hole.x, hole.y + 0.1, hole.z, 60, 1.0, 0.15, 1.0, 0.25);
		level.sendParticles(HwParticles.VECNA_EMBER, hole.x, hole.y + 0.2, hole.z, 20, 0.6, 0.1, 0.6, 0.08);
		level.sendParticles(HwParticles.VECNA_SMOKE, hole.x, hole.y + 0.3, hole.z, 10, 0.8, 0.1, 0.8, 0.02);
		Voices.play(this.victim, Voices.Kind.SCREAM);
		this.shake(this.victim, 16, 0.8f);
		this.shake(this.vecna, 12, 0.5f);
	}

	// ---------------------------------------------------------------- the wardrobe

	private boolean tickLocker(int t) {
		ServerLevel level = this.level();
		Vec3 inside = Vec3.atBottomCenterOf(this.locker).add(this.out().scale(0.08));
		// He comes in close for his claw.
		if (t <= L_GRIP) {
			if (this.stand == null) {
				this.stand = this.vecnaSpot;
				Vec3 near = this.door.add(this.out().scale(1.3));
				Vec3 to = new Vec3(near.x, this.stand.y, near.z);
				this.close = level.noCollision(this.vecna, this.vecna.getBoundingBox().move(to.subtract(this.vecna.position()))) ? to : this.stand;
			}
			this.vecnaSpot = this.stand.lerp(this.close, smooth(t / (float) L_GRIP));
		}
		Vec3 at;
		int spears = 0;
		for (int s : L_SPEARS) {
			if (t >= s) {
				spears++;
			}
		}
		if (t < L_RIP) {
			at = this.held.add(0, 0.1 * spears, 0);
		} else if (t < L_SLAM) {
			// Flung back into it.
			float k = smooth((t - L_RIP) / (float) (L_SLAM - L_RIP));
			at = this.held.add(0, 0.3, 0).lerp(inside, k);
		} else if (t < L_OPEN) {
			at = inside;
		} else {
			// Slumping out when the doors come open.
			float k = smooth((t - L_OPEN) / (float) (L_OUT - L_OPEN - 2));
			at = inside.lerp(this.door.add(this.out().scale(0.35)), k);
		}
		this.grab.placeVictim(at.x, at.y, at.z, this.facing.toYRot());
		this.victim.setDeltaMovement(Vec3.ZERO);
		Vec3 chest = at.add(0, 1.15, 0);
		if (t == 1) {
			level.playSound(null, chest.x, chest.y, chest.z, HwSounds.VECNA_GRIP_PULL, SoundSource.HOSTILE, 1.2f, 0.7f);
			Voices.play(this.victim, Voices.Kind.SCREAM);
		}
		if (t == L_GRIP) {
			level.playSound(null, chest.x, chest.y + 0.5, chest.z, HwSounds.VECNA_VINE_SLITHER, SoundSource.HOSTILE, 1.3f, 1.1f);
		}
		for (int s : L_SPEARS) {
			if (t == s) {
				if (s == L_SPEARS[0]) {
					this.burst(0.8f);
				}
				this.impale(chest, s == L_SPEARS[0] ? 1f : 0.7f);
				level.playSound(null, this.door.x, this.door.y + 1.2, this.door.z, SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR, SoundSource.HOSTILE, 1.3f, 0.6f + 0.1f * spears);
				Voices.play(this.victim, Voices.Kind.HURT);
			}
		}
		if (t == 42 || t == 50) {
			level.playSound(null, chest.x, chest.y, chest.z, HwSounds.VECNA_BONE_CRACK, SoundSource.HOSTILE, 1.5f, 0.85f);
			this.shake(this.victim, 6, 0.5f);
		}
		if (t > L_SPEARS[0] && t < L_RIP && t % 3 == 0) {
			level.sendParticles(HwParticles.BLOOD_DRIP, chest.x, chest.y - 0.3, chest.z, 1, 0.1, 0.1, 0.1, 0);
		}
		if (t == L_RIP) {
			LockerBlock.setOpen(level, this.locker, true);
			this.rip(chest);
			GrabSession.anim(this.victim, HwNet.Anim.LIMP);
		}
		if (t == L_SLAM) {
			LockerBlock.setOpen(level, this.locker, false);
			Vec3 c = Vec3.atCenterOf(this.locker).add(0, 0.5, 0);
			level.playSound(null, c.x, c.y, c.z, HwSounds.LOCKER_SLAM, SoundSource.HOSTILE, 2.4f, 0.55f);
			this.victim.setInvisible(true);
			this.shake(this.vecna, 6, 0.35f);
			this.shake(this.victim, 10, 0.7f);
		}
		if (t > L_SLAM + 2 && t < L_OPEN && t % 2 == 0) {
			// Blood running out from under the doors.
			Vec3 side = this.out().cross(new Vec3(0, 1, 0));
			double s = (level.getRandom().nextDouble() - 0.5) * 0.7;
			Vec3 seep = this.door.add(side.scale(s)).add(this.out().scale(0.08)).add(0, 0.06, 0);
			level.sendParticles(HwParticles.BLOOD_DRIP, seep.x, seep.y, seep.z, 0, this.out().x * 0.03, 0, this.out().z * 0.03, 1);
		}
		if (t == 66) {
			Blood.pool(level, this.door.add(this.out().scale(0.45)));
		}
		if (t == 70) {
			Vec3 c = Vec3.atCenterOf(this.locker);
			level.playSound(null, c.x, c.y, c.z, SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR, SoundSource.HOSTILE, 0.6f, 0.5f);
		}
		if (t == L_OPEN) {
			// The doors creak open on their own.
			LockerBlock.setOpen(level, this.locker, true);
			Vec3 c = Vec3.atCenterOf(this.locker).add(0, 0.5, 0);
			level.playSound(null, c.x, c.y, c.z, HwSounds.LOCKER_CLOSE, SoundSource.HOSTILE, 1.2f, 0.45f);
			this.victim.setInvisible(false);
		}
		if (t == L_OUT - 4) {
			GrabSession.anim(this.victim, HwNet.Anim.DEAD);
		}
		if (t == L_OUT) {
			level.playSound(null, at.x, at.y, at.z, HwSounds.VECNA_BODY_DROP, SoundSource.HOSTILE, 1.4f, 0.9f);
			level.playSound(null, at.x, at.y, at.z, SoundEvents.PLAYER_BIG_FALL, SoundSource.PLAYERS, 1.2f, 0.8f);
		}
		return t >= L_KILL;
	}

	// ---------------------------------------------------------------- shared moments

	/** The vines burst out of his back. */
	private void burst(float volume) {
		ServerLevel level = this.level();
		Vec3 back = this.vecnaSpot.add(this.ahead.scale(-0.25)).add(0, 2.2, 0);
		level.playSound(null, back.x, back.y, back.z, HwSounds.VECNA_VINE_BURST, SoundSource.HOSTILE, 1.8f * volume, 0.9f);
		level.sendParticles(HwParticles.VECNA_SMOKE, back.x, back.y, back.z, 5, 0.3, 0.3, 0.3, 0.03);
		level.sendParticles(HwParticles.BLOOD, back.x, back.y, back.z, 8, 0.2, 0.2, 0.2, 0.15);
	}

	/** Run through: a gout of blood out of their back, the jolt. */
	private void impale(Vec3 chest, float strength) {
		ServerLevel level = this.level();
		level.playSound(null, chest.x, chest.y, chest.z, HwSounds.VECNA_IMPALE, SoundSource.HOSTILE, 1.8f * strength, 0.9f + level.getRandom().nextFloat() * 0.15f);
		MatchManager.bleed(level, this.victim, (int) (16 * strength));
		Vec3 through = this.ahead;
		for (int i = 0; i < 14 * strength; i++) {
			Vec3 v = through.scale(0.25 + level.getRandom().nextDouble() * 0.25)
				.add((level.getRandom().nextDouble() - 0.5) * 0.2, level.getRandom().nextDouble() * 0.15, (level.getRandom().nextDouble() - 0.5) * 0.2);
			level.sendParticles(HwParticles.BLOOD, chest.x + through.x * 0.2, chest.y, chest.z + through.z * 0.2, 0, v.x, v.y, v.z, 1);
		}
		this.shake(this.victim, 10, 0.8f * strength);
		this.shake(this.vecna, 6, 0.4f * strength);
	}

	/** The neck broken: the crack, the choking cut short. */
	private void snapNeck(Vec3 head) {
		ServerLevel level = this.level();
		level.playSound(null, head.x, head.y, head.z, HwSounds.VECNA_NECK_SNAP, SoundSource.HOSTILE, 2.2f, 0.95f);
		level.playSound(null, head.x, head.y, head.z, HwSounds.VECNA_BONE_CRACK, SoundSource.HOSTILE, 1.4f, 0.7f);
		level.playSound(null, head.x, head.y, head.z, HwSounds.VECNA_KILL_CHIME, SoundSource.HOSTILE, 1.4f, 1f);
		this.grab.silence();
		this.shake(this.victim, 8, 0.9f);
		this.shake(this.vecna, 5, 0.4f);
	}

	/** The vines torn out of them. */
	private void rip(Vec3 chest) {
		ServerLevel level = this.level();
		level.playSound(null, chest.x, chest.y, chest.z, HwSounds.VECNA_VINE_BURST, SoundSource.HOSTILE, 1.5f, 1.3f);
		level.playSound(null, chest.x, chest.y, chest.z, HwSounds.VECNA_IMPALE, SoundSource.HOSTILE, 1.0f, 0.7f);
		MatchManager.bleed(level, this.victim, 40);
	}

	/** The body hits the ground. */
	private void land(Vec3 at) {
		ServerLevel level = this.level();
		level.playSound(null, at.x, at.y, at.z, HwSounds.VECNA_BODY_DROP, SoundSource.HOSTILE, 1.5f, 0.9f);
		level.playSound(null, at.x, at.y, at.z, SoundEvents.PLAYER_BIG_FALL, SoundSource.PLAYERS, 1.3f, 0.75f);
		level.sendParticles(HwParticles.VECNA_ASH, at.x, at.y + 0.1, at.z, 8, 0.5, 0.05, 0.5, 0.03);
		GrabSession.anim(this.victim, HwNet.Anim.DEAD);
		this.shake(this.vecna, 4, 0.3f);
	}

	private void shake(LivingEntity who, int ticks, float strength) {
		if (who instanceof ServerPlayer p && !Bots.isBot(p)) {
			ServerPlayNetworking.send(p, new HwNet.Quake(ticks, strength));
		}
	}

	// ---------------------------------------------------------------- the camera

	/**
	 * The two of them see it from the side for {@code ticks}: looking at {@code look} from {@code dist} away (and
	 * {@code rise} above it), drifting {@code orbit} degrees round it; from whichever side has the room.
	 */
	private void camera(int ticks, Vec3 look, double dist, double rise, float orbit) {
		ServerLevel level = this.level();
		Vec3 side = this.ahead.cross(new Vec3(0, 1, 0));
		Vec3 best = null;
		float bestOrbit = 0;
		double bestScore = -1;
		// Side on, a little in front of or behind the line between them; each way round.
		for (Vec3 dir : new Vec3[]{side.add(this.ahead.scale(0.35)), side.scale(-1).add(this.ahead.scale(0.35)), side.subtract(this.ahead.scale(0.45)),
			side.scale(-1).subtract(this.ahead.scale(0.45)), this.ahead.scale(1.0)}) {
			Vec3 d = dir.normalize();
			for (int sign : new int[]{1, -1}) {
				double score = Double.MAX_VALUE;
				Vec3 first = null;
				for (float f : new float[]{0f, 0.5f, 1f}) {
					float a = orbit * sign * f * Mth.DEG_TO_RAD;
					Vec3 r = new Vec3(d.x * Math.cos(a) - d.z * Math.sin(a), 0, d.x * Math.sin(a) + d.z * Math.cos(a));
					Vec3 want = look.add(r.scale(dist)).add(0, rise, 0);
					Vec3 got = this.clip(level, look, want);
					if (first == null) {
						first = got;
					}
					score = Math.min(score, got.distanceTo(look));
				}
				if (score > bestScore + 0.25) {
					bestScore = score;
					best = first;
					bestOrbit = orbit * sign;
				}
			}
		}
		if (best == null) {
			return;
		}
		// Not so close that it only shows a wall: if there is no room anywhere, the orbit stops.
		HwNet.Cutscene cam = new HwNet.Cutscene(Cutscenes.KILLCAM, ticks, best, look, new Vec3(bestScore > dist * 0.7 ? bestOrbit : 0, 0, 0.1));
		for (LivingEntity who : new LivingEntity[]{this.vecna, this.victim}) {
			if (who instanceof ServerPlayer p && !Bots.isBot(p)) {
				ServerPlayNetworking.send(p, cam);
			}
		}
	}

	private Vec3 clip(ServerLevel level, Vec3 from, Vec3 to) {
		HitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, this.vecna));
		if (hit.getType() == HitResult.Type.MISS) {
			return to;
		}
		Vec3 back = from.subtract(hit.getLocation());
		return hit.getLocation().add(back.lengthSqr() < 1e-4 ? Vec3.ZERO : back.normalize().scale(0.3));
	}
}
