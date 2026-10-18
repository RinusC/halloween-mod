package dev.halloween.game;

import dev.halloween.HwConfig;
import dev.halloween.HwSounds;
import dev.halloween.net.HwNet;
import java.util.Set;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.sounds.SoundEvents;
import com.mojang.math.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import dev.halloween.HwParticles;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;

/**
 * The Shape holding a victim. Both sides play reaction checks at once:
 * the victim scores with its own hits and with the Shape's misses and escapes at
 * {@link HwConfig#victimPointsToEscape}; the Shape scores only with his own hits and executes at
 * {@link HwConfig#shapePointsToKillByStalk} (fewer for a well-stalked victim). Executions go through a window when the victim is fully stalked
 * and glass is in front, and turn bloody at a high Bloodthirst level.
 */
public final class GrabSession {
	private final ServerPlayer shape;
	private final ShapeState state;
	final LivingEntity victim;
	private final VictimState victimState;
	private final RandomSource random;

	private int victimPoints;
	private int shapePoints;
	private final Track victimTrack = new Track();
	private final Track shapeTrack = new Track();

	private boolean finishing;
	/** Where the Shape last stood with room for the victim in front: he is held back there at walls. */
	private Vec3 lastGood;
	private int finisherTick;
	private BlockPos windowPos;
	private boolean bloody;
	private boolean done;
	/** Whether the Shape's HUD was last told a window is at hand (he may walk up to one mid-grab). */
	private boolean windowShown;
	/** When the victim next chokes and gasps for air. */
	private long nextChoke;
	/** The choke take playing now (stopped when the grab ends); -1: none. */
	private int choking = -1;
	/** The way out has been animated already (an escape plays its own moves). */
	private boolean animated;

	/** One side's current reaction check. */
	private static final class Track {
		long roundStart;
		float zoneStart;
		float zoneWidth;
		boolean open;
		int simPressTick;
		boolean simSuccess;
		/** The victim's check: a different kind each round (see HwNet.Check). */
		final CheckRound round = new CheckRound();
	}

	/** The victim's kinds of check, one picked at random each round. */
	private static final int[] VICTIM_KINDS = {HwNet.Check.BAR, HwNet.Check.RING, HwNet.Check.DOUBLE, HwNet.Check.PULSE, HwNet.Check.MASH};

	/** How fast the victim's mark moves for this kind: a tapping round is timed by the whole round. */
	private int victimPeriod() {
		return this.victimTrack.round.kind == HwNet.Check.MASH ? this.cfg().victimRoundTicks : this.cfg().victimCursorPeriodTicks;
	}

	/** Ticks the victim has for this round: a closing circle shuts sooner. */
	private int victimLimit() {
		int lost = this.victimTrack.round.lostAfter(this.victimPeriod());
		return lost > 0 ? Math.min(lost, this.cfg().victimRoundTicks) : this.cfg().victimRoundTicks;
	}

	/** Which way the Demogorgon faces over the one he holds (fixed when he throws them down). */
	private float pinYaw;

	GrabSession(ServerPlayer shape, ShapeState state, LivingEntity victim, VictimState victimState) {
		this(shape, state, victim, victimState, null);
	}

	/** {@code locker}: pulled out of that wardrobe (a scene first, see tickScene), or null. */
	GrabSession(ServerPlayer shape, ShapeState state, LivingEntity victim, VictimState victimState, BlockPos locker) {
		this.shape = shape;
		this.state = state;
		this.victim = victim;
		this.victimState = victimState;
		this.random = shape.getRandom();
		victimState.grabbed = true;
		// The Demogorgon stays over them, turned the way he threw them down.
		this.pinYaw = shape.getYRot();
		if (locker != null && LockerBlock.is(this.level().getBlockState(locker))) {
			this.startScene(locker);
			return;
		}
		this.level().playSound(null, victim.getX(), victim.getY(), victim.getZ(), HwSounds.GRAB, SoundSource.HOSTILE, 1.2f, 1f);
		Voices.play(victim, Voices.Kind.SCREAM);
		this.nextChoke = this.level().getGameTime() + 14;
		if (victim instanceof ServerPlayer vp && Windows.isVaulting(vp)) {
			// Caught on the sill: dragged back off it into his hands (see pinVictim).
			this.yankFrom = victim.position();
			this.yankTick = 0;
			this.level().playSound(null, victim.getX(), victim.getY() + 1, victim.getZ(), net.minecraft.sounds.SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR, SoundSource.HOSTILE, 1.0f, 1.2f);
			AiStats.add(victim, "grabbed.vaulting");
		}
		if (Demogorgon.active()) {
			// The Demogorgon throws them down on their back and goes for them with his mouth.
			anim(shape, HwNet.Anim.DEVOUR);
			anim(victim, HwNet.Anim.PINNED);
		} else {
			anim(shape, HwNet.Anim.HOLD);
			anim(victim, HwNet.Anim.STRUGGLE);
		}
		this.startVictimRound(0);
		this.startShapeRound(0);
		this.sendState();
	}

	// ---------------------------------------------------------------- out of a wardrobe

	/** The wardrobe they were pulled out of (null: an ordinary grab), and which way its door faces. */
	private BlockPos lockerBase;
	private Direction lockerFacing;
	/** How far the opening scene is (no checks while it plays); -1 once the struggle is on. */
	private int sceneTick = -1;
	/** Where he stands at the wardrobe, and where they are held against its door. */
	private Vec3 shapeSpot;
	private Vec3 victimSpot;
	/** Michael's knife while it is stuck in the wardrobe door: the one from his hand, and the one in the door. */
	private ItemStack knife = ItemStack.EMPTY;
	private Display.ItemDisplay stuckKnife;

	/** Is this Michael's grab at a wardrobe (he stays put by it, then smashes their head against it)? */
	boolean atLocker() {
		return this.lockerBase != null && !Demogorgon.active();
	}

	/** Out of the wardrobe's door. */
	private Vec3 out() {
		return new Vec3(this.lockerFacing.getStepX(), 0, this.lockerFacing.getStepZ());
	}

	private void startScene(BlockPos locker) {
		ServerLevel level = this.level();
		this.lockerBase = locker.immutable();
		this.lockerFacing = level.getBlockState(locker).getValue(LockerBlock.FACING);
		Vec3 door = Vec3.atBottomCenterOf(locker).add(this.out().scale(0.5));
		this.victimSpot = door.add(this.out().scale(0.32));
		Vec3 spot = door.add(this.out().scale(Demogorgon.active() ? 1.25 : 1.1));
		spot = new Vec3(spot.x, this.shape.getY(), spot.z);
		this.shapeSpot = level.noCollision(this.shape, this.shape.getBoundingBox().move(spot.subtract(this.shape.position()))) ? spot : this.shape.position();
		this.pinYaw = this.lockerFacing.getOpposite().toYRot();
		this.shape.teleportTo(level, this.shapeSpot.x, this.shapeSpot.y, this.shapeSpot.z, Set.of(), this.pinYaw, 10f, false);
		this.sceneTick = 0;
		// Caught hiding: as good as stalked to the second level (harder to tear free).
		this.victimState.stalk = Math.max(this.victimState.stalk, 2f);
		Vec3 c = Vec3.atCenterOf(locker);
		if (Demogorgon.active()) {
			// Rearing up at it: a roar the whole street hears.
			anim(this.shape, HwNet.Anim.DEMO_LOCKER);
			level.playSound(null, c.x, c.y, c.z, SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 2.2f, 0.55f);
			level.playSound(null, c.x, c.y, c.z, SoundEvents.WARDEN_ROAR, SoundSource.HOSTILE, 1.6f, 0.8f);
			this.shake(this.victim, 24, 0.5f);
		} else {
			anim(this.shape, HwNet.Anim.LOCKER_STAB);
		}
		AiStats.add(this.victim, "locker.scene");
	}

	/**
	 * The scene at the wardrobe, before the struggle (no checks). Michael drives his knife into the door (it stays
	 * there) and throws the door open, his hand at their throat, their back against the wardrobe. The Demogorgon
	 * roars at it, drives a claw through it and through them, lifts them out on it and flings them down in front.
	 */
	private void tickScene() {
		ServerLevel level = this.level();
		int t = this.sceneTick++;
		Vec3 c = Vec3.atCenterOf(this.lockerBase);
		if (!Demogorgon.active()) {
			this.holdShape();
			if (t == 7) {
				// The knife into the door, and left there.
				this.knife = this.shape.getMainHandItem().copy();
				this.shape.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
				this.stickKnife();
				level.playSound(null, c.x, c.y + 0.4, c.z, HwSounds.STAB, SoundSource.HOSTILE, 1.3f, 0.7f);
				level.playSound(null, c.x, c.y + 0.4, c.z, SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR, SoundSource.HOSTILE, 1.2f, 1.1f);
				Voices.play(this.victim, Voices.Kind.SCREAM);
			} else if (t == 18) {
				this.pullOut();
				anim(this.shape, HwNet.Anim.LOCKER_HOLD);
				anim(this.victim, HwNet.Anim.STRUGGLE);
			}
			if (t >= 18) {
				this.placeVictim(this.victimSpot.x, this.victimSpot.y, this.victimSpot.z, this.lockerFacing.toYRot());
				this.victim.setDeltaMovement(Vec3.ZERO);
			}
			if (t >= 26) {
				this.beginStruggle();
			}
			return;
		}
		this.shape.setDeltaMovement(0, this.shape.getDeltaMovement().y, 0);
		this.shape.setYBodyRot(this.pinYaw);
		if (t == 30) {
			// The claw through the door and through them.
			BlockState wood = level.getBlockState(this.lockerBase);
			level.playSound(null, c.x, c.y, c.z, SoundEvents.ZOMBIE_BREAK_WOODEN_DOOR, SoundSource.HOSTILE, 1.8f, 0.7f);
			level.playSound(null, c.x, c.y, c.z, HwSounds.STAB, SoundSource.HOSTILE, 1.4f, 0.6f);
			level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, wood),
				this.victimSpot.x, this.victimSpot.y + 1.1, this.victimSpot.z, 40, 0.3, 0.4, 0.3, 0.15);
			this.pullOut();
			anim(this.victim, HwNet.Anim.IMPALED);
			MatchManager.bleed(level, this.victim, 24);
		}
		if (t >= 30 && t < 54) {
			// Up off the floor on his claw, drawn out of the wardrobe towards him.
			float k = Mth.clamp((t - 30) / 18f, 0f, 1f);
			k = k * k * (3 - 2 * k);
			Vec3 lifted = this.shapeSpot.add(this.out().scale(-1.2)).add(0, 1.0, 0);
			Vec3 at = this.victimSpot.add(0, 0.25, 0).lerp(lifted, k);
			this.placeVictim(at.x, at.y, at.z, this.lockerFacing.toYRot());
			this.victim.setDeltaMovement(Vec3.ZERO);
			if (t % 6 == 0) {
				MatchManager.bleed(level, this.victim, 4);
			}
		} else if (t == 54) {
			// Flung down on the floor in front of the wardrobe, lying clear of it (they go over backwards, away
			// from him): out from the door with him between them and it, or along its front; he stands over them.
			Vec3 door = Vec3.atBottomCenterOf(this.lockerBase).add(this.out().scale(0.5));
			door = new Vec3(door.x, this.shape.getY(), door.z);
			Vec3 side = this.out().cross(new Vec3(0, 1, 0));
			Vec3[][] ways = {
				{this.out(), door.add(this.out().scale(0.45))},
				{side, door.add(this.out().scale(0.75)).subtract(side.scale(1.4))},
				{side.scale(-1), door.add(this.out().scale(0.75)).add(side.scale(1.4))}};
			Vec3 feet = null;
			for (Vec3[] way : ways) {
				Vec3 d = way[0];
				Vec3 stand = way[1];
				Vec3 v = stand.add(d.scale(DEMO_FEET));
				Vec3 head = v.add(d.scale(1.8));
				net.minecraft.world.phys.AABB body = new net.minecraft.world.phys.AABB(Math.min(v.x, head.x) - 0.3, v.y + 0.05, Math.min(v.z, head.z) - 0.3,
					Math.max(v.x, head.x) + 0.3, v.y + 0.6, Math.max(v.z, head.z) + 0.3);
				if (level.noCollision(this.shape, this.shape.getBoundingBox().move(stand.subtract(this.shape.position()))) && level.noCollision(body)) {
					this.pinYaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
					this.shape.teleportTo(level, stand.x, stand.y, stand.z, Set.of(Relative.X_ROT), this.pinYaw, 0f, false);
					this.shapeSpot = stand;
					feet = v;
					break;
				}
			}
			if (feet == null) {
				// No room anywhere: where he is, as he stands.
				float yaw = this.pinYaw * Mth.DEG_TO_RAD;
				feet = this.shape.position().add(-Mth.sin(yaw) * DEMO_FEET, 0, Mth.cos(yaw) * DEMO_FEET);
			}
			Vec3 floor = feet;
			this.placeVictim(floor.x, floor.y, floor.z, this.pinYaw + 180f);
			level.playSound(null, floor.x, floor.y, floor.z, SoundEvents.PLAYER_BIG_FALL, SoundSource.PLAYERS, 1.4f, 0.8f);
			this.shake(this.victim, 8, 0.6f);
			anim(this.shape, HwNet.Anim.DEVOUR);
			anim(this.victim, HwNet.Anim.PINNED);
		} else if (t >= 60) {
			this.beginStruggle();
		}
	}

	/** Out of the wardrobe and in his hands. */
	private void pullOut() {
		ServerLevel level = this.level();
		Vec3 c = Vec3.atCenterOf(this.lockerBase);
		Lockers.leave(this.victim, false);
		level.playSound(null, c.x, c.y + 0.5, c.z, HwSounds.LOCKER_SLAM, SoundSource.HOSTILE, 1.6f, 0.7f);
		level.playSound(null, this.victim.getX(), this.victim.getY(), this.victim.getZ(), HwSounds.GRAB, SoundSource.HOSTILE, 1.2f, 1f);
		Voices.play(this.victim, Voices.Kind.SCREAM);
		this.placeVictim(this.victimSpot.x, this.victimSpot.y, this.victimSpot.z, this.lockerFacing.toYRot());
	}

	private void beginStruggle() {
		this.sceneTick = -1;
		this.nextChoke = this.level().getGameTime() + 4;
		this.startVictimRound(0);
		this.startShapeRound(0);
		this.sendState();
	}

	/** Michael doesn't leave the wardrobe: held where he stands (he may look about). */
	private void holdShape() {
		if (this.shape.position().distanceToSqr(this.shapeSpot) > 0.0025) {
			this.shape.teleportTo(this.level(), this.shapeSpot.x, this.shapeSpot.y, this.shapeSpot.z, Set.of(Relative.X_ROT, Relative.Y_ROT), 0f, 0f, false);
		}
		this.shape.setDeltaMovement(Vec3.ZERO);
	}

	/** His knife stuck in the door at chest height, blade in, handle out. */
	private void stickKnife() {
		ServerLevel level = this.level();
		Display.ItemDisplay d = new Display.ItemDisplay(EntityTypes.ITEM_DISPLAY, level);
		d.setItemStack(this.knife.isEmpty() ? new ItemStack(Items.IRON_SWORD) : this.knife.copy());
		d.setItemTransform(ItemDisplayContext.FIXED);
		Vec3 side = this.out().cross(new Vec3(0, 1, 0));
		Vec3 at = Vec3.atBottomCenterOf(this.lockerBase).add(this.out().scale(0.62)).add(side.scale(0.12)).add(0, 1.35, 0);
		d.setPos(at.x, at.y, at.z);
		// The picture upright, edge on to the door, the blade (up and right in the picture) turned into it.
		float yaw = this.lockerFacing.toYRot() + 90f;
		d.setTransformation(new Transformation(null, new Quaternionf().rotateY(-yaw * Mth.DEG_TO_RAD).rotateZ(-45f * Mth.DEG_TO_RAD),
			new Vector3f(0.8f, 0.8f, 0.8f), null));
		d.addTag(Leftovers.stamp());
		level.addFreshEntity(d);
		this.stuckKnife = d;
	}

	/** The knife out of the door and back in his hand (after the kill, or at once if they got away). */
	private void takeKnife() {
		if (this.stuckKnife != null) {
			this.stuckKnife.discard();
			this.stuckKnife = null;
			this.level().playSound(null, this.shape.getX(), this.shape.getY() + 1, this.shape.getZ(), HwSounds.STAB, SoundSource.HOSTILE, 0.8f, 1.6f);
		}
		if (!this.knife.isEmpty()) {
			if (this.shape.getMainHandItem().isEmpty()) {
				this.shape.setItemInHand(InteractionHand.MAIN_HAND, this.knife);
			} else {
				this.shape.getInventory().add(this.knife);
			}
			this.knife = ItemStack.EMPTY;
		}
	}

	/** The screen jolts for them (a blow, a fall). */
	private void shake(LivingEntity who, int ticks, float strength) {
		if (who instanceof ServerPlayer p && !Bots.isBot(p)) {
			ServerPlayNetworking.send(p, new HwNet.Quake(ticks, strength));
		}
	}

	/** Caught climbing through a window: where they were on the sill, and how far the pull back is (-1: none). */
	private Vec3 yankFrom;
	private int yankTick = -1;
	private static final int YANK_TICKS = 8;

	/** How far ahead of the Demogorgon the feet of the one under him are (they lie on away from him). */
	private static final double DEMO_FEET = 1.4;

	private static final int LOCKER_KILL_TICKS = 52;

	/** Michael's kill at the wardrobe: their head smashed against it three times, then his knife pulled out of the door. */
	private void startLockerFinisher() {
		anim(this.shape, HwNet.Anim.LOCKER_SMASH);
		anim(this.victim, HwNet.Anim.LOCKER_SMASHED);
		// Seen from the side, letterboxed, for both of them: from whichever side has room.
		Vec3 mid = this.shapeSpot.lerp(this.victimSpot, 0.5);
		Vec3 look = mid.add(0, 1.8, 0);
		Vec3 side = this.out().cross(new Vec3(0, 1, 0));
		Vec3 best = null;
		double bestLen = -1;
		for (int sign : new int[]{1, -1}) {
			Vec3 want = mid.add(side.scale(3.4 * sign)).add(this.out().scale(1.3)).add(0, 3.0, 0);
			HitResult hit = this.level().clip(new ClipContext(look, want, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, this.shape));
			Vec3 got = want;
			if (hit.getType() != HitResult.Type.MISS) {
				Vec3 back = look.subtract(hit.getLocation());
				got = hit.getLocation().add(back.lengthSqr() < 1e-4 ? Vec3.ZERO : back.normalize().scale(0.3));
			}
			double len = got.distanceTo(look);
			if (len > bestLen + 0.3) {
				best = got;
				bestLen = len;
			}
		}
		HwNet.Cutscene cam = new HwNet.Cutscene(Cutscenes.KILLCAM, LOCKER_KILL_TICKS + 6, best, look, Vec3.ZERO);
		for (LivingEntity who : new LivingEntity[]{this.shape, this.victim}) {
			if (who instanceof ServerPlayer p && !Bots.isBot(p)) {
				ServerPlayNetworking.send(p, cam);
			}
		}
	}

	private void tickLockerFinisher() {
		ServerLevel level = this.level();
		int t = this.finisherTick;
		if (t == 8 || t == 20 || t == 32) {
			Vec3 head = this.victimSpot.add(this.out().scale(-0.25)).add(0, 1.6, 0);
			level.playSound(null, head.x, head.y, head.z, SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR, SoundSource.HOSTILE, 1.5f, 0.75f + this.random.nextFloat() * 0.1f);
			level.playSound(null, head.x, head.y, head.z, SoundEvents.SKELETON_HURT, SoundSource.HOSTILE, 0.8f, 0.5f);
			if (t < 32) {
				Voices.play(this.victim, Voices.Kind.HURT);
			}
			// Sprayed out every way from the blow: off to the sides, up, out into the room (not all over the wardrobe).
			Vec3 side = this.out().cross(new Vec3(0, 1, 0));
			for (int i = 0, drops = this.bloody ? 50 : 28; i < drops; i++) {
				double a = (this.random.nextDouble() - 0.5) * Math.PI;
				Vec3 v = this.out().scale(Math.cos(a)).add(side.scale(Math.sin(a))).scale(0.15 + this.random.nextDouble() * 0.25)
					.add(0, 0.05 + this.random.nextDouble() * 0.2, 0);
				level.sendParticles(HwParticles.BLOOD, head.x, head.y, head.z, 0, v.x, v.y, v.z, 1);
			}
			this.shake(this.shape, 6, 0.35f);
			this.shake(this.victim, 8, 0.7f);
		} else if (t == 40) {
			this.takeKnife();
		} else if (t >= LOCKER_KILL_TICKS) {
			this.done = true;
			anim(this.shape, HwNet.Anim.NONE);
			anim(this.victim, HwNet.Anim.NONE);
			MatchManager.kill(this.shape, this.state, this.victim, MatchManager.KillKind.EXECUTION, this.bloody);
		}
	}

	/** For tests: the struggle won outright (the kill plays). */
	public void testWin() {
		if (this.sceneTick < 0 && !this.finishing) {
			this.shapePoints = this.shapeNeeded();
			this.checkEnd();
		}
	}

	/** For tests: how far the scene at the wardrobe is (-1: the struggle is on, or there was none). */
	public int sceneTick() {
		return this.sceneTick;
	}

	private ServerLevel level() {
		return this.shape.level();
	}

	private HwConfig cfg() {
		return HwConfig.get();
	}

	/** Hits the Shape still needs in total: the more the victim was stalked, the fewer. */
	public int shapeNeeded() {
		return HwConfig.at(this.cfg().shapePointsToKillByStalk, this.victimState.level());
	}

	boolean isDone() {
		return this.done;
	}

	// ---------------------------------------------------------------- reaction checks

	/** A new check for the victim, {@code delay} ticks from now (a short pause between checks). */
	private void startVictimRound(int delay) {
		HwConfig cfg = this.cfg();
		Track t = this.victimTrack;
		t.roundStart = this.level().getGameTime() + delay;
		t.zoneWidth = Math.max(0.08f, cfg.victimZoneWidth
			- HwConfig.at(cfg.victimZoneShrinkByTier, this.state.tier())
			- HwConfig.at(cfg.victimZoneShrinkByStalk, this.victimState.level())
			+ (Perks.has(this.victim, Perks.Perk.SLIPPERY) ? cfg.perkSlipperyZone : 0f));
		// Each round a different kind, at random: the narrower the zone (stalked, his Bloodthirst), the more taps.
		int kind = VICTIM_KINDS[this.random.nextInt(VICTIM_KINDS.length)];
		int taps = Mth.clamp(Math.round(7 * cfg.victimZoneWidth / t.zoneWidth), 6, 12);
		t.round.roll(kind, t.zoneWidth, taps, this.random);
		t.zoneStart = t.round.zoneStart;
		t.open = true;
		// Simulated victims (bots, townsfolk) find a narrower zone harder to hit, like a person does.
		float base = this.victim instanceof BotPlayer ? cfg.botQteSuccessChance : cfg.mannequinQteSuccessChance;
		float chance = base * Mth.clamp(t.zoneWidth / cfg.victimZoneWidth, 0.35f, 1f);
		if (Police.isOfficer(this.victim)) {
			// Officers keep their heads: they hit their checks this often however narrow the zone.
			chance = cfg.policeQteSuccessChance;
		}
		t.simSuccess = this.random.nextFloat() < chance;
		t.simPressTick = 8 + this.random.nextInt(Math.max(1, this.victimLimit() - 12));
	}

	private void startShapeRound(int delay) {
		HwConfig cfg = this.cfg();
		Track t = this.shapeTrack;
		t.roundStart = this.level().getGameTime() + delay;
		t.zoneWidth = Math.min(0.5f, cfg.shapeZoneWidth + HwConfig.at(cfg.shapeZoneGrowByStalk, this.victimState.level()));
		t.zoneStart = 0.2f + this.random.nextFloat() * (0.7f - t.zoneWidth);
		t.open = true;
	}

	private void sendState() {
		HwConfig cfg = this.cfg();
		boolean windowReady = this.windowAvailable();
		this.windowShown = windowReady;
		this.bloody = this.state.level() >= cfg.bloodyExecutionLevel;
		if (this.victim instanceof ServerPlayer player) {
			CheckRound r = this.victimTrack.round;
			ServerPlayNetworking.send(player, new HwNet.Qte(true, false, r.zoneStart, r.zoneWidth, this.victimPeriod(),
				this.victimTrack.roundStart, this.victimLimit(), this.victimPoints, cfg.victimPointsToEscape, this.shapePoints, this.shapeNeeded(),
				false, false, r.kind, r.zone2, r.need, r.stage));
		}
		Track s = this.shapeTrack;
		ServerPlayNetworking.send(this.shape, new HwNet.Qte(true, true, s.zoneStart, s.zoneWidth, cfg.shapeNeedlePeriodTicks,
			s.roundStart, cfg.shapeRoundTicks, this.shapePoints, this.shapeNeeded(), this.victimPoints, cfg.victimPointsToEscape,
			windowReady, this.bloody));
	}

	/** Would the Shape's press land right now? (For the AI simulation's Shape.) */
	public boolean shapeWouldHit() {
		if (this.finishing || !this.shapeTrack.open) {
			return false;
		}
		long elapsed = this.level().getGameTime() - this.shapeTrack.roundStart;
		if (elapsed < 3) {
			return false;
		}
		float needle = HwNet.needle(elapsed, this.cfg().shapeNeedlePeriodTicks);
		return needle >= this.shapeTrack.zoneStart && needle <= this.shapeTrack.zoneStart + this.shapeTrack.zoneWidth;
	}

	public LivingEntity victim() {
		return this.victim;
	}

	/** Someone in the grab pressed their check key. */
	void press(ServerPlayer presser) {
		if (this.finishing) {
			return;
		}
		long now = this.level().getGameTime();
		HwConfig cfg = this.cfg();
		if (presser == this.shape && this.shapeTrack.open) {
			long elapsed = now - this.shapeTrack.roundStart;
			if (elapsed < 3) {
				return;
			}
			float needle = HwNet.needle(elapsed, cfg.shapeNeedlePeriodTicks);
			this.resolveShape(needle >= this.shapeTrack.zoneStart && needle <= this.shapeTrack.zoneStart + this.shapeTrack.zoneWidth);
		} else if (presser == this.victim && this.victimTrack.open) {
			long elapsed = now - this.victimTrack.roundStart;
			if (elapsed < 3) {
				return;
			}
			int result = this.victimTrack.round.press(elapsed, this.victimPeriod());
			if (result == CheckRound.PENDING) {
				// A tap, or the first of two arcs: not decided yet.
				this.sendState();
			} else {
				this.resolveVictim(result == CheckRound.HIT);
			}
		}
	}

	private void resolveVictim(boolean hit) {
		this.victimTrack.open = false;
		if (hit) {
			this.victimPoints++;
		} else if (this.cfg().victimMissGivesShapePoint) {
			this.shapePoints++;
		}
		this.feedback(this.victim, hit);
		if (!this.checkEnd()) {
			this.startVictimRound(this.cfg().qteRoundGapTicks);
			this.sendState();
		}
	}

	private void resolveShape(boolean hit) {
		this.shapeTrack.open = false;
		if (hit) {
			this.shapePoints++;
		} else {
			this.victimPoints++;
		}
		this.feedback(this.shape, hit);
		if (!this.checkEnd()) {
			this.startShapeRound(this.cfg().qteRoundGapTicks);
			this.sendState();
		}
	}

	private void feedback(LivingEntity who, boolean hit) {
		this.level().playSound(null, who.getX(), who.getY(), who.getZ(),
			hit ? HwSounds.QTE_HIT : HwSounds.QTE_MISS, SoundSource.PLAYERS, 1f, hit ? 1.2f : 0.7f);
	}

	/** Returns true once the grab is decided. */
	private boolean checkEnd() {
		HwConfig cfg = this.cfg();
		if (this.victimPoints >= cfg.victimPointsToEscape) {
			this.escape();
			return true;
		}
		if (this.shapePoints >= this.shapeNeeded()) {
			this.startFinisher();
			return true;
		}
		return false;
	}

	/** Tells everyone around what this entity is doing (see client.ClientAnims). */
	public static void anim(net.minecraft.world.entity.Entity who, int kind) {
		if (!(who.level() instanceof ServerLevel level)) {
			return;
		}
		HwNet.Anim payload = new HwNet.Anim(who.getId(), kind);
		for (ServerPlayer p : level.players()) {
			if (!Bots.isBot(p) && p.distanceToSqr(who) < 160 * 160) {
				ServerPlayNetworking.send(p, payload);
			}
		}
	}

	private void escape() {
		this.escape(false);
	}

	/** Out of his hands: torn free with a shove (he staggers), or with a knife in him (he clutches the wound). */
	private void escape(boolean stab) {
		this.animated = true;
		anim(this.victim, stab ? HwNet.Anim.STAB : HwNet.Anim.BREAK_FREE);
		anim(this.shape, stab ? HwNet.Anim.STABBED : HwNet.Anim.STAGGER);
		AiStats.add(this.victim, "grab.escaped");
		Match.chronicle(this.shape.level().getServer(), Component.translatable(stab ? "halloween.chronicle.stab_free" : "halloween.chronicle.break_free",
			this.victim.getDisplayName()).withStyle(ChatFormatting.YELLOW));
		HwConfig cfg = this.cfg();
		this.state.recoverTicks = cfg.escapeShapeRecoverTicks;
		this.state.grabCooldown = HwConfig.at(cfg.grabCooldownByTier, this.state.tier());
		Vec3 away = this.victim.position().subtract(this.shape.position()).multiply(1, 0, 1);
		away = away.lengthSqr() < 1.0E-4 ? this.shape.getLookAngle().multiply(1, 0, 1) : away;
		away = away.normalize();
		if (this.atLocker()) {
			// Their back to the wardrobe: they tear free to one side; his knife back out of the door.
			away = this.out().cross(new Vec3(0, 1, 0)).scale(this.random.nextBoolean() ? 1 : -1);
			this.takeKnife();
		}
		this.victim.setDeltaMovement(away.x * 0.9, 0.35, away.z * 0.9);
		this.victim.hurtMarked = true;
		this.level().playSound(null, this.victim.getX(), this.victim.getY(), this.victim.getZ(), HwSounds.ESCAPE, SoundSource.PLAYERS, 1f, 1f);
		this.shape.sendOverlayMessage(Component.translatable("halloween.msg.escaped"));
		if (this.victim instanceof ServerPlayer player) {
			player.sendOverlayMessage(Component.translatable("halloween.msg.you_escaped"));
		}
		this.end();
	}

	/** The victim stabbed him (a knife, an ice pick, a lawn dart): he lets go at once. */
	void stabbed() {
		ServerLevel level = this.level();
		level.playSound(null, this.shape.getX(), this.shape.getEyeY(), this.shape.getZ(), HwSounds.STAB, SoundSource.PLAYERS, 1.2f, 1.3f);
		MatchManager.bleed(level, this.shape, 10);
		this.escape(true);
		this.shape.sendOverlayMessage(Component.translatable("halloween.msg.stabbed_shape").withStyle(net.minecraft.ChatFormatting.DARK_RED));
		if (this.victim instanceof ServerPlayer player) {
			player.sendOverlayMessage(Component.translatable("halloween.msg.stabbed_free").withStyle(net.minecraft.ChatFormatting.GREEN));
		}
	}

	// ---------------------------------------------------------------- finisher

	private boolean windowAvailable() {
		return this.victimState.level() >= this.cfg().windowFinisherStalkLevel && this.findWindow() != null;
	}

	private void startFinisher() {
		this.finishing = true;
		Music.killing(this.level().getServer(), this.cfg().finisherTicks + 70);
		this.finisherTick = 0;
		this.bloody = this.state.level() >= this.cfg().bloodyExecutionLevel;
		if (this.atLocker()) {
			this.startLockerFinisher();
		} else 		if (this.victimState.level() >= this.cfg().windowFinisherStalkLevel) {
			this.windowPos = this.findWindow();
		}
		HwNet.Qte off = HwNet.Qte.INACTIVE;
		if (this.victim instanceof ServerPlayer player) {
			ServerPlayNetworking.send(player, off);
		}
		ServerPlayNetworking.send(this.shape, off);
	}

	private BlockPos findWindow() {
		int r = this.cfg().windowSearchRadius;
		BlockPos center = this.shape.blockPosition();
		Vec3 look = this.shape.getLookAngle().multiply(1, 0, 1).normalize();
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-r, 0, -r), center.offset(r, 2, r))) {
			if (Sight.isGlass(this.level().getBlockState(pos))) {
				// Only windows roughly in front of the Shape: he throws the victim forward.
				Vec3 dir = Vec3.atCenterOf(pos).subtract(this.shape.position()).multiply(1, 0, 1);
				if (dir.lengthSqr() < 1.0E-4 || look.dot(dir.normalize()) < 0.5) {
					continue;
				}
				double d = pos.distToCenterSqr(this.shape.position());
				if (d < bestDist) {
					bestDist = d;
					best = pos.immutable();
				}
			}
		}
		return best;
	}

	private void tickFinisher() {
		this.finisherTick++;
		if (this.atLocker()) {
			this.tickLockerFinisher();
			return;
		}
		if (this.windowPos != null) {
			if (this.finisherTick == 10) {
				this.stabEffects();
			} else if (this.finisherTick == 18) {
				this.smashWindow();
			} else if (this.finisherTick >= 26) {
				this.done = true;
				anim(this.shape, HwNet.Anim.NONE);
				anim(this.victim, HwNet.Anim.NONE);
				MatchManager.kill(this.shape, this.state, this.victim, MatchManager.KillKind.WINDOW, this.bloody);
			}
			return;
		}
		int[] stabs = this.bloody ? new int[]{6, 12, 18, 24, 30, 36} : new int[]{10, 20, 30};
		for (int t : stabs) {
			if (this.finisherTick == t) {
				this.stabEffects();
			}
		}
		if (this.finisherTick >= this.cfg().finisherTicks) {
			this.done = true;
			anim(this.shape, HwNet.Anim.NONE);
			anim(this.victim, HwNet.Anim.NONE);
			MatchManager.kill(this.shape, this.state, this.victim, MatchManager.KillKind.EXECUTION, this.bloody);
		}
	}

	private void stabEffects() {
		ServerLevel level = this.level();
		anim(this.shape, HwNet.Anim.KNIFE_DOWN);
		level.playSound(null, this.victim.getX(), this.victim.getY(), this.victim.getZ(), HwSounds.STAB, SoundSource.HOSTILE, 1.1f, 0.9f + this.random.nextFloat() * 0.2f);
		if (Demogorgon.active()) {
			// Not a knife: his mouth.
			level.playSound(null, this.victim.getX(), this.victim.getY(), this.victim.getZ(), net.minecraft.sounds.SoundEvents.RAVAGER_ATTACK, SoundSource.HOSTILE, 1.2f,
				0.55f + this.random.nextFloat() * 0.15f);
		}
		MatchManager.bleed(level, this.victim, this.bloody ? 30 : 18);
	}

	private void smashWindow() {
		// The glass shatters but shards stay in the frame (see Windows).
		Windows.breakColumn(this.level(), this.windowPos);
		anim(this.victim, HwNet.Anim.NONE);
		Vec3 window = Vec3.atBottomCenterOf(this.windowPos);
		Vec3 dir = window.subtract(this.shape.position()).multiply(1, 0, 1);
		dir = dir.lengthSqr() < 1.0E-4 ? this.shape.getLookAngle().multiply(1, 0, 1) : dir;
		Vec3 target = window.add(dir.normalize().scale(1.3));
		double y = Math.min(window.y, this.shape.getY());
		this.placeVictim(target.x, y, target.z, this.victim.getYRot());
		this.victim.setDeltaMovement(dir.normalize().scale(0.4).add(0, 0.2, 0));
		this.victim.hurtMarked = true;
	}

	// ---------------------------------------------------------------- tick

	void tick() {
		if (this.done) {
			return;
		}
		if (this.victim.isRemoved() || !this.victim.isAlive() || this.shape.isRemoved() || !MatchManager.isVictim(this.victim)) {
			this.end();
			return;
		}
		if (this.sceneTick >= 0) {
			this.tickScene();
			return;
		}
		if (!(this.finishing && this.windowPos != null && this.finisherTick >= 18)) {
			this.pinVictim();
		}
		if (this.finishing) {
			this.tickFinisher();
			return;
		}
		HwConfig cfg = this.cfg();
		long now = this.level().getGameTime();
		if (now >= this.nextChoke) {
			// Held by the throat: choking, gasping for air.
			// One whole take at a time; the next (a different one) only once it is over.
			int take = this.random.nextInt(HwSounds.VOICE_CHOKES.length);
			if (take == this.choking) {
				take = (take + 1) % HwSounds.VOICE_CHOKES.length;
			}
			this.choking = take;
			float pitch = (Voices.female(this.victim) ? 1.05f : 0.97f) + this.random.nextFloat() * 0.03f;
			this.nextChoke = now + (long) (HwSounds.VOICE_CHOKE_TICKS[take] / pitch) + 4;
			this.level().playSound(null, this.victim.getX(), this.victim.getEyeY(), this.victim.getZ(), HwSounds.VOICE_CHOKES[take], SoundSource.VOICE, 1.2f, pitch);
			AiStats.add(this.victim, "voice.choke");
		}
		if (now % 5 == 0 && this.windowAvailable() != this.windowShown) {
			// He shuffled up to a window (or away from one): the throw is on offer (or not) right away.
			this.sendState();
		}
		long victimElapsed = now - this.victimTrack.roundStart;
		if (this.victimTrack.open) {
			if ((!(this.victim instanceof ServerPlayer) || this.victim instanceof BotPlayer) && victimElapsed >= this.victimTrack.simPressTick) {
				this.resolveVictim(this.victimTrack.simSuccess);
			} else if (victimElapsed >= this.victimLimit()) {
				this.resolveVictim(false);
			}
		}
		if (!this.finishing && !this.done && this.shapeTrack.open && now - this.shapeTrack.roundStart >= cfg.shapeRoundTicks) {
			this.resolveShape(false);
		}
	}

	/** Is the Shape still carrying out the struggle (he may shuffle along), not finishing the kill? */
	boolean struggling() {
		return !this.finishing && !this.done && this.sceneTick < 0;
	}

	private void pinVictim() {
		if (this.atLocker()) {
			// At the wardrobe: he stays put, their back to its door.
			this.holdShape();
			this.placeVictim(this.victimSpot.x, this.victimSpot.y, this.victimSpot.z, this.lockerFacing.toYRot());
			this.victim.setDeltaMovement(Vec3.ZERO);
			return;
		}
		float yaw = (Demogorgon.active() ? this.pinYaw : this.shape.getYRot()) * Mth.DEG_TO_RAD;
		if (Demogorgon.active()) {
			// Over them on all fours his body faces them, whichever way he looks.
			this.shape.setYBodyRot(this.pinYaw);
		}
		// Held up in front of him; under the Demogorgon, lying, their feet by his.
		// Under the Demogorgon: their feet towards him, lying out ahead with the chest under his mouth.
		double reach = Demogorgon.active() ? DEMO_FEET : 0.9;
		double x = this.shape.getX() - Mth.sin(yaw) * reach;
		double z = this.shape.getZ() + Mth.cos(yaw) * reach;
		// Face the Shape.
		float victimYaw = (Demogorgon.active() ? this.pinYaw : this.shape.getYRot()) + 180f;
		if (this.yankTick >= 0 && this.yankTick < YANK_TICKS) {
			// Pulled back off the window sill: through the air into his hands, whatever is in between.
			float k = ++this.yankTick / (float) YANK_TICKS;
			k = k * k * (3 - 2 * k);
			Vec3 to = new Vec3(x, this.shape.getY(), z);
			if (!this.level().noCollision(this.victim, this.victim.getBoundingBox().move(to.subtract(this.victim.position())))) {
				// In front of him is the wall the window is in: to his side, or behind him, wherever there is room.
				for (float turn : new float[]{45f, -45f, 90f, -90f, 135f, -135f, 180f}) {
					float a = (this.shape.getYRot() + turn) * Mth.DEG_TO_RAD;
					Vec3 o = new Vec3(this.shape.getX() - Mth.sin(a) * 0.9, this.shape.getY(), this.shape.getZ() + Mth.cos(a) * 0.9);
					if (this.level().noCollision(this.victim, this.victim.getBoundingBox().move(o.subtract(this.victim.position())))) {
						to = o;
						break;
					}
				}
			}
			Vec3 at = this.yankFrom.lerp(to, k).add(0, Mth.sin(k * Mth.PI) * 0.25, 0);
			this.placeVictim(at.x, at.y, at.z, victimYaw);
			this.victim.setDeltaMovement(Vec3.ZERO);
			this.lastGood = this.shape.position();
			return;
		}
		if (this.victim.distanceToSqr(x, this.shape.getY(), z) > 0.04) {
			// He drags the victim along in front of him, but not through walls: there he can't go on.
			Vec3 move = new Vec3(x, this.shape.getY(), z).subtract(this.victim.position());
			// (Lying under the Demogorgon they fit wherever he stands over them: they stay under his mouth.)
			boolean room = Demogorgon.active() || this.level().noCollision(this.victim, this.victim.getBoundingBox().move(move));
			if (room) {
				this.placeVictim(x, this.shape.getY(), z, victimYaw);
				this.lastGood = this.shape.position();
			} else if (this.lastGood != null && this.shape.position().distanceTo(this.lastGood) > 0.05) {
				this.shape.teleportTo(this.level(), this.lastGood.x, this.lastGood.y, this.lastGood.z, Set.of(Relative.X_ROT, Relative.Y_ROT), 0f, 0f, false);
			}
		} else {
			this.lastGood = this.shape.position();
		}
		this.victim.setDeltaMovement(Vec3.ZERO);
	}

	private void placeVictim(double x, double y, double z, float yaw) {
		if (this.victim instanceof ServerPlayer player) {
			player.teleportTo(this.level(), x, y, z, Set.of(), yaw, 0f, false);
		} else {
			this.victim.teleportTo(x, y, z);
			this.victim.setYRot(yaw);
			this.victim.setYBodyRot(yaw);
			this.victim.setYHeadRot(yaw);
		}
	}

	/** Releases everything without a kill (escape, abort, reset). */
	/** Out of his hands (or dead): the choking breaks off. */
	private void stopChoking() {
		if (this.choking < 0) {
			return;
		}
		net.minecraft.network.protocol.game.ClientboundStopSoundPacket stop = new net.minecraft.network.protocol.game.ClientboundStopSoundPacket(
			HwSounds.VOICE_CHOKES[this.choking].location(), SoundSource.VOICE);
		this.choking = -1;
		for (ServerPlayer p : this.level().getServer().getPlayerList().getPlayers()) {
			if (p.level() == this.level() && p.distanceToSqr(this.victim) < 64 * 64) {
				p.connection.send(stop);
			}
		}
	}

	void end() {
		this.takeKnife();
		if (!this.animated) {
			anim(this.shape, HwNet.Anim.NONE);
			anim(this.victim, HwNet.Anim.NONE);
			this.animated = true;
		}
		if (this.state.grab == this) {
			this.state.grab = null;
		}
		this.done = true;
		this.victimState.grabbed = false;
		this.stopChoking();
		if (this.victim instanceof ServerPlayer player) {
			ServerPlayNetworking.send(player, HwNet.Qte.INACTIVE);
		}
		ServerPlayNetworking.send(this.shape, HwNet.Qte.INACTIVE);
	}
}
