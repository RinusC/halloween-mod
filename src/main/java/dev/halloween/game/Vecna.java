package dev.halloween.game;

import dev.halloween.Halloween;
import dev.halloween.HwConfig;
import dev.halloween.HwParticles;
import dev.halloween.HwSounds;
import dev.halloween.net.HwNet;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Vecna (Stranger Things), the third killer (/hw killer vecna): played by a person (a bot always plays Michael). As
 * slow as Michael and without his dash, Sense or Impulse: he wins with his head. Grabbing, the struggle and the kill
 * are Michael's; his own powers take Michael's keys.
 * <ul>
 * <li>The Grip (Killer Sense key): held, he stands with a hand thrust out, aiming (a second at least, everyone sees it
 * coming); let go, it flies where he aimed: an upright streak of red, man-high and narrow, a few strides a second, so a
 * step aside lets it by and a wall stops it. Whoever it touches first (survivor, townsperson, officer) is drawn to him
 * by itself (they can still shoot); at his hand he takes them by the throat. Losing sight of them or a stun breaks it.
 * A miss rests a little, a catch long. Every second drawn stalks them.</li>
 * <li>The Eyes (Shape Jump key): he sees through the eyes of the survivor nearest him, standing still, for as long as
 * the reserve lasts (it fills again while he doesn't look); seeing them stalks them. They hear voices meanwhile. A stun ends it.</li>
 * <li>The Anchor (Impulse key, held): the cursed show with their stage; let go, he is at once a few steps from the one
 * under his aim, unseen at first, taking shape for three seconds (rooted, the clock striking for them): time for them
 * to run. Then a long rest.</li>
 * <li>The curse on one survivor ({@link VecnaCurse}), and the ritual of kills that opens the rift ({@link VecnaRitual}).</li>
 * </ul>
 */
public final class Vecna {
	/** The held survivor: rooted and unable to jump. */
	private static final Identifier GRIPPED_ID = Halloween.id("vecna_gripped");

	/** Vecna is this match's killer (see begin). */
	private static boolean thisMatch;
	/** The Grip: whom it holds (null: nobody), since when, ticks out of sight, rest left. */
	private static LivingEntity gripped;
	private static int gripSince;
	private static int gripLost;
	private static int gripCooldown;
	/** The rest the Grip took last (a miss rests less than a catch); ticks aimed so far (-1: not aiming); the throw in flight (null: none), its way and the reach left. */
	private static int gripCooldownMax = 1;
	private static int gripAim = -1;
	/** The Grip key as it was last tick while drawing someone in (pressed anew: he lets go). */
	private static boolean gripKeyWas;
	private static Vec3 bolt;
	private static Vec3 boltDir = Vec3.ZERO;
	private static double boltLeft;
	/** The Eyes: when the next whisper comes to the one he looks through. */
	private static int nextWhisper;
	/** The Eyes: whose eyes he looks through (null: his own), the seconds of looking in reserve (ticks), ticks since the last refill. */
	private static LivingEntity eyesOn;
	private static int eyesReserve;
	private static int eyesIdle;
	/** The Anchor: choosing (the key held), then arriving beside them (taking shape, rooted), whom for and ticks left; then its rest. */
	public static final int ANCHOR_IDLE = 0;
	public static final int ANCHOR_CHOOSING = 1;
	public static final int ANCHOR_CHANNEL = 2;
	private static int anchorPhase;
	private static ServerPlayer anchorTarget;
	private static int anchorLeft;
	private static int anchorCooldown;

	private Vecna() {
	}

	/** The next matches' killer: Vecna. */
	public static void choose() {
		HwConfig.get().killerDemogorgon = false;
		HwConfig.get().killerVecna = true;
		HwConfig.save();
	}

	public static boolean chosen() {
		return HwConfig.get().killerVecna;
	}

	/** At a match's start: Vecna this match if he is chosen and a person plays the killer (a bot plays Michael). */
	public static void begin(boolean personKiller) {
		thisMatch = chosen() && personKiller;
	}

	/** Is the killer Vecna (chosen, and a person plays him)? */
	public static boolean active() {
		return thisMatch;
	}

	/** Holding someone in the Grip: he stands still. */
	public static boolean gripping() {
		return active() && gripped != null;
	}

	/** Barely moving: aiming the Grip, throwing it or drawing someone in (he can creep along). */
	public static boolean creeping() {
		return active() && (gripAim >= 0 || bolt != null || gripped != null);
	}

	/** Standing still: looking through the Eyes, or taking shape after the Anchor. */
	public static boolean rooted() {
		return active() && (eyesOn != null || anchorPhase == ANCHOR_CHANNEL);
	}

	/** The Grip in flight. */
	public static boolean flying() {
		return active() && bolt != null;
	}

	/** For tests: the thrown Grip's way. */
	public static Vec3 testBoltDir() {
		return boltDir;
	}

	/** For tests: the Grip and the Anchor rested. */
	public static void testReady() {
		gripCooldown = 0;
		anchorCooldown = 0;
	}

	/** Is this one held by the Grip? */
	public static boolean held(LivingEntity e) {
		return gripping() && gripped == e;
	}

	// ---------------------------------------------------------------- the keys

	/** The Shape Jump key: the Eyes on (through the nearest survivor's eyes) or off again. */
	static void eyesKey(ServerPlayer p, ShapeState st) {
		if (eyesOn != null) {
			eyesOff(p);
			return;
		}
		if (st.busy() || gripped != null || bolt != null || gripAim >= 0 || anchorPhase == ANCHOR_CHANNEL || Match.frozen()) {
			return;
		}
		if (eyesReserve < 20) {
			p.sendOverlayMessage(Component.translatable("halloween.msg.vecna.eyes_tired").withStyle(ChatFormatting.GRAY));
			return;
		}
		// The nearest survivor or townsperson (or officer).
		LivingEntity nearest = null;
		for (LivingEntity s : p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(256), Vecna::watchable)) {
			if (nearest == null || s.distanceTo(p) < nearest.distanceTo(p)) {
				nearest = s;
			}
		}
		if (nearest == null) {
			return;
		}
		eyesOn = nearest;
		nextWhisper = 0;
		p.level().playSound(null, p.getX(), p.getY(), p.getZ(), HwSounds.VECNA_EYES_OPEN, SoundSource.HOSTILE, 1f, 1f);
		AiStats.add(p, "vecna.eyes");
	}

	/** Whom the Eyes can look through: a survivor, a townsperson, an officer, alive and in the match. */
	private static boolean watchable(LivingEntity e) {
		if (e instanceof ServerPlayer s) {
			return MatchManager.role(s).survivor() && !s.isSpectator() && s.isAlive();
		}
		return e.isAlive() && !e.isRemoved() && MatchManager.isVictim(e);
	}

	private static void eyesOff(ServerPlayer p) {
		if (eyesOn == null) {
			return;
		}
		eyesOn = null;
		eyesIdle = 0;
		if (p != null) {
			p.level().playSound(null, p.getX(), p.getY(), p.getZ(), HwSounds.VECNA_EYES_CLOSE, SoundSource.HOSTILE, 0.8f, 1f);
		}
	}

	/** The Impulse key, pressed or let go (the Anchor: held, the cursed show; let go, he goes to the one he aims at). */
	public static void anchorKey(ServerPlayer p, boolean held) {
		ShapeState st = MatchManager.shapeState(p);
		if (!active() || st == null || MatchManager.role(p) != dev.halloween.Role.SHAPE) {
			return;
		}
		if (held) {
			if (anchorPhase != ANCHOR_IDLE) {
				return;
			}
			if (anchorCooldown > 0) {
				p.sendOverlayMessage(Component.translatable("halloween.msg.vecna.anchor_rest", (anchorCooldown + 19) / 20).withStyle(ChatFormatting.GRAY));
				return;
			}
			anchorPhase = ANCHOR_CHOOSING;
			return;
		}
		if (anchorPhase != ANCHOR_CHOOSING) {
			return;
		}
		anchorPhase = ANCHOR_IDLE;
		ServerPlayer target = anchorAimed(p, st);
		if (target == null) {
			p.sendOverlayMessage(Component.translatable("halloween.msg.vecna.anchor_none").withStyle(ChatFormatting.GRAY));
			return;
		}
		if (st.busy() || gripped != null || eyesOn != null || bolt != null || Match.frozen()) {
			return;
		}
		ServerLevel level = p.level();
		HwConfig cfg = HwConfig.get();
		Vec3 to = arrival(level, target, cfg.vecnaAnchorDistance);
		if (to == null) {
			p.sendOverlayMessage(Component.translatable("halloween.msg.vecna.anchor_none").withStyle(ChatFormatting.GRAY));
			return;
		}
		if (gripAim >= 0) {
			stopAim(p);
		}
		// He is there at once, and takes shape over a few seconds: time for them to run.
		level.playSound(null, p.getX(), p.getY(), p.getZ(), HwSounds.VECNA_ANCHOR_VANISH, SoundSource.HOSTILE, 1.5f, 1f);
		level.sendParticles(HwParticles.VECNA_SMOKE, p.getX(), p.getY() + 1, p.getZ(), 14, 0.35, 0.8, 0.35, 0.02);
		level.sendParticles(HwParticles.VECNA_SPARK, p.getX(), p.getY() + 1, p.getZ(), 12, 0.3, 0.8, 0.3, 0.12);
		Vec3 d = target.position().subtract(to);
		float yaw = (float) (Math.atan2(-d.x, d.z) * 180 / Math.PI);
		p.teleportTo(level, to.x, to.y, to.z, java.util.Set.of(), yaw, 0f, false);
		p.setInvisible(true);
		level.playSound(null, to.x, to.y, to.z, HwSounds.VECNA_ANCHOR_ARRIVE, SoundSource.HOSTILE, 1.2f, 1f);
		anchorPhase = ANCHOR_CHANNEL;
		anchorTarget = target;
		anchorLeft = arriveTicks();
		anchorCooldown = anchorRest();
		target.sendOverlayMessage(Component.translatable("halloween.msg.vecna.anchor_coming").withStyle(ChatFormatting.DARK_RED));
		AiStats.add(p, "vecna.anchor");
	}

	/** How long he takes shape after the Anchor (shorter in the rift). */
	private static int arriveTicks() {
		return VecnaRitual.rift() ? HwConfig.get().vecnaRiftAnchorDelayTicks : HwConfig.get().vecnaAnchorDelayTicks;
	}

	/** The cursed survivor nearest his aim, wherever they are. */
	private static ServerPlayer anchorAimed(ServerPlayer v, ShapeState st) {
		Vec3 from = MatchManager.aimOrigin(v, st);
		Vec3 look = MatchManager.aimDirection(v, st);
		ServerPlayer best = null;
		double bestAngle = Double.MAX_VALUE;
		for (ServerPlayer s : v.level().players()) {
			if (!MatchManager.role(s).survivor() || s.isSpectator() || !s.isAlive() || VecnaCurse.stage(s) < 1 && !VecnaRitual.rift()) {
				continue;
			}
			double angle = Sight.angleTo(from, look, FocusPoints.bodyCenter(s));
			if (angle < bestAngle) {
				bestAngle = angle;
				best = s;
			}
		}
		return best;
	}

	// ---------------------------------------------------------------- every tick

	public static void tick(MinecraftServer server) {
		if (!active() || !Match.running()) {
			return;
		}
		ServerLevel level = server.overworld();
		int now = server.getTickCount();
		ServerPlayer v = Senses.shape(level);
		ShapeState st = v == null ? null : MatchManager.shapeState(v);
		if (st == null) {
			release(level, null, false);
			eyesOff(null);
			anchorPhase = ANCHOR_IDLE;
			gripAim = -1;
			bolt = null;
			return;
		}
		HwConfig cfg = HwConfig.get();
		if (gripCooldown > 0) {
			gripCooldown--;
		}
		if (anchorCooldown > 0) {
			anchorCooldown--;
		}
		if (gripped != null) {
			tickGrip(level, v, st, now);
		} else if (bolt != null) {
			tickBolt(level, v, st, now);
		} else {
			tickAim(level, v, st, now);
		}
		tickEyes(v, st);
		tickAnchor(level, v, st, now);
		if (now % 3 == 0 && !v.isInvisible()) {
			// He floats: a dark haze and ash drifting under his feet.
			level.sendParticles(HwParticles.VECNA_ASH, v.getX(), v.getY() + 0.4, v.getZ(), 1, 0.3, 0.3, 0.3, 0.005);
			if (now % 9 == 0) {
				level.sendParticles(HwParticles.VECNA_SMOKE, v.getX(), v.getY() + 0.1, v.getZ(), 1, 0.2, 0.02, 0.2, 0.004);
			}
		}
		if (now % 2 == 0) {
			// The cursed one always (he knows who it is; through walls once they are broken); choosing where the Anchor
			// goes, everyone it could go to.
			List<HwNet.CurseMark> marks = new ArrayList<>();
			for (ServerPlayer s : level.players()) {
				int stage = MatchManager.role(s).survivor() && !s.isSpectator() && s.isAlive() ? VecnaCurse.stage(s) : 0;
				if (stage > 0 || anchorPhase == ANCHOR_CHOOSING && VecnaRitual.rift() && MatchManager.role(s).survivor() && !s.isSpectator() && s.isAlive()) {
					marks.add(new HwNet.CurseMark(s.getId(), FocusPoints.bodyCenter(s), stage, s.getDisplayName().getString()));
				}
			}
			ServerPlayNetworking.send(v, new HwNet.VecnaHud(gripCooldown, gripCooldownMax, gripped == null ? -1 : gripped.getId(), gripAim, cfg.vecnaGripAimTicks, bolt != null,
				eyesReserve, eyesMax(), eyesOn == null ? -1 : eyesOn.getId(),
				anchorCooldown, anchorRest(), anchorPhase, anchorLeft, marks));
		}
	}

	private static double gripRange() {
		return VecnaRitual.rift() ? HwConfig.get().vecnaRiftGripRange : HwConfig.get().vecnaGripRange;
	}

	/** The Anchor's rest: short once the rift is open. */
	private static int anchorRest() {
		return VecnaRitual.rift() ? HwConfig.get().vecnaRiftAnchorCooldownTicks : HwConfig.get().vecnaAnchorCooldownTicks;
	}

	private static int eyesMax() {
		return VecnaRitual.rift() ? HwConfig.get().vecnaRiftEyesMaxTicks : HwConfig.get().vecnaEyesMaxTicks;
	}

	/** The Eyes: their reserve drains while he looks, fills while he doesn't; seeing someone stalks them. */
	private static void tickEyes(ServerPlayer v, ShapeState st) {
		HwConfig cfg = HwConfig.get();
		if (eyesOn == null) {
			if (eyesReserve < eyesMax() && ++eyesIdle >= (VecnaRitual.rift() ? cfg.vecnaRiftEyesRefillEvery : cfg.vecnaEyesRefillEvery)) {
				eyesIdle = 0;
				eyesReserve++;
			}
			return;
		}
		LivingEntity t = eyesOn;
		if (--eyesReserve <= 0 || st.stunnedTicks > 0 || st.grab != null || !watchable(t)) {
			eyesReserve = Math.max(0, eyesReserve);
			eyesOff(v);
			return;
		}
		MatchManager.addStalk(v, st, t, cfg.vecnaEyesStalkPerSecond / 20f);
		if (t instanceof ServerPlayer sp) {
			whisper(sp);
		}
	}

	/** Voices in the head of the one he looks through: whispers, now and then words. */
	private static void whisper(ServerPlayer t) {
		int now = t.level().getServer().getTickCount();
		if (now < nextWhisper) {
			return;
		}
		var r = t.getRandom();
		nextWhisper = now + 50 + r.nextInt(40);
		var sound = net.minecraft.core.Holder.direct(HwSounds.VECNA_WHISPER);
		t.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(sound, SoundSource.MASTER,
			t.getX() + r.nextGaussian(), t.getEyeY(), t.getZ() + r.nextGaussian(), 0.9f, 0.92f + r.nextFloat() * 0.16f, r.nextLong()));
		if (r.nextInt(3) > 0) {
			t.sendOverlayMessage(Component.translatable("halloween.voice.vecna." + r.nextInt(8)).withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.ITALIC));
		}
	}

	/**
	 * After the Anchor: he is already there, taking shape out of the dark (unseen at first, then a figure in a swirl
	 * of red), rooted, the clock striking for them every second. Time enough to run.
	 */
	private static void tickAnchor(ServerLevel level, ServerPlayer v, ShapeState st, int now) {
		if (anchorPhase == ANCHOR_CHOOSING && (st.busy() || Match.frozen())) {
			anchorPhase = ANCHOR_IDLE;
		}
		if (anchorPhase != ANCHOR_CHANNEL) {
			return;
		}
		ServerPlayer t = anchorTarget;
		int total = arriveTicks();
		if (t != null && !t.isRemoved() && anchorLeft % 20 == 0) {
			t.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(net.minecraft.core.Holder.direct(HwSounds.VECNA_ANCHOR_TOLL), SoundSource.MASTER,
				t.getX(), t.getEyeY(), t.getZ(), 1f, 1f, t.getRandom().nextLong()));
		}
		// The dark gathering into him: sparks drawn in from round about.
		for (int i = 0; i < 3; i++) {
			double a = v.getRandom().nextDouble() * Math.PI * 2;
			double r = 1.2 + v.getRandom().nextDouble();
			level.sendParticles(HwParticles.VECNA_SPARK, v.getX() + Math.cos(a) * r, v.getY() + v.getRandom().nextDouble() * 2, v.getZ() + Math.sin(a) * r, 0, -Math.cos(a), 0, -Math.sin(a), 0.14);
		}
		if (anchorLeft % 4 == 0) {
			level.sendParticles(HwParticles.VECNA_MIST, v.getX(), v.getY() + 1, v.getZ(), 1, 0.3, 0.6, 0.3, 0.005);
			level.sendParticles(HwParticles.VECNA_SMOKE, v.getX(), v.getY() + 0.3, v.getZ(), 2, 0.4, 0.2, 0.4, 0.01);
		}
		if (anchorLeft == total * 2 / 5) {
			// Now he shows.
			v.setInvisible(false);
			level.playSound(null, v.getX(), v.getY(), v.getZ(), HwSounds.VECNA_ANCHOR_REVEAL, SoundSource.HOSTILE, 1.6f, 1f);
		}
		if (--anchorLeft > 0) {
			return;
		}
		v.setInvisible(false);
		anchorPhase = ANCHOR_IDLE;
		anchorTarget = null;
	}

	/** Where he comes out: a spot he can stand on about this far from them, the one nearest that distance. */
	private static Vec3 arrival(ServerLevel level, ServerPlayer t, double dist) {
		return arrival(level, t.blockPosition(), dist);
	}

	private static Vec3 arrival(ServerLevel level, BlockPos c, double dist) {
		BlockPos best = null;
		double bestScore = Double.MAX_VALUE;
		int r = (int) Math.ceil(dist) + 3;
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				for (int dy = -2; dy <= 2; dy++) {
					BlockPos p = c.offset(dx, dy, dz);
					if (!Nav.standable(level, p) || !Nav.inArea(p)) {
						continue;
					}
					double score = Math.abs(Math.sqrt(dx * dx + dz * dz) - dist) + Math.abs(dy) * 2;
					if (score < bestScore) {
						bestScore = score;
						best = p;
					}
				}
			}
		}
		return best == null ? null : Vec3.atBottomCenterOf(best);
	}

	// ---------------------------------------------------------------- the Grip: aimed, thrown, dodged

	/** Holding the key: he stands with his hand thrust out; after a second's aim, letting go throws the Grip. */
	private static void tickAim(ServerLevel level, ServerPlayer v, ShapeState st, int now) {
		HwConfig cfg = HwConfig.get();
		boolean can = gripCooldown == 0 && !st.busy() && eyesOn == null && anchorPhase != ANCHOR_CHANNEL
			&& !Match.frozen();
		if (gripAim < 0) {
			if (st.senseHeld && can) {
				gripAim = 0;
				GrabSession.anim(v, HwNet.Anim.VECNA_CAST);
				level.playSound(null, v.getX(), v.getY(), v.getZ(), HwSounds.VECNA_GRIP_CHARGE, SoundSource.HOSTILE, 1f, 1f);
			}
			return;
		}
		if (!can) {
			stopAim(v);
			return;
		}
		if (st.senseHeld) {
			gripAim++;
			if (now % 2 == 0) {
				// Red sparks gather in the outstretched hand: everyone sees it coming.
				Vec3 hand = hand(v);
				level.sendParticles(gripAim >= cfg.vecnaGripAimTicks ? HwParticles.VECNA_EMBER : HwParticles.VECNA_SPARK, hand.x, hand.y, hand.z, 1, 0.1, 0.1, 0.1, 0.02);
			}
			if (gripAim == cfg.vecnaGripAimTicks) {
				level.playSound(null, v.getX(), v.getY(), v.getZ(), HwSounds.VECNA_GRIP_READY, SoundSource.HOSTILE, 1f, 1f);
			}
			return;
		}
		// Let go.
		if (gripAim < cfg.vecnaGripAimTicks) {
			v.sendOverlayMessage(Component.translatable("halloween.msg.vecna.grip_short").withStyle(ChatFormatting.GRAY));
			stopAim(v);
			return;
		}
		gripAim = -1;
		fire(level, v, st);
	}

	private static void stopAim(ServerPlayer v) {
		gripAim = -1;
		GrabSession.anim(v, HwNet.Anim.NONE);
	}

	/** His left hand, thrust out before him. */
	private static Vec3 hand(ServerPlayer v) {
		float yaw = v.getYRot() * Mth.DEG_TO_RAD;
		return v.position().add(-Mth.sin(yaw) * 0.7 + Mth.cos(yaw) * 0.35, 1.4, Mth.cos(yaw) * 0.7 + Mth.sin(yaw) * 0.35);
	}

	private static double gripSpeed() {
		return VecnaRitual.rift() ? HwConfig.get().vecnaRiftGripSpeed : HwConfig.get().vecnaGripSpeed;
	}

	/**
	 * The thrown Grip's body: a pillar of dark red light from the ground up into the sky, carried by an item display
	 * that the client draws as a beacon's beam (client.VecnaBeamRenderer).
	 */
	private static Display.ItemDisplay beam;

	private static void spawnBolt(ServerLevel level, Vec3 at, float yaw) {
		removeBars();
		Display.ItemDisplay d = new Display.ItemDisplay(net.minecraft.world.entity.EntityTypes.ITEM_DISPLAY, level);
		net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PAPER);
		stack.set(net.minecraft.core.component.DataComponents.ITEM_MODEL, Halloween.id("vecna_beam"));
		d.setItemStack(stack);
		d.setItemTransform(net.minecraft.world.item.ItemDisplayContext.FIXED);
		d.setBillboardConstraints(Display.BillboardConstraints.FIXED);
		d.setPosRotInterpolationDuration(1);
		// Seen from far off, as a pillar of light should be.
		d.setViewRange(8f);
		d.setPos(at.x, at.y, at.z);
		d.addTag(Leftovers.stamp());
		level.addFreshEntity(d);
		beam = d;
	}

	private static void moveBars(Vec3 at, float yaw) {
		if (beam != null && !beam.isRemoved()) {
			beam.setPos(at.x, at.y, at.z);
		}
	}

	private static void removeBars() {
		if (beam != null) {
			beam.discard();
		}
		beam = null;
	}

	/** Its way as a facing (the membrane turned across it). */
	private static float boltYaw() {
		return (float) (Math.atan2(-boltDir.x, boltDir.z) * 180 / Math.PI);
	}

	/** Thrown from his hand at whatever the middle of his view rests on. */
	private static void fire(ServerLevel level, ServerPlayer v, ShapeState st) {
		Vec3 from = MatchManager.aimOrigin(v, st);
		Vec3 look = MatchManager.aimDirection(v, st);
		Vec3 far = from.add(look.scale(gripRange() + 6));
		net.minecraft.world.phys.BlockHitResult hit = level.clip(new net.minecraft.world.level.ClipContext(from, far,
			net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, v));
		Vec3 aimAt = hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS ? far : hit.getLocation();
		// Someone under the crosshair (nearer than the wall): the Grip goes for them. The camera is over his shoulder and
		// the Grip leaves his hand, so it is aimed at the point the crosshair rests on, not along his body's facing.
		net.minecraft.world.phys.EntityHitResult who = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(level, v, from, aimAt,
			new AABB(from, aimAt).inflate(1.5), e -> e instanceof LivingEntity le && e != v && grippable(le), 0.3f);
		if (who != null) {
			aimAt = FocusPoints.bodyCenter((LivingEntity) who.getEntity());
		}
		Vec3 hand = hand(v);
		Vec3 dir = aimAt.subtract(hand);
		// An upright pillar: only its way over the ground matters.
		dir = new Vec3(dir.x, 0, dir.z);
		bolt = hand;
		boltDir = dir.lengthSqr() < 4 ? look : dir.normalize();
		boltLeft = gripRange();
		spawnBolt(level, hand, boltYaw());
		// A low tearing groan as it leaves his hand, and a burst of the dark.
		level.playSound(null, hand.x, hand.y, hand.z, HwSounds.VECNA_GRIP_CAST, SoundSource.HOSTILE, 1.6f, 1f);
		level.sendParticles(HwParticles.VECNA_SMOKE, hand.x, hand.y, hand.z, 10, 0.2, 0.3, 0.2, 0.04);
		level.sendParticles(HwParticles.VECNA_SPARK, hand.x, hand.y, hand.z, 10, 0.1, 0.1, 0.1, 0.18);
		AiStats.add(v, "vecna.grip.thrown");
	}

	/**
	 * The Grip in flight: a dark membrane standing across its way, as tall as a man and wider than one, a few strides a
	 * second; a good step aside is enough to let it by. A wall stops it; whoever it touches first (survivor,
	 * townsperson, officer) is caught.
	 */
	private static void tickBolt(ServerLevel level, ServerPlayer v, ShapeState st, int now) {
		HwConfig cfg = HwConfig.get();
		if (st.stunnedTicks > 0) {
			burst(level);
			missed(level, v);
			return;
		}
		if (VecnaRitual.rift()) {
			// The rift open, he steers it: it bends towards where he looks now, as far off as it already is.
			Vec3 from = MatchManager.aimOrigin(v, st);
			Vec3 look = MatchManager.aimDirection(v, st);
			Vec3 want = from.add(look.scale(from.distanceTo(bolt) + 4)).subtract(bolt);
			if (want.lengthSqr() > 0.01) {
				boltDir = boltDir.scale(1 - cfg.vecnaRiftSteer).add(want.normalize().scale(cfg.vecnaRiftSteer)).normalize();
			}
		}
		double step = gripSpeed() / 20.0;
		int parts = Math.max(1, (int) Math.ceil(step / 0.2));
		double half = cfg.vecnaGripWidth / 2;
		double tall = cfg.vecnaGripHeight / 2;
		Vec3 flat = new Vec3(boltDir.x, 0, boltDir.z);
		Vec3 along = flat.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : flat.normalize();
		Vec3 across = new Vec3(-along.z, 0, along.x);
		for (int i = 0; i < parts; i++) {
			Vec3 next = bolt.add(boltDir.scale(step / parts));
			bolt = next;
			boltLeft -= step / parts;
			AABB reach = new AABB(bolt.x - half - 1, bolt.y - tall - 1, bolt.z - half - 1, bolt.x + half + 1, bolt.y + 256, bolt.z + half + 1);
			for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, reach, Vecna::grippable)) {
				if (e == v) {
					continue;
				}
				// Inside the pillar, at any height (it goes up to the sky): within its width, round its middle.
				double dx = e.getX() - bolt.x;
				double dz = e.getZ() - bolt.z;
				double reachR = half + e.getBbWidth() / 2;
				if (dx * dx + dz * dz <= reachR * reachR && e.getBoundingBox().maxY >= bolt.y - tall) {
					catchHold(level, v, e, now);
					return;
				}
			}
		}
		moveBars(bolt, boltYaw());
		// Sparks of red light drifting up the pillar.
		for (int k = 0; k < 2; k++) {
			double up = level.getRandom().nextDouble() * 20;
			level.sendParticles(HwParticles.VECNA_EMBER, bolt.x, bolt.y + up, bolt.z, 1, half * 0.5, 0.3, half * 0.5, 0.01);
		}
		// It sheds the dark behind it: smoke, ash, red dust fading to black; whispers as it passes.
		Vec3 back = bolt.subtract(along.scale(0.4));
		level.sendParticles(HwParticles.VECNA_SMOKE, back.x, back.y, back.z, 2, across.x * half * 0.6, tall * 0.5, across.z * half * 0.6, 0.01);
		level.sendParticles(HwParticles.VECNA_ASH, back.x, back.y, back.z, 2, half * 0.7, tall * 0.7, half * 0.7, 0.01);
		if (now % 3 == 0) {
			level.sendParticles(HwParticles.VECNA_MIST, back.x, back.y, back.z, 1, half * 0.4, tall * 0.4, half * 0.4, 0.005);
		}
		if (now % 20 == 0) {
			level.playSound(null, bolt.x, bolt.y, bolt.z, HwSounds.VECNA_GRIP_HUM, SoundSource.HOSTILE, 1.4f, 1f);
		}
		if (boltLeft <= 0) {
			burst(level);
			missed(level, v);
		}
	}

	/** The membrane coming apart where it stops: a puff of the dark, a hiss. */
	private static void burst(ServerLevel level) {
		if (bolt == null) {
			return;
		}
		double tall = HwConfig.get().vecnaGripHeight / 2;
		level.sendParticles(HwParticles.VECNA_SMOKE, bolt.x, bolt.y, bolt.z, 14, 0.5, tall * 0.5, 0.5, 0.03);
		level.sendParticles(HwParticles.VECNA_SPARK, bolt.x, bolt.y, bolt.z, 8, 0.3, tall * 0.4, 0.3, 0.1);
		level.playSound(null, bolt.x, bolt.y, bolt.z, HwSounds.VECNA_GRIP_FIZZLE, SoundSource.HOSTILE, 1f, 1f);
	}

	/** Gone by, into a wall or out of reach: a short rest. */
	private static void missed(ServerLevel level, ServerPlayer v) {
		bolt = null;
		removeBars();
		gripCooldown = HwConfig.get().vecnaGripMissCooldownTicks;
		gripCooldownMax = gripCooldown;
		GrabSession.anim(v, HwNet.Anim.NONE);
	}

	private static boolean grippable(LivingEntity e) {
		if (e instanceof ServerPlayer s && (s.isSpectator() || !MatchManager.role(s).survivor())) {
			return false;
		}
		return e.isAlive() && MatchManager.exposed(e) && MatchManager.grabHolding(e) == null;
	}

	private static void catchHold(ServerLevel level, ServerPlayer v, LivingEntity target, int now) {
		bolt = null;
		removeBars();
		gripped = target;
		gripSince = now;
		gripLost = 0;
		gripCooldown = HwConfig.get().vecnaGripCooldownTicks;
		gripCooldownMax = gripCooldown;
		root(target, true);
		gripKeyWas = true;
		level.playSound(null, target.getX(), target.getY(), target.getZ(), HwSounds.VECNA_GRIP_CATCH, SoundSource.HOSTILE, 1.2f, 1f);
		// The dark closes over them as it takes hold.
		level.sendParticles(HwParticles.VECNA_SMOKE, target.getX(), target.getY() + 1, target.getZ(), 12, 0.4, 0.7, 0.4, 0.03);
		level.sendParticles(HwParticles.VECNA_SPARK, target.getX(), target.getY() + 1, target.getZ(), 12, 0.3, 0.6, 0.3, 0.15);
		level.sendParticles(HwParticles.VECNA_MIST, target.getX(), target.getY() + 1, target.getZ(), 2, 0.3, 0.5, 0.3, 0.005);
		if (target instanceof ServerPlayer sp) {
			sp.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 140, 0, true, false));
		}
		Panic.add(target, HwConfig.get().panicOnGrab);
		AiStats.add(v, "vecna.grip");
	}

	/** Caught: drawn to him by itself; at his hand, he takes them by the throat. */
	private static void tickGrip(ServerLevel level, ServerPlayer v, ShapeState st, int now) {
		HwConfig cfg = HwConfig.get();
		LivingEntity t = gripped;
		if (st.stunnedTicks > 0 || st.grab != null || !grippable(t) || t.level() != level || t.distanceTo(v) > gripRange() * 1.5) {
			// Stunned, busy with someone else, or they are out of his reach (a wardrobe, someone else's hands).
			release(level, v, st.stunnedTicks > 0);
			return;
		}
		if (st.senseHeld && !gripKeyWas) {
			// He lets them go himself (the key pressed again).
			release(level, v, false);
			gripKeyWas = true;
			return;
		}
		gripKeyWas = st.senseHeld;
		if (now - gripSince > cfg.vecnaGripPullTicks) {
			// Too long (a wall in the way, most likely): the bond snaps, and it throws him down.
			release(level, v, false);
			st.stunnedTicks = Math.max(st.stunnedTicks, cfg.vecnaGripSnapStunTicks);
			st.senseHeld = false;
			GrabSession.anim(v, HwNet.Anim.FALL);
			level.playSound(null, v.getX(), v.getY(), v.getZ(), HwSounds.VECNA_GRIP_SNAP, SoundSource.HOSTILE, 1.4f, 1f);
			v.sendOverlayMessage(Component.translatable("halloween.msg.vecna.snap").withStyle(ChatFormatting.DARK_RED));
			return;
		}
		Vec3 to = new Vec3(v.getX() - t.getX(), 0, v.getZ() - t.getZ());
		double dist = to.length();
		boolean clear = Sight.canSee(level, v.getEyePosition(), FocusPoints.bodyCenter(t), v);
		if (dist <= 1.8 && clear) {
			// At his hand: straight into a stranglehold.
			gripped = null;
			root(t, false);
			if (!MatchManager.vecnaGrab(v, t)) {
				GrabSession.anim(v, HwNet.Anim.NONE);
			}
			return;
		}
		// Drawn towards him over the ground (they keep their own eyes, and their gun).
		Vec3 pull = to.scale((VecnaRitual.rift() ? cfg.vecnaRiftGripPull : cfg.vecnaGripPull) / 20.0 / dist);
		if (!clear && !level.noCollision(t, t.getBoundingBox().move(pull.x * 3, 0.05, pull.z * 3))) {
			// A wall between them: held against it, not slid along it (and not taken through it).
			pull = Vec3.ZERO;
		}
		t.setDeltaMovement(pull.x, -0.1, pull.z);
		t.hurtMarked = true;
		MatchManager.addStalk(v, st, t, cfg.vecnaGripStalkPerSecond / 20f);
		beam(level, v, t, now);
		if ((now - gripSince) % 15 == 0) {
			level.playSound(null, t.getX(), t.getY(), t.getZ(), HwSounds.VECNA_GRIP_PULL, SoundSource.HOSTILE, 1.2f, 0.95f + t.getRandom().nextFloat() * 0.1f);
		}
		if (t instanceof ServerPlayer sp && (now - gripSince) % 10 == 0) {
			sp.sendOverlayMessage(Component.translatable("halloween.msg.vecna.gripped").withStyle(ChatFormatting.DARK_RED));
		}
	}

	/** The Grip while it draws someone: a quivering thread of red from his outstretched hand to their chest. */
	private static void beam(ServerLevel level, ServerPlayer v, LivingEntity t, int now) {
		Vec3 hand = hand(v);
		Vec3 chest = FocusPoints.bodyCenter(t);
		Vec3 d = chest.subtract(hand);
		double len = d.length();
		if (len < 0.01) {
			return;
		}
		Vec3 side = d.cross(new Vec3(0, 1, 0)).normalize();
		Vec3 up = side.cross(d).normalize();
		int n = Math.max(10, (int) (len / 0.12));
		for (int i = 0; i <= n; i++) {
			double k = i / (double) n;
			// It quivers a little (still at the ends); much more and the lingering dust turns the thread into a band.
			double sway = Math.sin(k * Math.PI) * 0.06;
			double a = k * 9 - now * 0.35;
			Vec3 at = hand.add(d.scale(k)).add(side.scale(Math.sin(a) * sway)).add(up.scale(Math.cos(a * 0.7) * sway));
			level.sendParticles(HwParticles.VECNA_THREAD, at.x, at.y, at.z, 1, 0, 0, 0, 0);
		}
		if (now % 3 == 0) {
			level.sendParticles(HwParticles.VECNA_SPARK, chest.x, chest.y, chest.z, 2, 0.2, 0.3, 0.2, 0.06);
		}
	}

	/** The Grip broken before they reach him: they can walk again. */
	private static void release(ServerLevel level, ServerPlayer v, boolean stunned) {
		if (gripped == null) {
			return;
		}
		LivingEntity t = gripped;
		t.removeEffect(MobEffects.DARKNESS);
		gripped = null;
		root(t, false);
		if (v != null) {
			GrabSession.anim(v, HwNet.Anim.NONE);
		}
		level.playSound(null, t.getX(), t.getY(), t.getZ(), HwSounds.VECNA_GRIP_RELEASE, SoundSource.HOSTILE, 1f, 1f);
		if (t instanceof ServerPlayer sp) {
			sp.sendOverlayMessage(Component.translatable(stunned ? "halloween.msg.vecna.freed_stun" : "halloween.msg.vecna.freed").withStyle(ChatFormatting.GRAY));
		}
	}

	private static void root(LivingEntity e, boolean on) {
		AttributeInstance speed = e.getAttribute(Attributes.MOVEMENT_SPEED);
		AttributeInstance jump = e.getAttribute(Attributes.JUMP_STRENGTH);
		for (AttributeInstance a : new AttributeInstance[]{speed, jump}) {
			if (a == null) {
				continue;
			}
			if (on) {
				a.addOrUpdateTransientModifier(new AttributeModifier(GRIPPED_ID, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
			} else {
				a.removeModifier(GRIPPED_ID);
			}
		}
		if (!on) {
			e.setDeltaMovement(Vec3.ZERO);
			e.hurtMarked = true;
		}
	}

	/** Back to school: nothing of the Grip left on them. */
	public static void reset(ServerPlayer p) {
		root(p, false);
		if (p.isInvisible()) {
			p.setInvisible(false);
		}
	}

	/** A match over (or starting): nobody held, no rest. */
	public static void clear(ServerLevel level) {
		if (anchorPhase == ANCHOR_CHANNEL) {
			ServerPlayer v = Senses.shape(level);
			if (v != null) {
				v.setInvisible(false);
			}
		}
		gripAim = -1;
		bolt = null;
		gripCooldownMax = 1;
		removeBars();
		if (gripped != null) {
			root(gripped, false);
			gripped = null;
		}
		gripCooldown = 0;
		gripLost = 0;
		eyesOn = null;
		eyesReserve = HwConfig.get().vecnaEyesMaxTicks;
		eyesIdle = 0;
		anchorPhase = ANCHOR_IDLE;
		anchorTarget = null;
		anchorLeft = 0;
		anchorCooldown = 0;
	}
}
