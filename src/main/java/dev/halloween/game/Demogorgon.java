package dev.halloween.game;

import dev.halloween.HwConfig;
import dev.halloween.net.HwNet;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * The Demogorgon (Stranger Things), the other killer (/hw killer demogorgon): played by a person (a bot always plays
 * Michael). Everything a killer does (Sense, stalking, the grab and the kill, the Impulse) is as Michael's; his own
 * powers take the place of Shape Jump and the dash on the same keys.
 * <ul>
 * <li>The Upside Down (the Shape Jump key), anywhere: five seconds up on his hind legs tearing the air open (a rift
 * everyone near sees and hears), through it, and it closes behind him. Down there (a dark copy of the town, see
 * {@link UpsideDown}) he runs very fast and feels every survivor near him through the world above. Out again
 * anywhere: five seconds of the ceiling over that place splitting (or the air, out of doors), dripping, then he drops
 * through it.</li>
 * <li>Shred (the dash key): a second's charge, then a lunge forwards that strikes the first one in his way and breaks
 * a door or a window it runs into.</li>
 * </ul>
 */
public final class Demogorgon {
	/** Five seconds to tear a way in, or out. */
	public static final int RIFT_TICKS = 100;
	/** After coming out, before he can go down again. */
	private static final int RIFT_COOLDOWN = 20 * 20;
	private static final int SHRED_CHARGE = 20;
	private static final int SHRED_TICKS = 7;
	/** How far round him he feels survivors from the Upside Down (horizontally). */
	private static final double FEEL_RANGE = 40;

	private static boolean chosen;

	/** Tearing a rift: 1 down into the Upside Down, 2 up out of it; 0 not at it. */
	private static int rift;
	private static int riftStart;
	/** Where the rift is (up in the town): in the air before him, or in the ceiling over where he comes out. */
	private static Vec3 riftAt;
	/** Where he lands coming out. */
	private static Vec3 landing;
	/** Shred: ticks charged (-1: not charging), ticks of lunge left, its way. */
	private static int shredCharge = -1;
	private static int shredTicks;
	private static Vec3 shredDir = Vec3.ZERO;

	private Demogorgon() {
	}

	/** The next matches' killer: the Demogorgon (true) or Michael. */
	public static void choose(boolean demogorgon) {
		chosen = demogorgon;
	}

	public static boolean chosen() {
		return chosen;
	}

	/** Is the killer the Demogorgon (chosen, and a person plays him: a bot plays Michael)? */
	public static boolean active() {
		return chosen && !ShapeBrain.chosen();
	}

	/** Charging a Shred (he goes slowly meanwhile). */
	public static boolean charging() {
		return active() && shredCharge >= 0;
	}

	/** Tearing a rift: he stays where he is. */
	public static boolean rooted() {
		return active() && rift != 0;
	}

	/** Down in the Upside Down: he runs very fast there. */
	public static boolean fast(ServerPlayer p) {
		return active() && rift == 0 && UpsideDown.isBelow(p);
	}

	// ---------------------------------------------------------------- the keys

	/** The Shape Jump key: a rift down into the Upside Down from here, or (down there) up out of it here. */
	static void riftKey(ServerPlayer p, ShapeState st) {
		if (st.busy() || rift != 0 || shredCharge >= 0 || shredTicks > 0) {
			return;
		}
		ServerLevel level = p.level();
		int now = level.getServer().getTickCount();
		if (UpsideDown.isBelow(p)) {
			Vec3 up = UpsideDown.above(p.position());
			Vec3 land = landing(level, up);
			if (land == null) {
				p.sendOverlayMessage(Component.translatable("halloween.msg.demo.no_way_up").withStyle(ChatFormatting.GRAY));
				return;
			}
			landing = land;
			riftAt = ceiling(level, land);
			start(p, 2, now);
			// Up there the ceiling begins to give: everyone near hears it.
			level.playSound(null, riftAt.x, riftAt.y, riftAt.z, SoundEvents.SCULK_SHRIEKER_SHRIEK, SoundSource.HOSTILE, 1.4f, 0.55f);
			return;
		}
		if (st.jumpCooldown > 0) {
			p.sendOverlayMessage(Component.translatable("halloween.msg.demo.cooldown").withStyle(ChatFormatting.GRAY));
			return;
		}
		if (!UpsideDown.ready()) {
			p.sendOverlayMessage(Component.translatable("halloween.msg.demo.not_ready").withStyle(ChatFormatting.GRAY));
			return;
		}
		Vec3 look = p.getLookAngle().multiply(1, 0, 1);
		look = look.lengthSqr() < 1e-4 ? new Vec3(0, 0, 1) : look.normalize();
		riftAt = p.position().add(look.scale(1.3)).add(0, 1.1, 0);
		start(p, 1, now);
		level.playSound(null, riftAt.x, riftAt.y, riftAt.z, SoundEvents.PORTAL_TRIGGER, SoundSource.HOSTILE, 0.9f, 0.6f);
	}

	private static void start(ServerPlayer p, int kind, int now) {
		rift = kind;
		riftStart = now;
		GrabSession.anim(p, HwNet.Anim.RIFT);
		AiStats.add(p, kind == 1 ? "demo.rift_down" : "demo.rift_up");
		Match.markDirty();
	}

	/** Where he would stand coming out over this place below: the floor there (or next to it). */
	private static Vec3 landing(ServerLevel level, Vec3 up) {
		BlockPos at = Nav.snap(level, BlockPos.containing(up));
		if (Nav.standable(level, at)) {
			return Vec3.atBottomCenterOf(at);
		}
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos side = Nav.snap(level, at.relative(d));
			if (Nav.standable(level, side)) {
				return Vec3.atBottomCenterOf(side);
			}
		}
		return null;
	}

	/** The ceiling over this place (the underside of the first block up within six), or the air three blocks up. */
	private static Vec3 ceiling(ServerLevel level, Vec3 floor) {
		BlockPos p = BlockPos.containing(floor);
		for (int dy = 2; dy <= 6; dy++) {
			BlockPos q = p.above(dy);
			if (!level.getBlockState(q).getCollisionShape(level, q).isEmpty()) {
				return new Vec3(floor.x, q.getY() - 0.1, floor.z);
			}
		}
		return floor.add(0, 3.2, 0);
	}

	/** The dash key: Shred, a second's charge then a lunge. */
	static void shredKey(ServerPlayer p, ShapeState st) {
		if (st.busy() || st.dashCooldown > 0 || shredCharge >= 0 || shredTicks > 0 || rift != 0 || UpsideDown.isBelow(p)) {
			return;
		}
		shredCharge = 0;
		p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.RAVAGER_STUNNED, SoundSource.HOSTILE, 1f, 1.4f);
	}

	// ---------------------------------------------------------------- every tick

	public static void tick(MinecraftServer server) {
		if (!active() || !Match.running()) {
			return;
		}
		UpsideDown.tick();
		ServerLevel level = server.overworld();
		int now = server.getTickCount();
		ServerPlayer d = Senses.shape(level);
		if (d == null) {
			return;
		}
		ShapeState st = MatchManager.shapeState(d);
		if (st == null) {
			return;
		}
		tickRift(level, d, st, now);
		boolean below = UpsideDown.isBelow(d);
		if (below && now % 5 == 0) {
			// Ash drifting in the dark (for his eyes).
			level.sendParticles(d, ParticleTypes.WHITE_ASH, true, false, d.getX(), d.getY() + 1.5, d.getZ(), 30, 8, 3, 8, 0.01);
		}
		if (below && now % 10 == 0) {
			// He feels them through the world above: every survivor near him, wherever they are.
			ServerPlayNetworking.send(d, new HwNet.Impulse(survivorsNear(level, UpsideDown.above(d.position()), FEEL_RANGE), List.of(), 15));
		}
		if (!below && rift == 0 && d.hasEffect(MobEffects.NIGHT_VISION)) {
			d.removeEffect(MobEffects.NIGHT_VISION);
		}
		tickShred(d, st);
		if (now % 5 == 0) {
			ServerPlayNetworking.send(d, new HwNet.DemoHud(rift == 0 ? 0 : now - riftStart, RIFT_TICKS, below, UpsideDown.ready(), shredCharge >= 0));
		}
	}

	private static void tickRift(ServerLevel level, ServerPlayer d, ShapeState st, int now) {
		if (rift == 0) {
			return;
		}
		if (st.busy()) {
			// Struck down or grabbed mid-way: the rift falls shut.
			cancel(level, d);
			return;
		}
		int t = now - riftStart;
		float k = Math.min(1f, t / (float) RIFT_TICKS);
		if (now % 3 == 0) {
			if (rift == 1) {
				// A tear in the air before him, growing: red spores, the dark beyond it pulling at the light.
				double h = 0.3 + 1.9 * k;
				for (int i = 0; i < 6; i++) {
					double y = (level.getRandom().nextDouble() - 0.5) * h;
					level.sendParticles(ParticleTypes.CRIMSON_SPORE, riftAt.x, riftAt.y + y, riftAt.z, 2, 0.05, 0.05, 0.05, 0.02);
					level.sendParticles(ParticleTypes.REVERSE_PORTAL, riftAt.x, riftAt.y + y, riftAt.z, 1, 0.05, 0.05, 0.05, 0.05);
				}
			} else {
				// The ceiling splitting where he will come through: dripping, spores falling.
				double r = 0.2 + 0.9 * k;
				level.sendParticles(ParticleTypes.FALLING_OBSIDIAN_TEAR, riftAt.x, riftAt.y, riftAt.z, 2 + (int) (6 * k), r, 0.02, r, 0);
				level.sendParticles(ParticleTypes.CRIMSON_SPORE, riftAt.x, riftAt.y - 0.3, riftAt.z, 3, r, 0.1, r, 0.01);
				if (t % 30 == 0) {
					level.playSound(null, riftAt.x, riftAt.y, riftAt.z, SoundEvents.SCULK_BLOCK_SPREAD, SoundSource.HOSTILE, 1f, 0.5f);
				}
			}
		}
		if (t < RIFT_TICKS) {
			return;
		}
		if (rift == 1) {
			// Through, and it closes behind him.
			Vec3 to = UpsideDown.below(d.position());
			level.sendParticles(ParticleTypes.REVERSE_PORTAL, riftAt.x, riftAt.y, riftAt.z, 80, 0.3, 1.0, 0.3, 0.4);
			level.playSound(null, riftAt.x, riftAt.y, riftAt.z, SoundEvents.PORTAL_TRAVEL, SoundSource.HOSTILE, 0.35f, 0.6f);
			d.teleportTo(level, to.x, to.y, to.z, java.util.Set.of(), d.getYRot(), d.getXRot(), false);
			d.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 20 * 60 * 30, 0, true, false));
			GrabSession.anim(d, HwNet.Anim.NONE);
			AiStats.add(d, "demo.down");
		} else {
			// Down through the ceiling.
			Vec3 to = landing;
			d.teleportTo(level, to.x, to.y, to.z, java.util.Set.of(), d.getYRot(), d.getXRot(), false);
			d.removeEffect(MobEffects.NIGHT_VISION);
			st.jumpCooldownMax = RIFT_COOLDOWN;
			st.jumpCooldown = RIFT_COOLDOWN;
			level.sendParticles(ParticleTypes.CRIMSON_SPORE, riftAt.x, riftAt.y - 0.5, riftAt.z, 60, 0.6, 0.4, 0.6, 0.05);
			level.playSound(null, to.x, to.y, to.z, SoundEvents.WARDEN_ROAR, SoundSource.HOSTILE, 1.3f, 1.25f);
			GrabSession.anim(d, HwNet.Anim.EMERGE);
			AiStats.add(d, "demo.up");
		}
		rift = 0;
		riftAt = null;
		landing = null;
		Match.markDirty();
	}

	private static void cancel(ServerLevel level, ServerPlayer d) {
		if (riftAt != null) {
			level.sendParticles(ParticleTypes.SMOKE, riftAt.x, riftAt.y, riftAt.z, 20, 0.2, 0.6, 0.2, 0.01);
		}
		rift = 0;
		riftAt = null;
		landing = null;
		GrabSession.anim(d, HwNet.Anim.NONE);
		AiStats.add(d, "demo.rift_broken");
	}

	private static void tickShred(ServerPlayer d, ShapeState st) {
		if (shredCharge >= 0) {
			if (st.busy()) {
				shredCharge = -1;
				return;
			}
			if (++shredCharge >= SHRED_CHARGE) {
				// Charged: the lunge.
				shredCharge = -1;
				Vec3 look = d.getLookAngle().multiply(1, 0, 1);
				shredDir = look.lengthSqr() < 1e-4 ? new Vec3(0, 0, 1) : look.normalize();
				shredTicks = SHRED_TICKS;
				HwConfig cfg = HwConfig.get();
				st.dashCooldownMax = HwConfig.at(cfg.dashCooldownByTier, st.tier());
				st.dashCooldown = st.dashCooldownMax;
				d.level().playSound(null, d.getX(), d.getY(), d.getZ(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 1.4f, 1.5f);
				AiStats.add(d, "demo.shred");
			}
			return;
		}
		if (shredTicks <= 0) {
			return;
		}
		shredTicks--;
		d.setDeltaMovement(shredDir.x * 0.95, d.getDeltaMovement().y, shredDir.z * 0.95);
		d.hurtMarked = true;
		Vec3 eye = d.getEyePosition();
		MatchManager.onAim(d, eye, shredDir);
		for (LivingEntity v : d.level().getEntitiesOfClass(LivingEntity.class, d.getBoundingBox().inflate(2.2), MatchManager::exposed)) {
			Vec3 to = v.position().subtract(d.position()).multiply(1, 0, 1);
			if (to.lengthSqr() > 1e-4 && to.normalize().dot(shredDir) > 0.5) {
				// The first in his way takes it.
				MatchManager.onAim(d, eye, FocusPoints.bodyCenter(v).subtract(eye).normalize());
				MatchManager.onShapeAttack(d, v.getId(), true);
				shredTicks = 0;
				AiStats.add(d, "demo.shred_hit");
				return;
			}
		}
		if (d.horizontalCollision) {
			// Into a door or a window: through it.
			MatchManager.onShapeAttack(d, -1, true);
			shredTicks = 0;
		}
	}

	/** Survivors (not in a wardrobe) within {@code r} of this point, across (horizontally). */
	private static List<Integer> survivorsNear(ServerLevel level, Vec3 at, double r) {
		List<Integer> ids = new ArrayList<>();
		for (ServerPlayer s : level.players()) {
			if (MatchManager.role(s).survivor() && MatchManager.exposed(s) && s.position().multiply(1, 0, 1).distanceTo(at.multiply(1, 0, 1)) <= r) {
				ids.add(s.getId());
			}
		}
		return ids;
	}

	/** A match over: no rift open, the Upside Down left as it is. */
	public static void clear(ServerLevel level) {
		rift = 0;
		riftAt = null;
		landing = null;
		shredCharge = -1;
		shredTicks = 0;
		UpsideDown.clear();
	}
}
