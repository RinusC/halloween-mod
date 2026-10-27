package dev.halloween.game;

import dev.halloween.HwParticles;
import dev.halloween.HwSounds;
import dev.halloween.net.HwNet;
import java.util.Set;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Michael's kills once he has won the struggle (in place of three blows of the knife; from {@link GrabSession}). Seen
 * from a slow camera off to the side by him and by them, the rest see it where it happens. The server moves the
 * bodies, leaves his knife in the wall, makes the sounds and the blood; his moves and theirs are client.MichaelKillScene's,
 * from the {@link HwNet.MichaelKill} everyone near gets.
 * <ul>
 * <li>{@link #PIN} (Halloween, 1978: Bob): he walks them back into a wall by the throat, lifts them up it and drives
 * the knife through them into it, and leaves it there: they hang pinned, feet off the floor, kicking weaker and weaker.
 * He steps back and looks at them, his head slowly tilting. Then he pulls the knife out and they slide down the wall
 * and are left sitting against it, slumped (the body stays so: a mannequin in {@link Pose#FALL_FLYING}, which
 * client.MichaelKillScene draws sitting). Only where there is a wall at hand.
 * <li>{@link #LIFT}: the knife up into the belly, and he lifts them off their feet on it at arm's length, up over his
 * head; holds them there, the head tilting as he looks up at them; then flings the body off the blade: it turns over in
 * the air and comes down along the way it flew, face down.
 * </ul>
 */
public final class MichaelKill {
	public static final int NONE = 0;
	public static final int PIN = 1;
	public static final int LIFT = 2;

	/**
	 * Pin: walked back into the wall until P_SLAM, lifted, the knife in at P_STAB; let go at P_LEAVE, he steps back and
	 * looks, the knife out at P_PULL; down the wall onto the floor at P_DOWN, the knees giving, sitting against it.
	 */
	public static final int PIN_TICKS = 104;
	public static final int P_SLAM = 14;
	public static final int P_STAB = 26;
	public static final int P_LEAVE = 36;
	public static final int P_BACK = 48;
	public static final int P_RETURN = 70;
	public static final int P_PULL = 84;
	public static final int P_DOWN = 90;
	private static final int P_KILL = 100;
	/** How high they are lifted against the wall (their feet off the floor). */
	public static final double P_HIGH = 0.62;
	/** Sitting against the wall: how far the body sinks below where its feet were (the legs out along the floor). */
	public static final float SIT = 0.6f;

	/** Lift: the knife in at L_STAB, lifted from L_RAISE to L_HIGH, held, flung off at L_FLING, down at L_LAND. */
	public static final int LIFT_TICKS = 92;
	public static final int L_COCK = 8;
	public static final int L_STAB = 11;
	public static final int L_RAISE = 22;
	public static final int L_HIGH = 42;
	public static final int L_FLING = 60;
	public static final int L_LAND = 68;
	private static final int L_KILL = 88;
	/** His shoulder above his feet and the knife's point from it (blocks, as his model is drawn). */
	public static final double SHOULDER = 1.5;
	public static final double REACH = 1.05;
	/** His knife shoulder off to the side of his middle (blocks). */
	public static final double SHOULDER_SIDE = 0.33;
	/** Where in them the knife goes in: this high above their feet. */
	public static final double BELLY = 0.95;

	private final GrabSession grab;
	private final ServerPlayer michael;
	private final LivingEntity victim;
	private final int kind;
	private int t;
	private Vec3 spot;
	private final Vec3 from;
	private final double floor;
	/** From him towards them (level), and his yaw that way. */
	private Vec3 ahead;
	private float yaw;
	/** Pin: where they hang against the wall, where he stands at them and where he steps back to. */
	private Vec3 pinned;
	private Vec3 close;
	private Vec3 back;
	/**
	 * Lift: how high his arm goes (radians from hanging; a ceiling can keep it lower); where the body lands, and which
	 * way it faces then (along the way it flew).
	 */
	private float raise = 2.3f;
	private Vec3 landed;
	private float flyYaw;
	private Vec3 flungFrom;
	/** His knife while it is in the wall (drawn there by the clients). */
	private ItemStack knife = ItemStack.EMPTY;

	/** For tests: the next kill is this one (0: at random). */
	public static int forceKind;

	private MichaelKill(GrabSession grab, ServerPlayer michael, LivingEntity victim, int kind) {
		this.grab = grab;
		this.michael = michael;
		this.victim = victim;
		this.kind = kind;
		this.spot = michael.position();
		this.from = victim.position();
		this.floor = this.from.y;
		Vec3 d = this.from.subtract(this.spot).multiply(1, 0, 1);
		this.ahead = d.lengthSqr() < 1e-4 ? Vec3.directionFromRotation(0, michael.getYRot()) : d.normalize();
		this.yaw = yawOf(this.ahead);
	}

	private static float yawOf(Vec3 d) {
		return (float) Math.toDegrees(Math.atan2(-d.x, d.z));
	}

	/** His kill after the struggle: pinned to a wall (where there is one) or lifted on the knife, at random. */
	static MichaelKill start(GrabSession grab, ServerPlayer michael, LivingEntity victim) {
		MichaelKill pin = new MichaelKill(grab, michael, victim, PIN);
		boolean wall = pin.findWall();
		boolean wantPin = forceKind == PIN || forceKind != LIFT && michael.getRandom().nextBoolean();
		MichaelKill k = wall && wantPin ? pin : new MichaelKill(grab, michael, victim, LIFT);
		if (k.kind == LIFT) {
			k.headroom();
			k.landed = k.landing();
			Vec3 fly = k.landed.subtract(k.victimOnKnife(k.raise)).multiply(1, 0, 1);
			k.flyYaw = fly.lengthSqr() < 1e-4 ? k.yaw + 180f : yawOf(fly);
		}
		k.begin();
		return k;
	}

	private ServerLevel level() {
		return this.michael.level();
	}

	// ---------------------------------------------------------------- finding room

	/**
	 * A wall to pin them to: a flat face of whole blocks at their chest and head height, a step or two behind them
	 * (away from him first, then to the sides), nothing in between, room for them up against it and for him before them.
	 */
	private boolean findWall() {
		ServerLevel level = this.level();
		BlockPos feet = BlockPos.containing(this.from.x, this.floor + 0.2, this.from.z);
		Direction best = null;
		double bestScore = -9;
		Vec3 bestPin = null;
		for (Direction dir : Direction.Plane.HORIZONTAL) {
			Vec3 d = new Vec3(dir.getStepX(), 0, dir.getStepZ());
			double score = d.dot(this.ahead);
			if (score < -0.2 || score <= bestScore) {
				continue;
			}
			for (int k = 1; k <= 2; k++) {
				BlockPos w = feet.relative(dir, k);
				if (!full(level, w.above()) || !full(level, w.above(2))) {
					if (!free(level, w) || !free(level, w.above()) || !free(level, w.above(2))) {
						break;
					}
					continue;
				}
				// The wall's face, and them up against it (on the line through where they stand).
				double face = dir.getAxis() == Direction.Axis.X ? (dir.getStepX() > 0 ? w.getX() : w.getX() + 1) : (dir.getStepZ() > 0 ? w.getZ() : w.getZ() + 1);
				Vec3 pin = dir.getAxis() == Direction.Axis.X ? new Vec3(face - dir.getStepX() * 0.32, this.floor, this.from.z)
					: new Vec3(this.from.x, this.floor, face - dir.getStepZ() * 0.32);
				AABB body = new AABB(pin.x - 0.28, pin.y + P_HIGH, pin.z - 0.28, pin.x + 0.28, pin.y + P_HIGH + 1.8, pin.z + 0.28);
				Vec3 stand = pin.subtract(d.scale(0.95));
				if (level.noCollision(body) && level.noCollision(this.michael, this.michael.getBoundingBox().move(stand.subtract(this.michael.position())))) {
					best = dir;
					bestScore = score;
					bestPin = pin;
				}
				break;
			}
		}
		if (best == null) {
			return false;
		}
		Vec3 d = new Vec3(best.getStepX(), 0, best.getStepZ());
		this.pinned = bestPin;
		this.ahead = d;
		this.yaw = yawOf(d);
		this.close = bestPin.subtract(d.scale(0.95));
		this.back = bestPin.subtract(d.scale(1.9));
		if (!level.noCollision(this.michael, this.michael.getBoundingBox().move(this.back.subtract(this.michael.position())))) {
			this.back = bestPin.subtract(d.scale(1.45));
			if (!level.noCollision(this.michael, this.michael.getBoundingBox().move(this.back.subtract(this.michael.position())))) {
				this.back = this.close;
			}
		}
		return true;
	}

	private static boolean full(ServerLevel level, BlockPos p) {
		return level.getBlockState(p).isCollisionShapeFullBlock(level, p) && !level.getBlockState(p).hasBlockEntity();
	}

	private static boolean free(ServerLevel level, BlockPos p) {
		return level.getBlockState(p).getCollisionShape(level, p).isEmpty();
	}

	/** Lift: how high there is room to raise them (a ceiling over them keeps his arm lower). */
	private void headroom() {
		ServerLevel level = this.level();
		for (float a = 2.3f; a >= 1.3f; a -= 0.1f) {
			Vec3 at = this.victimOnKnife(a);
			AABB body = new AABB(at.x - 0.28, at.y, at.z - 0.28, at.x + 0.28, at.y + 1.85, at.z + 0.28);
			if (level.noCollision(body)) {
				this.raise = a;
				return;
			}
		}
		this.raise = 1.3f;
	}

	/** Where they are when his knife arm is {@code a} radians up from hanging: on the point of it, by the belly. */
	private Vec3 victimOnKnife(double a) {
		// Before his knife shoulder, not his middle: the blade goes into the middle of them.
		Vec3 shoulder = this.spot.add(this.ahead.cross(new Vec3(0, 1, 0)).scale(-SHOULDER_SIDE));
		Vec3 tip = shoulder.add(this.ahead.scale(REACH * Math.sin(a))).add(0, SHOULDER - REACH * Math.cos(a), 0);
		return new Vec3(tip.x + this.ahead.x * 0.12, Math.max(this.floor, tip.y - BELLY), tip.z + this.ahead.z * 0.12);
	}

	/** How far up from hanging his knife arm is at tick {@code t} of the lift (the same on the clients). */
	public static float liftArm(float t, float raise) {
		if (t < L_COCK) {
			return Mth.lerp(smooth(t / L_COCK), 0.1f, -0.55f);
		}
		if (t < L_STAB) {
			return Mth.lerp(smooth((t - L_COCK) / (L_STAB - L_COCK)), -0.55f, 1.2f);
		}
		if (t < L_RAISE) {
			// A second shove, deeper.
			return 1.2f + 0.1f * (float) Math.sin(Mth.clamp((t - 16) / 5f, 0f, 1f) * Math.PI);
		}
		if (t < L_HIGH) {
			return Mth.lerp(smooth((t - L_RAISE) / (L_HIGH - L_RAISE)), 1.2f, raise);
		}
		if (t < L_FLING) {
			return raise + 0.03f * (float) Math.sin(t * 1.3f);
		}
		if (t < L_FLING + 6) {
			return Mth.lerp(smooth((t - L_FLING) / 6f), raise, 0.9f);
		}
		return Mth.lerp(smooth((t - L_FLING - 6) / 12f), 0.9f, 0.15f);
	}

	/** Lift: the body's yaw at tick {@code t}, turning in the air from facing him ({@code from}) to along its flight ({@code to}). */
	public static float flightYaw(float t, float from, float to) {
		return from + Mth.wrapDegrees(to - from) * smooth((t - L_FLING) / (L_LAND - L_FLING));
	}

	private static float smooth(float x) {
		x = Mth.clamp(x, 0f, 1f);
		return x * x * (3 - 2 * x);
	}

	// ---------------------------------------------------------------- playing it

	private void begin() {
		ServerLevel level = this.level();
		int ticks = this.kind == PIN ? PIN_TICKS : LIFT_TICKS;
		Vec3 a = this.kind == PIN ? this.pinned : new Vec3(this.raise, this.flyYaw, 0);
		send(new HwNet.MichaelKill(this.kind, this.michael.getId(), this.victim.getId(), level.getGameTime(), ticks, this.yaw, a, this.spot), this.victim);
		GrabSession.anim(this.michael, HwNet.Anim.NONE);
		GrabSession.anim(this.victim, HwNet.Anim.NONE);
		if (this.kind == PIN) {
			Vec3 look = this.pinned.lerp(this.close, 0.5).add(0, 1.35, 0);
			VecnaKill.sideCamera(this.michael, this.victim, this.ahead, ticks, look, 6.0, 0.5, 20f);
		} else {
			Vec3 look = this.spot.add(this.ahead.scale(0.5)).add(0, 1.4 + (this.raise - 1.2) * 0.45, 0);
			VecnaKill.sideCamera(this.michael, this.victim, this.ahead, ticks, look, 6.6, -0.35, 30f);
		}
		level.playSound(null, this.michael.getX(), this.michael.getEyeY(), this.michael.getZ(), HwSounds.SHAPE_BREATH, SoundSource.HOSTILE, 1.6f, 0.8f);
	}

	private static void send(HwNet.MichaelKill payload, LivingEntity near) {
		if (!(near.level() instanceof ServerLevel level)) {
			return;
		}
		for (ServerPlayer p : level.players()) {
			if (!Bots.isBot(p) && p.distanceToSqr(near) < 160 * 160) {
				ServerPlayNetworking.send(p, payload);
			}
		}
	}

	/** Every tick of the kill; true once they are dead. */
	boolean tick() {
		int t = ++this.t;
		this.over = this.kind == PIN ? this.tickPin(t) : this.tickLift(t);
		return this.over;
	}

	/** Played out to the end (nothing to break off). */
	private boolean over;

	/** After the kill, on the body left in their place (a mannequin): pinned, it stays sitting against the wall. */
	void killed() {
		if (this.kind != PIN) {
			return;
		}
		Vec3 at = this.pinned.add(this.ahead.scale(0.08));
		for (Mannequin body : this.level().getEntitiesOfClass(Mannequin.class, new AABB(at, at).inflate(0.6),
			m -> m.entityTags().contains(MatchManager.CORPSE_TAG))) {
			body.setPose(Pose.FALL_FLYING);
		}
	}

	/** Broken off (the match over): the knife back in his hand, the clients told. */
	void stop() {
		this.takeKnife();
		if (this.over) {
			return;
		}
		send(new HwNet.MichaelKill(NONE, this.michael.getId(), this.victim.getId(), this.level().getGameTime(), 0, this.yaw, Vec3.ZERO, Vec3.ZERO), this.victim);
	}

	private void place(Vec3 at) {
		if (this.michael.position().distanceToSqr(at) > 0.0004) {
			dev.halloween.Vr.place(this.michael, this.level(), at.x, at.y, at.z, Set.of(Relative.X_ROT, Relative.Y_ROT), 0f, 0f);
		}
		this.michael.setDeltaMovement(Vec3.ZERO);
		this.michael.setYBodyRot(this.yaw);
	}

	// ---------------------------------------------------------------- pinned to the wall

	private boolean tickPin(int t) {
		ServerLevel level = this.level();
		// Him: walking them back into the wall, at them; a step back to look; in again for the knife.
		Vec3 me;
		if (t <= P_SLAM) {
			me = this.spot.lerp(this.close, smooth(t / (float) P_SLAM));
		} else if (t < P_LEAVE + 4) {
			me = this.close;
		} else if (t < P_BACK) {
			me = this.close.lerp(this.back, smooth((t - P_LEAVE - 4) / (float) (P_BACK - P_LEAVE - 4)));
		} else if (t < P_RETURN) {
			me = this.back;
		} else if (t < P_PULL + 3) {
			me = this.back.lerp(this.close, smooth((t - P_RETURN) / 8f));
		} else {
			// A step back as they come down, to look at them sitting there.
			me = this.close.lerp(this.back, 0.5 * smooth((t - P_PULL - 3) / 8f));
		}
		this.place(me);
		// Them: backed into the wall, up it by the throat, hanging on the knife, sliding down when it comes out.
		Vec3 at;
		if (t <= P_SLAM) {
			float k = t / (float) P_SLAM;
			at = this.from.lerp(this.pinned, k * k).add(0, 0.12 * k, 0);
		} else if (t < P_STAB) {
			at = this.pinned.add(0, Mth.lerp(smooth((t - P_SLAM - 2) / 8f), 0.12, P_HIGH), 0);
		} else if (t < P_PULL) {
			at = this.pinned.add(0, P_HIGH + (t < P_STAB + 4 ? 0.06 * (1 - (t - P_STAB) / 4.0) : 0), 0);
		} else {
			// Down the wall, faster and faster, the back against it.
			float k = Mth.clamp((t - P_PULL) / (float) (P_DOWN - P_PULL), 0f, 1f);
			at = this.pinned.add(this.ahead.scale(0.08 * k)).add(0, P_HIGH * (1 - k * k), 0);
		}
		this.grab.placeVictim(at.x, at.y, at.z, this.yaw + 180f);
		this.victim.setDeltaMovement(Vec3.ZERO);
		Vec3 chest = at.add(0, 1.2, 0);
		if (t == 1) {
			Voices.play(this.victim, Voices.Kind.SCREAM);
		}
		if (t == P_SLAM) {
			// Slammed into it.
			level.playSound(null, chest.x, chest.y, chest.z, HwSounds.OBSTACLE_THUD, SoundSource.HOSTILE, 1.6f, 0.7f);
			level.playSound(null, chest.x, chest.y, chest.z, SoundEvents.PLAYER_BIG_FALL, SoundSource.PLAYERS, 1.2f, 0.7f);
			this.shake(this.victim, 10, 0.7f);
			this.shake(this.michael, 5, 0.3f);
		}
		if (t == P_STAB) {
			// Through them and into the wall behind.
			level.playSound(null, chest.x, chest.y, chest.z, HwSounds.STAB, SoundSource.HOSTILE, 1.8f, 0.75f);
			level.playSound(null, chest.x, chest.y, chest.z, HwSounds.VECNA_IMPALE, SoundSource.HOSTILE, 1.4f, 1.1f);
			level.playSound(null, chest.x, chest.y, chest.z, SoundEvents.TRIDENT_HIT, SoundSource.HOSTILE, 1.4f, 0.6f);
			this.grab.silence();
			Voices.play(this.victim, Voices.Kind.HURT);
			this.spray(chest, this.ahead.scale(-1), 26);
			this.shake(this.victim, 12, 0.9f);
			this.shake(this.michael, 8, 0.5f);
		}
		if (t == P_LEAVE) {
			// He lets go of the knife: it stays in them, in the wall.
			this.leaveKnife();
			level.playSound(null, this.michael.getX(), this.michael.getEyeY(), this.michael.getZ(), HwSounds.SHAPE_BREATH, SoundSource.HOSTILE, 1.4f, 0.75f);
		}
		if (t == 44 || t == 56) {
			Voices.play(this.victim, Voices.Kind.HURT);
		}
		if (t > P_STAB && t < P_PULL && t % 3 == 0) {
			// Blood running down them and the wall, dripping off their feet.
			level.sendParticles(HwParticles.BLOOD_DRIP, chest.x, chest.y - 0.2, chest.z, 1, 0.08, 0.05, 0.08, 0);
			if (t % 6 == 0) {
				level.sendParticles(HwParticles.BLOOD_DRIP, at.x, at.y + 0.05, at.z, 1, 0.1, 0, 0.1, 0);
			}
		}
		if (t == 58) {
			Blood.pool(level, this.pinned.add(this.ahead.scale(-0.2)));
		}
		if (t == P_BACK + 2) {
			// The look: the head tilts.
			level.playSound(null, this.michael.getX(), this.michael.getEyeY(), this.michael.getZ(), HwSounds.STINGER, SoundSource.HOSTILE, 1.3f, 1f);
		}
		if (t == P_PULL) {
			this.takeKnife();
			level.playSound(null, chest.x, chest.y, chest.z, HwSounds.STAB, SoundSource.HOSTILE, 1.2f, 1.4f);
			level.playSound(null, chest.x, chest.y, chest.z, SoundEvents.TRIDENT_HIT_GROUND, SoundSource.HOSTILE, 1.0f, 0.7f);
			this.spray(chest, this.ahead.scale(-1), 14);
		}
		if (t == P_DOWN) {
			// On the floor; the knees give and they sit down against the wall.
			level.playSound(null, at.x, at.y, at.z, HwSounds.VECNA_BODY_DROP, SoundSource.HOSTILE, 1.3f, 1f);
		}
		if (t == P_DOWN + 4) {
			level.playSound(null, at.x, at.y, at.z, SoundEvents.PLAYER_SMALL_FALL, SoundSource.PLAYERS, 1.0f, 0.7f);
			level.sendParticles(HwParticles.BLOOD_DRIP, at.x, at.y + 0.6, at.z, 2, 0.1, 0.05, 0.1, 0);
		}
		return t >= P_KILL;
	}

	/** His knife left in them, through into the wall (the clients draw it there, in their chest: client.MichaelKillScene). */
	private void leaveKnife() {
		this.knife = this.michael.getMainHandItem().copy();
		this.michael.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
	}

	/** The knife back in his hand. */
	private void takeKnife() {
		if (!this.knife.isEmpty()) {
			if (this.michael.getMainHandItem().isEmpty()) {
				this.michael.setItemInHand(InteractionHand.MAIN_HAND, this.knife);
			} else {
				this.michael.getInventory().add(this.knife);
			}
			this.knife = ItemStack.EMPTY;
		}
	}

	// ---------------------------------------------------------------- lifted on the knife

	private boolean tickLift(int t) {
		ServerLevel level = this.level();
		this.place(this.spot);
		float arm = liftArm(t, this.raise);
		Vec3 at;
		if (t < L_STAB) {
			at = this.from;
		} else if (t < L_FLING) {
			at = this.from.lerp(this.victimOnKnife(arm), Math.min(1f, (t - L_STAB + 1) / 3f));
		} else if (t <= L_LAND) {
			// Flung off the blade to his knife side, down onto the floor.
			if (this.flungFrom == null) {
				this.flungFrom = this.victim.position();
			}
			float k = (t - L_FLING) / (float) (L_LAND - L_FLING);
			Vec3 across = this.flungFrom.lerp(this.landed, k);
			at = new Vec3(across.x, Math.max(this.floor, Mth.lerp(k * k, this.flungFrom.y, this.floor) + Math.sin(k * Math.PI) * 0.35), across.z);
		} else {
			at = this.landed;
		}
		// Turning over in the air to come down along the way they fly.
		this.grab.placeVictim(at.x, at.y, at.z, flightYaw(t, this.yaw + 180f, this.flyYaw));
		this.victim.setDeltaMovement(Vec3.ZERO);
		Vec3 belly = at.add(0, BELLY, 0);
		if (t == 1) {
			Voices.play(this.victim, Voices.Kind.SCREAM);
		}
		if (t == L_STAB) {
			level.playSound(null, belly.x, belly.y, belly.z, HwSounds.STAB, SoundSource.HOSTILE, 1.8f, 0.8f);
			level.playSound(null, belly.x, belly.y, belly.z, HwSounds.VECNA_IMPALE, SoundSource.HOSTILE, 1.4f, 1.15f);
			this.grab.silence();
			Voices.play(this.victim, Voices.Kind.HURT);
			this.spray(belly, this.ahead, 18);
			this.shake(this.victim, 12, 0.9f);
			this.shake(this.michael, 6, 0.4f);
		}
		if (t == 18) {
			level.playSound(null, belly.x, belly.y, belly.z, HwSounds.STAB, SoundSource.HOSTILE, 1.3f, 0.65f);
			this.spray(belly, this.ahead, 10);
		}
		if (t == L_RAISE + 2) {
			level.playSound(null, belly.x, belly.y, belly.z, HwSounds.SHAPE_BREATH, SoundSource.HOSTILE, 1.4f, 0.7f);
			Voices.play(this.victim, Voices.Kind.SCREAM);
		}
		if (t > L_STAB && t < L_FLING && t % 2 == 0) {
			// Down the blade, off their feet.
			level.sendParticles(HwParticles.BLOOD_DRIP, belly.x - this.ahead.x * 0.15, belly.y - 0.1, belly.z - this.ahead.z * 0.15, 1, 0.06, 0.02, 0.06, 0);
			if (t % 6 == 0) {
				level.sendParticles(HwParticles.BLOOD_DRIP, at.x, at.y + 0.05, at.z, 1, 0.1, 0, 0.1, 0);
			}
		}
		if (t == L_HIGH + 4) {
			level.playSound(null, this.michael.getX(), this.michael.getEyeY(), this.michael.getZ(), HwSounds.STINGER, SoundSource.HOSTILE, 1.3f, 1f);
		}
		if (t == 50) {
			Blood.pool(level, this.spot.add(this.ahead.scale(0.8)));
		}
		if (t == L_FLING) {
			level.playSound(null, belly.x, belly.y, belly.z, HwSounds.SWING, SoundSource.HOSTILE, 1.4f, 0.7f);
			level.playSound(null, belly.x, belly.y, belly.z, HwSounds.STAB, SoundSource.HOSTILE, 1.0f, 1.5f);
			this.spray(belly, this.ahead.cross(new Vec3(0, 1, 0)).scale(-1), 14);
		}
		if (t == L_LAND) {
			// Down on the floor (only now: in the air they were turning over, see client.MichaelKillScene).
			GrabSession.anim(this.victim, HwNet.Anim.DEAD);
			level.playSound(null, at.x, at.y, at.z, HwSounds.VECNA_BODY_DROP, SoundSource.HOSTILE, 1.5f, 0.95f);
			level.playSound(null, at.x, at.y, at.z, SoundEvents.PLAYER_BIG_FALL, SoundSource.PLAYERS, 1.3f, 0.75f);
			this.shake(this.michael, 4, 0.3f);
		}
		if (t == 78) {
			// The blood flicked off the blade.
			Vec3 side = this.ahead.cross(new Vec3(0, 1, 0)).scale(-1);
			Vec3 blade = this.spot.add(side.scale(0.4)).add(this.ahead.scale(0.3)).add(0, 0.9, 0);
			for (int i = 0; i < 8; i++) {
				Vec3 v = side.scale(0.15).add(0, -0.05, 0).add((level.getRandom().nextDouble() - 0.5) * 0.1, 0, (level.getRandom().nextDouble() - 0.5) * 0.1);
				level.sendParticles(HwParticles.BLOOD, blade.x, blade.y, blade.z, 0, v.x, v.y, v.z, 1);
			}
			level.playSound(null, blade.x, blade.y, blade.z, HwSounds.SWING, SoundSource.HOSTILE, 0.8f, 1.4f);
		}
		return t >= L_KILL;
	}

	/** Where the body comes down: off to his knife side, or wherever a body fits. */
	private Vec3 landing() {
		ServerLevel level = this.level();
		Vec3 right = this.ahead.cross(new Vec3(0, 1, 0)).scale(-1);
		for (Vec3 d : new Vec3[]{right.scale(1.5).add(this.ahead.scale(0.7)), right.scale(-1.5).add(this.ahead.scale(0.7)), this.ahead.scale(1.7), right.scale(1.2)}) {
			Vec3 at = new Vec3(this.spot.x + d.x, this.floor, this.spot.z + d.z);
			AABB box = new AABB(at.x - 0.3, at.y + 0.05, at.z - 0.3, at.x + 0.3, at.y + 1.0, at.z + 0.3);
			BlockPos under = BlockPos.containing(at).below();
			if (level.noCollision(box) && !level.getBlockState(under).getCollisionShape(level, under).isEmpty()) {
				return at;
			}
		}
		return this.from;
	}

	// ---------------------------------------------------------------- shared

	/** Blood out of the wound, mostly {@code dir}-wards. */
	private void spray(Vec3 at, Vec3 dir, int count) {
		ServerLevel level = this.level();
		MatchManager.bleed(level, this.victim, count);
		for (int i = 0; i < count / 2; i++) {
			Vec3 v = dir.scale(0.12 + level.getRandom().nextDouble() * 0.18)
				.add((level.getRandom().nextDouble() - 0.5) * 0.15, level.getRandom().nextDouble() * 0.12, (level.getRandom().nextDouble() - 0.5) * 0.15);
			level.sendParticles(HwParticles.BLOOD, at.x, at.y, at.z, 0, v.x, v.y, v.z, 1);
		}
	}

	private void shake(LivingEntity who, int ticks, float strength) {
		if (who instanceof ServerPlayer p && !Bots.isBot(p)) {
			ServerPlayNetworking.send(p, new HwNet.Quake(ticks, strength));
		}
	}
}
