package dev.halloween.game;

import dev.halloween.Halloween;
import dev.halloween.HwConfig;
import dev.halloween.net.HwNet;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
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
 * <li>The Eyes (Shape Jump key) and the Anchor (Impulse key): to come (docs/VECNA_PLAN.md).</li>
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

	/** Is this one held by the Grip? */
	public static boolean held(LivingEntity e) {
		return gripping() && gripped == e;
	}

	// ---------------------------------------------------------------- the keys

	/** The Shape Jump key: the Eyes (not yet). */
	static void eyesKey(ServerPlayer p, ShapeState st) {
		p.sendOverlayMessage(Component.translatable("halloween.msg.vecna.soon").withStyle(ChatFormatting.GRAY));
	}

	/** The Impulse key: the Anchor (not yet). */
	static void anchorKey(ServerPlayer p, ShapeState st) {
		p.sendOverlayMessage(Component.translatable("halloween.msg.vecna.soon").withStyle(ChatFormatting.GRAY));
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
			return;
		}
		if (gripCooldown > 0) {
			gripCooldown--;
		}
		if (gripped != null) {
			tickGrip(level, v, st, now);
		} else if (st.senseHeld && gripCooldown == 0 && !st.busy() && !Match.frozen()) {
			// Holding the key with nobody caught yet: whoever comes under his aim is.
			LivingEntity target = aimed(level, v, st);
			if (target != null) {
				catchHold(level, v, target, now);
			}
		}
		if (now % 4 == 0) {
			ServerPlayNetworking.send(v, new HwNet.VecnaHud(gripCooldown, HwConfig.get().vecnaGripCooldownTicks,
				gripped == null ? -1 : gripped.getId()));
		}
	}

	/** The survivor nearest his aim within reach and the cone, in sight. */
	private static LivingEntity aimed(ServerLevel level, ServerPlayer v, ShapeState st) {
		HwConfig cfg = HwConfig.get();
		Vec3 from = MatchManager.aimOrigin(v, st);
		Vec3 look = MatchManager.aimDirection(v, st);
		Vec3 eye = v.getEyePosition();
		LivingEntity best = null;
		double bestAngle = cfg.vecnaGripAngleDeg;
		for (ServerPlayer s : level.players()) {
			if (!grippable(s)) {
				continue;
			}
			Vec3 body = FocusPoints.bodyCenter(s);
			if (eye.distanceTo(body) > cfg.vecnaGripRange) {
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
			|| t.distanceTo(v) > cfg.vecnaGripRange * 1.5) {
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
		Vec3 pull = dist > 1.6 ? to.scale(cfg.vecnaGripPull / 20.0 / dist) : Vec3.ZERO;
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
	}
}
