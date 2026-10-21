package dev.halloween.game;

import dev.halloween.Halloween;
import dev.halloween.HwConfig;
import dev.halloween.HwParticles;
import dev.halloween.HwSounds;
import dev.halloween.net.HwNet;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/**
 * Vecna takes the cursed one (Stranger Things, season four): instead of his usual kill, a scene. He stands with a hand
 * raised; the clock strikes and they are lifted off their feet, head thrown back; their bones break one by one (arms
 * wrenched back, the legs, last the neck), blood runs from their eyes, and they hang slack. If that ends his ritual
 * they stay up there and the rift opens under them; else they drop. Their pose and the camera for the two of them
 * are client.VecnaDeathScene's, from the {@link HwNet.VecnaDeath} every client gets.
 */
public final class VecnaDeath {
	/** The whole scene (ticks); the bones break at these; the eyes; the body drops (if it is not his last); the kill. */
	public static final int TICKS = 150;
	public static final int[] CRACKS = {38, 50, 62, 72, 92};
	private static final int EYES = 100;
	private static final int DROP = 112;
	private static final int KILL = 122;
	/** How high they are lifted (blocks). */
	public static final double HEIGHT = 2.6;
	private static final Identifier HELD = Halloween.id("vecna_death");

	private static ServerPlayer victim;
	private static ServerPlayer vecna;
	private static Vec3 ground;
	private static float yaw;
	private static boolean last;
	private static int t = -1;

	private VecnaDeath() {
	}

	/** The scene is playing (Vecna and they are held in it). */
	public static boolean active() {
		return t >= 0;
	}

	public static boolean isVictim(net.minecraft.world.entity.Entity e) {
		return t >= 0 && e == victim;
	}

	/** In place of his kill (from the grab's finish): the cursed one dies this way. */
	static void start(ServerPlayer v, ServerPlayer p) {
		if (active()) {
			return;
		}
		if (Lockers.isInside(p)) {
			Lockers.leave(p, false);
		}
		victim = p;
		vecna = v;
		ground = p.position();
		Vec3 d = v.position().subtract(p.position());
		yaw = (float) (Math.atan2(-d.x, d.z) * 180 / Math.PI);
		last = VecnaRitual.kills() >= HwConfig.get().vecnaRitualKills;
		t = 0;
		hold(p, true);
		p.setNoGravity(true);
		GrabSession.anim(v, HwNet.Anim.VECNA_CAST);
		ServerLevel level = p.level();
		long now = level.getGameTime();
		HwNet.VecnaDeath payload = new HwNet.VecnaDeath(p.getId(), v.getId(), now, TICKS);
		for (ServerPlayer s : level.getServer().getPlayerList().getPlayers()) {
			ServerPlayNetworking.send(s, payload);
		}
		level.playSound(null, p.getX(), p.getY(), p.getZ(), HwSounds.VECNA_CURSE_LAST, SoundSource.HOSTILE, 2f, 1f);
		level.playSound(null, p.getX(), p.getY(), p.getZ(), HwSounds.VECNA_CURSE_FLOAT, SoundSource.HOSTILE, 1.6f, 1f);
		AiStats.add(p, "vecna.death_scene");
	}

	/** Rooted where they are, no jumping (the server moves them). */
	private static void hold(ServerPlayer p, boolean on) {
		for (AttributeInstance a : new AttributeInstance[]{p.getAttribute(Attributes.MOVEMENT_SPEED), p.getAttribute(Attributes.JUMP_STRENGTH)}) {
			if (a == null) {
				continue;
			}
			if (on) {
				a.addOrUpdateTransientModifier(new AttributeModifier(HELD, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
			} else {
				a.removeModifier(HELD);
			}
		}
	}

	/** How high they hang at tick {@code k}: lifted slowly, jolted at each break, dropped at the end (unless it is his last). */
	private static double height(int k) {
		double up = HEIGHT * smooth(k / 70.0);
		for (int c : CRACKS) {
			if (k >= c && k < c + 4) {
				up += 0.12 * (1 - (k - c) / 4.0);
			}
		}
		if (!last && k >= DROP) {
			double f = Math.min(1, (k - DROP) / (double) (KILL - DROP));
			up *= 1 - f * f;
		}
		return up;
	}

	private static double smooth(double x) {
		x = Mth.clamp(x, 0, 1);
		return x * x * (3 - 2 * x);
	}

	public static void tick(MinecraftServer server) {
		if (t < 0) {
			return;
		}
		if (!Match.running() || victim == null || victim.isRemoved()) {
			stop();
			return;
		}
		ServerLevel level = victim.level();
		t++;
		if (t <= KILL) {
			Vec3 at = ground.add(0, height(t), 0);
			victim.teleportTo(level, at.x, at.y, at.z, java.util.Set.of(), yaw, -20f, false);
			victim.setYBodyRot(yaw);
			victim.setYHeadRot(yaw);
			victim.setDeltaMovement(Vec3.ZERO);
		}
		Vec3 chest = victim.position().add(0, 1.2, 0);
		for (int c : CRACKS) {
			if (t == c) {
				level.playSound(null, chest.x, chest.y, chest.z, HwSounds.VECNA_BONE_CRACK, SoundSource.HOSTILE, 1.8f, 0.85f + level.getRandom().nextFloat() * 0.3f);
				if (c != CRACKS[CRACKS.length - 1]) {
					Voices.play(victim, Voices.Kind.HURT);
				}
				level.sendParticles(HwParticles.VECNA_SPARK, chest.x, chest.y, chest.z, 6, 0.3, 0.4, 0.3, 0.08);
			}
		}
		if (t % 3 == 0 && t < KILL) {
			// The dark round them: smoke curling off, ash.
			level.sendParticles(HwParticles.VECNA_SMOKE, chest.x, chest.y - 0.8, chest.z, 1, 0.3, 0.3, 0.3, 0.01);
			level.sendParticles(HwParticles.VECNA_ASH, chest.x, chest.y, chest.z, 1, 0.5, 0.6, 0.5, 0.01);
		}
		if (t >= EYES && t < KILL && t % 2 == 0) {
			// Blood running from the eyes.
			Vec3 eyes = victim.getEyePosition().add(Vec3.directionFromRotation(0, yaw).scale(0.25));
			level.sendParticles(HwParticles.BLOOD, eyes.x, eyes.y - 0.05, eyes.z, 0, 0, -0.05, 0, 1);
		}
		if (t == KILL) {
			ServerPlayer v = vecna;
			ShapeState st = v == null ? null : MatchManager.shapeState(v);
			ServerPlayer p = victim;
			release(p);
			if (v != null && st != null) {
				GrabSession.anim(v, HwNet.Anim.NONE);
				MatchManager.kill(v, st, p, MatchManager.KillKind.STRIKE, false);
			} else {
				MatchManager.eliminate(p);
			}
		}
		if (t >= TICKS) {
			t = -1;
			victim = null;
			vecna = null;
		}
	}

	private static void release(ServerPlayer p) {
		hold(p, false);
		p.setNoGravity(false);
	}

	private static void stop() {
		if (victim != null) {
			release(victim);
		}
		if (vecna != null) {
			GrabSession.anim(vecna, HwNet.Anim.NONE);
		}
		t = -1;
		victim = null;
		vecna = null;
	}

	public static void clear() {
		stop();
	}

	/** For tests: the scene now, as if he had just won the struggle. */
	public static void testStart(ServerPlayer v, ServerPlayer p) {
		start(v, p);
	}

	/** Below {@code at}: where the floor is (at most a few blocks down). */
	static Vec3 floor(ServerLevel level, Vec3 at) {
		BlockPos p = BlockPos.containing(at);
		for (int dy = 0; dy < 6; dy++) {
			BlockPos c = p.below(dy);
			if (!level.getBlockState(c.below()).getCollisionShape(level, c.below()).isEmpty()) {
				return new Vec3(at.x, c.getY(), at.z);
			}
		}
		return at;
	}
}
