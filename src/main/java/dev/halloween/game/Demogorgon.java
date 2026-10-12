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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The Demogorgon (Stranger Things), the other killer (/hw killer demogorgon): played by a person (a bot always plays
 * Michael). Everything a killer does (Sense, stalking, the grab and the kill) is as Michael's; his own powers take the
 * place of Michael's on the same keys.
 * <ul>
 * <li>Portals (the Shape Jump key): a portal where he stands (up to six, apart from each other: the ground turns to
 * flesh, spores rise from it); standing on one, down into the Upside Down (a dark copy of the town, see
 * {@link UpsideDown}); down there, at the same place as any portal, up through it, quietly for a few seconds.</li>
 * <li>Of the Abyss (the Impulse key): on a portal or in the Upside Down, everyone near any portal shows to him.
 * Down there those right by a portal show by themselves.</li>
 * <li>Shred (the dash key): a second's charge, then a lunge forwards that strikes the first one in his way and
 * breaks a door or a window it runs into.</li>
 * </ul>
 * Survivors seal a portal holding use on it (twelve seconds, with reaction checks): it is gone.
 */
public final class Demogorgon {
	public static final int MAX_PORTALS = 6;
	/** How far apart two portals must be. */
	private static final double PORTAL_GAP = 8;
	private static final int SEAL_TICKS = 240;
	private static final int TRAVERSE_COOLDOWN = 200;
	private static final int EMERGE_QUIET = 100;
	private static final int SHRED_CHARGE = 20;
	private static final int SHRED_TICKS = 7;
	/** Of the Abyss: how near a portal one shows, and for how long. */
	private static final double ABYSS_RANGE = 16;
	private static final double PASSIVE_RANGE = 8;

	private static boolean chosen;

	/** A portal: the floor block turned to flesh, and what was there before. */
	private record Portal(BlockPos floor, BlockState before) {
		Vec3 top() {
			return Vec3.atBottomCenterOf(this.floor.above());
		}
	}

	private static final List<Portal> PORTALS = new ArrayList<>();
	private static int placeAt;
	private static int quietUntil;
	private static int abyssUntil;
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

	public static int portals() {
		return PORTALS.size();
	}

	// ---------------------------------------------------------------- the keys

	/** The Shape Jump key: up through a portal from below, down through the one he stands on, or a new portal here. */
	static void portalKey(ServerPlayer p, ShapeState st) {
		if (st.busy() || shredCharge >= 0 || shredTicks > 0) {
			return;
		}
		int now = p.level().getServer().getTickCount();
		if (UpsideDown.isBelow(p)) {
			Portal at = nearest(UpsideDown.above(p.position()), 2.5);
			if (at == null) {
				p.sendOverlayMessage(Component.translatable("halloween.msg.demo.no_portal_below").withStyle(ChatFormatting.GRAY));
				return;
			}
			emerge(p, st, at, now);
			return;
		}
		Portal at = nearest(p.position(), 2);
		if (at != null) {
			if (st.jumpCooldown > 0) {
				p.sendOverlayMessage(Component.translatable("halloween.msg.demo.cooldown").withStyle(ChatFormatting.GRAY));
			} else if (!UpsideDown.ready()) {
				p.sendOverlayMessage(Component.translatable("halloween.msg.demo.not_ready").withStyle(ChatFormatting.GRAY));
			} else {
				enter(p, at);
			}
			return;
		}
		place(p, now);
	}

	private static void place(ServerPlayer p, int now) {
		ServerLevel level = p.level();
		if (now < placeAt) {
			return;
		}
		if (PORTALS.size() >= MAX_PORTALS) {
			p.sendOverlayMessage(Component.translatable("halloween.msg.demo.max", MAX_PORTALS).withStyle(ChatFormatting.GRAY));
			return;
		}
		if (nearest(p.position(), PORTAL_GAP) != null) {
			p.sendOverlayMessage(Component.translatable("halloween.msg.demo.too_close").withStyle(ChatFormatting.GRAY));
			return;
		}
		BlockPos floor = p.blockPosition().below();
		BlockState before = level.getBlockState(floor);
		if (!p.onGround() || !before.isFaceSturdy(level, floor, Direction.UP) || before.hasBlockEntity() || Exits.isProp(floor)) {
			p.sendOverlayMessage(Component.translatable("halloween.msg.demo.bad_ground").withStyle(ChatFormatting.GRAY));
			return;
		}
		placeAt = now + 60;
		PORTALS.add(new Portal(floor.immutable(), before));
		level.setBlock(floor, Blocks.CRIMSON_NYLIUM.defaultBlockState(), Block.UPDATE_ALL);
		level.playSound(null, floor, SoundEvents.SCULK_BLOCK_SPREAD, SoundSource.HOSTILE, 1.4f, 0.6f);
		level.sendParticles(ParticleTypes.CRIMSON_SPORE, floor.getX() + 0.5, floor.getY() + 1.2, floor.getZ() + 0.5, 40, 0.6, 0.4, 0.6, 0.02);
		AiStats.add(p, "demo.portal");
		Match.markDirty();
	}

	/** Down through this portal: the same place in the Upside Down, his eyes made for the dark. */
	private static void enter(ServerPlayer p, Portal at) {
		ServerLevel level = p.level();
		Vec3 to = UpsideDown.below(at.top());
		level.playSound(null, at.floor().above(), SoundEvents.SCULK_SHRIEKER_SHRIEK, SoundSource.HOSTILE, 0.8f, 0.5f);
		level.sendParticles(ParticleTypes.CRIMSON_SPORE, at.top().x, at.top().y + 0.5, at.top().z, 60, 0.4, 0.8, 0.4, 0.05);
		p.teleportTo(level, to.x, to.y, to.z, java.util.Set.of(), p.getYRot(), p.getXRot(), false);
		p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 20 * 60 * 30, 0, true, false));
		AiStats.add(p, "demo.down");
	}

	/** Up through this portal, quiet for a few seconds; the portals rest a while before he goes down again. */
	private static void emerge(ServerPlayer p, ShapeState st, Portal at, int now) {
		ServerLevel level = p.level();
		Vec3 to = at.top();
		p.teleportTo(level, to.x, to.y, to.z, java.util.Set.of(), p.getYRot(), p.getXRot(), false);
		p.removeEffect(MobEffects.NIGHT_VISION);
		p.setSilent(true);
		quietUntil = now + EMERGE_QUIET;
		st.jumpCooldownMax = TRAVERSE_COOLDOWN;
		st.jumpCooldown = TRAVERSE_COOLDOWN;
		level.sendParticles(ParticleTypes.CRIMSON_SPORE, to.x, to.y + 0.5, to.z, 30, 0.4, 0.8, 0.4, 0.03);
		AiStats.add(p, "demo.up");
		Match.markDirty();
	}

	/** The Impulse key: Of the Abyss, on a portal or down below: everyone near any portal shows to him a while. */
	static void abyss(ServerPlayer p, ShapeState st) {
		if (st.busy() || st.impulseCooldown > 0) {
			return;
		}
		boolean below = UpsideDown.isBelow(p);
		if (!below && nearest(p.position(), 2.5) == null) {
			p.sendOverlayMessage(Component.translatable("halloween.msg.demo.abyss_portal").withStyle(ChatFormatting.GRAY));
			return;
		}
		HwConfig cfg = HwConfig.get();
		int now = p.level().getServer().getTickCount();
		List<Integer> ids = nearPortals(p.level(), ABYSS_RANGE);
		st.impulseCooldownMax = HwConfig.at(cfg.impulseCooldownByTier, st.tier());
		st.impulseCooldown = st.impulseCooldownMax;
		abyssUntil = now + 100;
		p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.WARDEN_HEARTBEAT, SoundSource.HOSTILE, 1.5f, 0.7f);
		ServerPlayNetworking.send(p, new HwNet.Impulse(ids, List.of(), 100));
		AiStats.add(p, "demo.abyss");
	}

	/** The dash key: Shred, a second's charge then a lunge. */
	static void shredKey(ServerPlayer p, ShapeState st) {
		if (st.busy() || st.dashCooldown > 0 || shredCharge >= 0 || shredTicks > 0 || UpsideDown.isBelow(p)) {
			return;
		}
		shredCharge = 0;
		p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.RAVAGER_STUNNED, SoundSource.HOSTILE, 1f, 1.4f);
	}

	// ---------------------------------------------------------------- survivors

	/** A survivor holding use on a portal: sealing it, twelve seconds (reaction checks on the way). */
	static Holds.Hold resolve(ServerPlayer player, BlockPos pos) {
		if (!MatchManager.role(player).survivor()) {
			return null;
		}
		Portal at = portalAt(pos);
		if (at == null) {
			return null;
		}
		BlockPos floor = at.floor();
		return new Holds.Hold(floor, "halloween.hud.progress.seal_portal", false, SEAL_TICKS, p -> portalAt(floor) != null, p -> {
			Portal still = portalAt(floor);
			if (still != null) {
				remove(p.level(), still);
				p.level().playSound(null, floor, SoundEvents.SCULK_SHRIEKER_BREAK, SoundSource.BLOCKS, 1.2f, 0.8f);
				Match.score(p, HwConfig.get().scoreExitProgress, Component.translatable("halloween.score.portal_sealed"));
				ServerPlayer shape = Senses.shape(p.level());
				if (shape != null) {
					shape.sendOverlayMessage(Component.translatable("halloween.msg.demo.sealed").withStyle(ChatFormatting.DARK_RED));
				}
			}
		}, true);
	}

	private static Portal portalAt(BlockPos floor) {
		for (Portal p : PORTALS) {
			if (p.floor().equals(floor)) {
				return p;
			}
		}
		return null;
	}

	private static void remove(ServerLevel level, Portal p) {
		PORTALS.remove(p);
		if (level.getBlockState(p.floor()).is(Blocks.CRIMSON_NYLIUM)) {
			level.setBlock(p.floor(), p.before(), Block.UPDATE_ALL);
		}
		Match.markDirty();
	}

	// ---------------------------------------------------------------- every tick

	public static void tick(MinecraftServer server) {
		if (!active() || !Match.running()) {
			return;
		}
		UpsideDown.tick();
		ServerLevel level = server.overworld();
		int now = server.getTickCount();
		// The portals breathe: spores rising, a low sound now and then (survivors find them so).
		if (now % 10 == 0) {
			for (Portal p : PORTALS) {
				Vec3 t = p.top();
				level.sendParticles(ParticleTypes.CRIMSON_SPORE, t.x, t.y + 0.3, t.z, 4, 0.4, 0.2, 0.4, 0.01);
				if (now % 80 == 0) {
					level.playSound(null, p.floor().above(), SoundEvents.SCULK_BLOCK_SPREAD, SoundSource.BLOCKS, 0.6f, 0.5f);
				}
			}
		}
		ServerPlayer d = Senses.shape(level);
		if (d == null) {
			return;
		}
		ShapeState st = MatchManager.shapeState(d);
		if (st == null) {
			return;
		}
		if (quietUntil > 0 && now >= quietUntil) {
			quietUntil = 0;
			d.setSilent(false);
		}
		boolean below = UpsideDown.isBelow(d);
		if (below && now % 5 == 0) {
			// Ash drifting in the dark (for his eyes).
			level.sendParticles(d, ParticleTypes.WHITE_ASH, true, false, d.getX(), d.getY() + 1.5, d.getZ(), 30, 8, 3, 8, 0.01);
		}
		if (below && now % 20 == 0 && now > abyssUntil) {
			// From below he feels whoever stands right by a portal.
			ServerPlayNetworking.send(d, new HwNet.Impulse(nearPortals(level, PASSIVE_RANGE), List.of(), 30));
		}
		if (!below && d.hasEffect(MobEffects.NIGHT_VISION)) {
			d.removeEffect(MobEffects.NIGHT_VISION);
		}
		tickShred(d, st);
		if (now % 10 == 0) {
			ServerPlayNetworking.send(d, new HwNet.DemoHud(PORTALS.size(), MAX_PORTALS, below, UpsideDown.ready(), shredCharge >= 0));
		}
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

	/** Survivors (not in a wardrobe) within {@code r} of any portal. */
	private static List<Integer> nearPortals(ServerLevel level, double r) {
		List<Integer> ids = new ArrayList<>();
		for (ServerPlayer s : level.players()) {
			if (!MatchManager.role(s).survivor() || !MatchManager.exposed(s)) {
				continue;
			}
			if (nearest(s.position(), r) != null) {
				ids.add(s.getId());
			}
		}
		return ids;
	}

	/** The portal within {@code r} of this point (up in the town), the nearest, or null. */
	private static Portal nearest(Vec3 at, double r) {
		Portal best = null;
		double bestD = r;
		for (Portal p : PORTALS) {
			double d = p.top().distanceTo(at);
			if (d <= bestD) {
				bestD = d;
				best = p;
			}
		}
		return best;
	}

	/** A match over: the portals gone, the ground as it was, the Upside Down left as it is. */
	public static void clear(ServerLevel level) {
		for (Portal p : new ArrayList<>(PORTALS)) {
			remove(level, p);
		}
		PORTALS.clear();
		shredCharge = -1;
		shredTicks = 0;
		quietUntil = 0;
		abyssUntil = 0;
		placeAt = 0;
		UpsideDown.clear();
	}
}
