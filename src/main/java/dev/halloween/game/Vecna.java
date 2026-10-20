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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/**
 * Vecna (Stranger Things), the third killer (/hw killer vecna): played by a person (a bot always plays Michael). As
 * slow as Michael and without his dash, Sense or Impulse: he wins with his head. Grabbing, the struggle and the kill
 * are Michael's; his own powers take Michael's keys.
 * <ul>
 * <li>The Grip (held Killer Sense key): a survivor under his aim within reach is caught. While he holds the key and
 * keeps them in sight they cannot walk (they can still shoot) and are drawn slowly towards him; he stands still,
 * turning only. It breaks when he lets go, loses sight of them or is stunned; then a long rest. Every second held
 * stalks them.</li>
 * <li>The Eyes (Shape Jump key): he sees through the eyes of the survivor nearest him, standing still, for as long as
 * the reserve lasts (it fills again while he doesn't look); seeing them stalks them. A stun ends it.</li>
 * <li>The Anchor (Impulse key, held): the cursed show with their stage; let go, he goes to the one under his aim
 * (three seconds standing, the clock striking for them), coming out a few steps from them. Then a long rest.</li>
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
	/** The Eyes: whose eyes he looks through (null: his own), the seconds of looking in reserve (ticks), ticks since the last refill. */
	private static ServerPlayer eyesOn;
	private static int eyesReserve;
	private static int eyesIdle;
	/** The Anchor: choosing (the key held), the delay before he goes, its target and ticks left; then its rest. */
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

	/** Standing still: holding the Grip, looking through the Eyes, or about to go by the Anchor. */
	public static boolean rooted() {
		return active() && (gripped != null || eyesOn != null || anchorPhase == ANCHOR_CHANNEL);
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
		if (st.busy() || gripped != null || anchorPhase == ANCHOR_CHANNEL || VecnaRitual.carrying() || Match.frozen()) {
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
		if (st.busy() || gripped != null || eyesOn != null || VecnaRitual.carrying() || Match.frozen()) {
			return;
		}
		anchorPhase = ANCHOR_CHANNEL;
		anchorTarget = target;
		anchorLeft = VecnaRitual.rift() ? HwConfig.get().vecnaRiftAnchorDelayTicks : HwConfig.get().vecnaAnchorDelayTicks;
		ServerLevel level = p.level();
		level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PORTAL_TRIGGER, SoundSource.HOSTILE, 0.8f, 0.6f);
		target.sendOverlayMessage(Component.translatable("halloween.msg.vecna.anchor_coming").withStyle(ChatFormatting.DARK_RED));
		AiStats.add(p, "vecna.anchor");
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
			release(level, false);
			eyesOff(null);
			anchorPhase = ANCHOR_IDLE;
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
		} else if (st.senseHeld && gripCooldown == 0 && !st.busy() && eyesOn == null && anchorPhase != ANCHOR_CHANNEL && !VecnaRitual.carrying() && !Match.frozen()) {
			// Holding the key with nobody caught yet: whoever comes under his aim is.
			LivingEntity target = aimed(level, v, st);
			if (target != null) {
				catchHold(level, v, target, now);
			}
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
			ServerPlayNetworking.send(v, new HwNet.VecnaHud(gripCooldown, cfg.vecnaGripCooldownTicks, gripped == null ? -1 : gripped.getId(),
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
	}

	/** The Anchor's delay: he stands, the clock strikes for them, then he is there. */
	private static void tickAnchor(ServerLevel level, ServerPlayer v, ShapeState st, int now) {
		if (anchorPhase == ANCHOR_CHOOSING && (st.busy() || Match.frozen())) {
			anchorPhase = ANCHOR_IDLE;
		}
		if (anchorPhase != ANCHOR_CHANNEL) {
			return;
		}
		HwConfig cfg = HwConfig.get();
		ServerPlayer t = anchorTarget;
		if (st.stunnedTicks > 0 || st.grab != null || t == null || t.isRemoved() || t.isSpectator() || !t.isAlive()) {
			// Stunned, or nobody to go to any more: it fails, and rests all the same.
			anchorPhase = ANCHOR_IDLE;
			anchorTarget = null;
			anchorCooldown = anchorRest();
			return;
		}
		if (anchorLeft % 20 == 0) {
			// The clock strikes for them: he is coming.
			t.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(net.minecraft.core.Holder.direct(SoundEvents.BELL_BLOCK), SoundSource.MASTER,
				t.getX(), t.getEyeY(), t.getZ(), 1.4f, 0.5f, t.getRandom().nextLong()));
		}
		level.sendParticles(ParticleTypes.REVERSE_PORTAL, v.getX(), v.getY() + 1, v.getZ(), 8, 0.4, 0.9, 0.4, 0.05);
		if (--anchorLeft > 0) {
			return;
		}
		anchorPhase = ANCHOR_IDLE;
		anchorTarget = null;
		anchorCooldown = anchorRest();
		Vec3 to = arrival(level, t, cfg.vecnaAnchorDistance);
		if (to == null) {
			v.sendOverlayMessage(Component.translatable("halloween.msg.vecna.anchor_none").withStyle(ChatFormatting.GRAY));
			return;
		}
		level.playSound(null, v.getX(), v.getY(), v.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.5f, 0.5f);
		Vec3 d = t.position().subtract(to);
		float yaw = (float) (Math.atan2(-d.x, d.z) * 180 / Math.PI);
		v.teleportTo(level, to.x, to.y, to.z, java.util.Set.of(), yaw, 0f, false);
		level.playSound(null, to.x, to.y, to.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.5f, 0.5f);
		level.playSound(null, to.x, to.y, to.z, SoundEvents.BELL_BLOCK, SoundSource.HOSTILE, 1.2f, 0.5f);
		level.sendParticles(ParticleTypes.REVERSE_PORTAL, to.x, to.y + 1, to.z, 40, 0.4, 0.9, 0.4, 0.1);
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

	/** The survivor nearest his aim within reach and the cone, in sight. */
	private static LivingEntity aimed(ServerLevel level, ServerPlayer v, ShapeState st) {
		HwConfig cfg = HwConfig.get();
		Vec3 from = MatchManager.aimOrigin(v, st);
		Vec3 look = MatchManager.aimDirection(v, st);
		Vec3 eye = v.getEyePosition();
		LivingEntity best = null;
		double bestAngle = VecnaRitual.rift() ? cfg.vecnaRiftGripAngleDeg : cfg.vecnaGripAngleDeg;
		for (ServerPlayer s : level.players()) {
			if (!grippable(s)) {
				continue;
			}
			Vec3 body = FocusPoints.bodyCenter(s);
			if (eye.distanceTo(body) > gripRange()) {
				continue;
			}
			double angle = Sight.angleTo(from, look, body);
			if (angle <= bestAngle && Sight.canSee(level, eye, body, v)) {
				best = s;
				bestAngle = angle;
			}
		}
		return best;
	}

	private static boolean grippable(LivingEntity e) {
		return e instanceof ServerPlayer s && MatchManager.role(s).survivor() && !s.isSpectator() && s.isAlive()
			&& MatchManager.exposed(s) && !MatchManager.isGrabbed(s);
	}

	private static void catchHold(ServerLevel level, ServerPlayer v, LivingEntity target, int now) {
		gripped = target;
		gripSince = now;
		gripLost = 0;
		root(target, true);
		level.playSound(null, v.getX(), v.getY(), v.getZ(), SoundEvents.EVOKER_PREPARE_ATTACK, SoundSource.HOSTILE, 1.2f, 0.5f);
		level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.HOSTILE, 0.7f, 0.6f);
		Panic.add(target, HwConfig.get().panicOnGrab);
		AiStats.add(v, "vecna.grip");
	}

	private static void tickGrip(ServerLevel level, ServerPlayer v, ShapeState st, int now) {
		HwConfig cfg = HwConfig.get();
		LivingEntity t = gripped;
		if (!st.senseHeld || st.stunnedTicks > 0 || st.grab != null || !grippable(t) || t.level() != level
			|| t.distanceTo(v) > gripRange() * 1.5) {
			// Let go, stunned, the grab closed on them (or someone else), or they are out of his reach.
			release(level, st.stunnedTicks > 0);
			return;
		}
		Vec3 eye = v.getEyePosition();
		Vec3 body = FocusPoints.bodyCenter(t);
		gripLost = Sight.canSee(level, eye, body, v) ? 0 : gripLost + 1;
		if (gripLost > cfg.vecnaGripLostTicks) {
			release(level, false);
			return;
		}
		// Drawn towards him over the ground (they keep their own eyes, and their gun).
		Vec3 to = new Vec3(v.getX() - t.getX(), 0, v.getZ() - t.getZ());
		double dist = to.length();
		Vec3 pull = dist > 1.6 ? to.scale((VecnaRitual.rift() ? cfg.vecnaRiftGripPull : cfg.vecnaGripPull) / 20.0 / dist) : Vec3.ZERO;
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

	/** The Grip seen by everyone: a writhing thread of red from his raised hand to their chest. */
	private static void beam(ServerLevel level, ServerPlayer v, LivingEntity t, int now) {
		float yaw = v.getYRot() * Mth.DEG_TO_RAD;
		// His right hand, held out before him.
		Vec3 hand = v.position().add(-Mth.sin(yaw) * 0.6 - Mth.cos(yaw) * 0.35, 1.45, Mth.cos(yaw) * 0.6 - Mth.sin(yaw) * 0.35);
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

	/** The Grip ends: they can walk again; he rests before the next. */
	private static void release(ServerLevel level, boolean stunned) {
		if (gripped == null) {
			return;
		}
		LivingEntity t = gripped;
		gripped = null;
		root(t, false);
		gripCooldown = HwConfig.get().vecnaGripCooldownTicks;
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
	}

	/** A match over (or starting): nobody held, no rest. */
	public static void clear(ServerLevel level) {
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
