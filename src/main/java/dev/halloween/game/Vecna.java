package dev.halloween.game;

import dev.halloween.Halloween;
import dev.halloween.HwConfig;
import dev.halloween.net.HwNet;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.LivingEntity;
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
 * <li>The curse ({@link VecnaCurse}) and the telephone booths that lift it ({@link Booths}).</li>
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
	private static Vec3 bolt;
	private static Vec3 boltDir = Vec3.ZERO;
	private static double boltLeft;
	/** The Eyes: when the next whisper comes to the one he looks through. */
	private static int nextWhisper;
	/** The Eyes: whose eyes he looks through (null: his own), the seconds of looking in reserve (ticks), ticks since the last refill. */
	private static ServerPlayer eyesOn;
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

	/** Standing still: aiming or throwing the Grip or drawing someone in, looking through the Eyes, or taking shape after the Anchor. */
	public static boolean rooted() {
		return active() && (gripAim >= 0 || bolt != null || gripped != null || eyesOn != null || anchorPhase == ANCHOR_CHANNEL);
	}

	/** The Grip in flight. */
	public static boolean flying() {
		return active() && bolt != null;
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
		if (st.busy() || gripped != null || bolt != null || gripAim >= 0 || anchorPhase == ANCHOR_CHANNEL || VecnaRitual.carrying() || Match.frozen()) {
			return;
		}
		if (eyesReserve < 20) {
			p.sendOverlayMessage(Component.translatable("halloween.msg.vecna.eyes_tired").withStyle(ChatFormatting.GRAY));
			return;
		}
		ServerPlayer nearest = null;
		for (ServerPlayer s : p.level().players()) {
			if (MatchManager.role(s).survivor() && !s.isSpectator() && s.isAlive() && (nearest == null || s.distanceTo(p) < nearest.distanceTo(p))) {
				nearest = s;
			}
		}
		if (nearest == null) {
			return;
		}
		eyesOn = nearest;
		nextWhisper = 0;
		p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENDER_EYE_DEATH, SoundSource.HOSTILE, 1f, 0.5f);
		AiStats.add(p, "vecna.eyes");
	}

	private static void eyesOff(ServerPlayer p) {
		if (eyesOn == null) {
			return;
		}
		eyesOn = null;
		eyesIdle = 0;
		if (p != null) {
			p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENDER_EYE_LAUNCH, SoundSource.HOSTILE, 0.8f, 0.5f);
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
		if (st.busy() || gripped != null || eyesOn != null || bolt != null || VecnaRitual.carrying() || Match.frozen()) {
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
		level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.5f, 0.5f);
		level.sendParticles(ParticleTypes.REVERSE_PORTAL, p.getX(), p.getY() + 1, p.getZ(), 30, 0.4, 0.9, 0.4, 0.1);
		Vec3 d = target.position().subtract(to);
		float yaw = (float) (Math.atan2(-d.x, d.z) * 180 / Math.PI);
		p.teleportTo(level, to.x, to.y, to.z, java.util.Set.of(), yaw, 0f, false);
		p.setInvisible(true);
		level.playSound(null, to.x, to.y, to.z, SoundEvents.PORTAL_TRIGGER, SoundSource.HOSTILE, 1f, 0.6f);
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
		if (now % 2 == 0) {
			List<HwNet.CurseMark> marks = new ArrayList<>();
			if (anchorPhase == ANCHOR_CHOOSING) {
				for (ServerPlayer s : level.players()) {
					int stage = MatchManager.role(s).survivor() && !s.isSpectator() && s.isAlive() ? VecnaCurse.stage(s) : 0;
					if (stage > 0 || VecnaRitual.rift() && MatchManager.role(s).survivor() && !s.isSpectator() && s.isAlive()) {
						marks.add(new HwNet.CurseMark(s.getId(), FocusPoints.bodyCenter(s), stage, s.getDisplayName().getString()));
					}
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
		ServerPlayer t = eyesOn;
		if (--eyesReserve <= 0 || st.stunnedTicks > 0 || st.grab != null || t.isRemoved() || t.isSpectator() || !t.isAlive()
			|| !MatchManager.role(t).survivor()) {
			eyesReserve = Math.max(0, eyesReserve);
			eyesOff(v);
			return;
		}
		MatchManager.addStalk(v, st, t, cfg.vecnaEyesStalkPerSecond / 20f);
		whisper(t);
	}

	/** Voices in the head of the one he looks through: whispers, now and then words. */
	private static void whisper(ServerPlayer t) {
		int now = t.level().getServer().getTickCount();
		if (now < nextWhisper) {
			return;
		}
		var r = t.getRandom();
		nextWhisper = now + 30 + r.nextInt(30);
		net.minecraft.core.Holder<net.minecraft.sounds.SoundEvent> sound = r.nextBoolean() ? SoundEvents.AMBIENT_CAVE
			: net.minecraft.core.Holder.direct(r.nextBoolean() ? SoundEvents.SOUL_ESCAPE.value() : SoundEvents.WARDEN_NEARBY_CLOSE);
		t.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(sound, SoundSource.MASTER,
			t.getX() + r.nextGaussian(), t.getEyeY(), t.getZ() + r.nextGaussian(), 0.9f, 0.5f + r.nextFloat() * 0.2f, r.nextLong()));
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
			t.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(net.minecraft.core.Holder.direct(SoundEvents.BELL_BLOCK), SoundSource.MASTER,
				t.getX(), t.getEyeY(), t.getZ(), 1.4f, 0.5f, t.getRandom().nextLong()));
		}
		// The dark gathering into him: sparks drawn in from round about.
		for (int i = 0; i < 6; i++) {
			double a = v.getRandom().nextDouble() * Math.PI * 2;
			double r = 1.2 + v.getRandom().nextDouble();
			level.sendParticles(ParticleTypes.REVERSE_PORTAL, v.getX() + Math.cos(a) * r, v.getY() + v.getRandom().nextDouble() * 2, v.getZ() + Math.sin(a) * r, 1, 0, 0, 0, 0.05);
		}
		level.sendParticles(new DustParticleOptions(0x8A0E1E, 1.2f), v.getX(), v.getY() + 1, v.getZ(), 4, 0.3, 0.8, 0.3, 0);
		if (anchorLeft == total * 2 / 5) {
			// Now he shows.
			v.setInvisible(false);
			level.playSound(null, v.getX(), v.getY(), v.getZ(), SoundEvents.BELL_BLOCK, SoundSource.HOSTILE, 1.4f, 0.5f);
			level.playSound(null, v.getX(), v.getY(), v.getZ(), SoundEvents.WARDEN_EMERGE, SoundSource.HOSTILE, 1f, 1.2f);
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
		BlockPos c = t.blockPosition();
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
			&& !VecnaRitual.carrying() && !Match.frozen();
		if (gripAim < 0) {
			if (st.senseHeld && can) {
				gripAim = 0;
				GrabSession.anim(v, HwNet.Anim.VECNA_CAST);
				level.playSound(null, v.getX(), v.getY(), v.getZ(), SoundEvents.EVOKER_PREPARE_ATTACK, SoundSource.HOSTILE, 1.2f, 0.5f);
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
				level.sendParticles(new DustParticleOptions(gripAim >= cfg.vecnaGripAimTicks ? 0xFF3020 : 0x8A0E1E, 0.9f), hand.x, hand.y, hand.z, 3, 0.12, 0.12, 0.12, 0);
			}
			if (gripAim == cfg.vecnaGripAimTicks) {
				level.playSound(null, v.getX(), v.getY(), v.getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.HOSTILE, 1.4f, 0.5f);
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

	/** The thrown Grip's body: an upright bar of red light, a bright core in a glowing sheath (block displays). */
	private static Display.BlockDisplay boltCore;
	private static Display.BlockDisplay boltGlow;

	private static Display.BlockDisplay bar(ServerLevel level, Vec3 at, net.minecraft.world.level.block.state.BlockState block, float width) {
		HwConfig cfg = HwConfig.get();
		Display.BlockDisplay d = new Display.BlockDisplay(net.minecraft.world.entity.EntityTypes.BLOCK_DISPLAY, level);
		d.setBlockState(block);
		float h = (float) cfg.vecnaGripHeight;
		d.setTransformation(new com.mojang.math.Transformation(new org.joml.Vector3f(-width / 2, -h / 2, -width / 2), null,
			new org.joml.Vector3f(width, h, width), null));
		d.setBrightnessOverride(new net.minecraft.util.Brightness(15, 15));
		d.setPosRotInterpolationDuration(1);
		d.setViewRange(3f);
		d.setPos(at.x, at.y, at.z);
		d.addTag(Leftovers.stamp());
		level.addFreshEntity(d);
		return d;
	}

	private static void moveBars(Vec3 at) {
		for (Display.BlockDisplay d : new Display.BlockDisplay[]{boltCore, boltGlow}) {
			if (d != null && !d.isRemoved()) {
				d.setPos(at.x, at.y, at.z);
			}
		}
	}

	private static void removeBars() {
		for (Display.BlockDisplay d : new Display.BlockDisplay[]{boltCore, boltGlow}) {
			if (d != null) {
				d.discard();
			}
		}
		boltCore = null;
		boltGlow = null;
	}

	/** Thrown from his hand at whatever the middle of his view rests on. */
	private static void fire(ServerLevel level, ServerPlayer v, ShapeState st) {
		Vec3 from = MatchManager.aimOrigin(v, st);
		Vec3 look = MatchManager.aimDirection(v, st);
		Vec3 far = from.add(look.scale(gripRange() + 6));
		net.minecraft.world.phys.BlockHitResult hit = level.clip(new net.minecraft.world.level.ClipContext(from, far,
			net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, v));
		Vec3 aimAt = hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS ? far : hit.getLocation();
		Vec3 hand = hand(v);
		Vec3 dir = aimAt.subtract(hand);
		bolt = hand;
		boltDir = dir.lengthSqr() < 4 ? look : dir.normalize();
		boltLeft = gripRange();
		removeBars();
		boltCore = bar(level, hand, net.minecraft.world.level.block.Blocks.REDSTONE_BLOCK.defaultBlockState(), 0.07f);
		boltGlow = bar(level, hand, MapPlan.b("red_stained_glass"), 0.22f);
		level.playSound(null, hand.x, hand.y, hand.z, SoundEvents.EVOKER_CAST_SPELL, SoundSource.HOSTILE, 1.5f, 0.6f);
		level.playSound(null, hand.x, hand.y, hand.z, SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.HOSTILE, 0.6f, 1.6f);
		AiStats.add(v, "vecna.grip.thrown");
	}

	/**
	 * The Grip in flight: a standing streak of red, as tall as a man and narrow, a few strides a second; a step aside
	 * is enough to let it by. A wall stops it; whoever it touches first (survivor, townsperson, officer) is caught.
	 */
	private static void tickBolt(ServerLevel level, ServerPlayer v, ShapeState st, int now) {
		HwConfig cfg = HwConfig.get();
		if (st.stunnedTicks > 0) {
			missed(level, v);
			return;
		}
		double step = gripSpeed() / 20.0;
		int parts = Math.max(1, (int) Math.ceil(step / 0.2));
		double half = cfg.vecnaGripWidth / 2;
		double tall = cfg.vecnaGripHeight / 2;
		for (int i = 0; i < parts; i++) {
			Vec3 next = bolt.add(boltDir.scale(step / parts));
			if (level.clip(new net.minecraft.world.level.ClipContext(bolt, next, net.minecraft.world.level.ClipContext.Block.COLLIDER,
				net.minecraft.world.level.ClipContext.Fluid.NONE, v)).getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
				level.sendParticles(ParticleTypes.SMOKE, bolt.x, bolt.y, bolt.z, 10, 0.1, 0.6, 0.1, 0.02);
				missed(level, v);
				return;
			}
			bolt = next;
			boltLeft -= step / parts;
			AABB column = new AABB(bolt.x - half, bolt.y - tall, bolt.z - half, bolt.x + half, bolt.y + tall, bolt.z + half);
			for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, column.inflate(1), Vecna::grippable)) {
				if (e != v && e.getBoundingBox().intersects(column)) {
					catchHold(level, v, e, now);
					return;
				}
			}
		}
		// Seen by everyone: the bar of red light (moved smoothly by the client), shedding a few sparks.
		moveBars(bolt);
		level.sendParticles(new DustParticleOptions(0xFF3A2A, 0.7f), bolt.x, bolt.y, bolt.z, 2, 0.04, tall * 0.6, 0.04, 0);
		if (now % 4 == 0) {
			level.playSound(null, bolt.x, bolt.y, bolt.z, SoundEvents.SOUL_ESCAPE.value(), SoundSource.HOSTILE, 1.2f, 0.6f);
		}
		if (boltLeft <= 0) {
			level.sendParticles(ParticleTypes.SMOKE, bolt.x, bolt.y, bolt.z, 10, 0.1, 0.6, 0.1, 0.02);
			missed(level, v);
		}
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
		level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.HOSTILE, 0.8f, 0.6f);
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
		Vec3 eye = v.getEyePosition();
		Vec3 body = FocusPoints.bodyCenter(t);
		gripLost = Sight.canSee(level, eye, body, v) ? 0 : gripLost + 1;
		if (gripLost > cfg.vecnaGripLostTicks) {
			release(level, v, false);
			return;
		}
		Vec3 to = new Vec3(v.getX() - t.getX(), 0, v.getZ() - t.getZ());
		double dist = to.length();
		if (dist <= 1.8) {
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
		t.setDeltaMovement(pull.x, -0.1, pull.z);
		t.hurtMarked = true;
		MatchManager.addStalk(v, st, t, cfg.vecnaGripStalkPerSecond / 20f);
		beam(level, v, t, now);
		if ((now - gripSince) % 15 == 0) {
			level.playSound(null, t.getX(), t.getY(), t.getZ(), SoundEvents.BEACON_AMBIENT, SoundSource.HOSTILE, 1.4f, 0.5f);
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
		int n = Math.max(6, (int) (len / 0.3));
		DustParticleOptions dark = new DustParticleOptions(0x8A0E1E, 0.6f);
		DustParticleOptions glow = new DustParticleOptions(0xFF4A3A, 0.45f);
		for (int i = 0; i <= n; i++) {
			double k = i / (double) n;
			// It quivers a little (still at the ends); much more and the lingering dust turns the thread into a band.
			double sway = Math.sin(k * Math.PI) * 0.06;
			double a = k * 9 - now * 0.35;
			Vec3 at = hand.add(d.scale(k)).add(side.scale(Math.sin(a) * sway)).add(up.scale(Math.cos(a * 0.7) * sway));
			level.sendParticles(i % 3 == 0 ? glow : dark, at.x, at.y, at.z, 1, 0, 0, 0, 0);
		}
		if (now % 3 == 0) {
			level.sendParticles(ParticleTypes.REVERSE_PORTAL, chest.x, chest.y, chest.z, 3, 0.25, 0.4, 0.25, 0.02);
		}
	}

	/** The Grip broken before they reach him: they can walk again. */
	private static void release(ServerLevel level, ServerPlayer v, boolean stunned) {
		if (gripped == null) {
			return;
		}
		LivingEntity t = gripped;
		gripped = null;
		root(t, false);
		if (v != null) {
			GrabSession.anim(v, HwNet.Anim.NONE);
		}
		level.playSound(null, t.getX(), t.getY(), t.getZ(), SoundEvents.BEACON_DEACTIVATE, SoundSource.HOSTILE, 1.2f, 0.6f);
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
