package dev.halloween.game;

import dev.halloween.HwConfig;
import dev.halloween.HwParticles;
import dev.halloween.HwSounds;
import dev.halloween.Role;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Fighting back. Bats, knives and batons fill the Shape's stun meter; thrown lawn darts fill it more.
 * A full meter drops him for a few seconds and frees whoever he holds. Weapons wear out.
 * Deputies' flashlights are real light (no Shape Jump in the beam) and blind the Shape while the beam is
 * on his face.
 */
public final class Fight {
	public static final String DART_TAG = "hw_dart";

	private static final Map<UUID, Integer> LAST_HIT = new HashMap<>();
	/** Light blocks placed by each flashlight, removed when the beam moves on. */
	private static final Map<UUID, BlockPos> LIGHTS = new HashMap<>();
	/** Players whose flashlight is switched on (it only shines while also held in hand). */
	private static final java.util.Set<UUID> SWITCHED_ON = new java.util.HashSet<>();

	private Fight() {
	}

	/** Right click with the flashlight in hand switches it on or off. */
	public static void toggleFlashlight(ServerPlayer player) {
		if (!MatchManager.role(player).survivor() || player.isSpectator() || !HwItems.is(player.getMainHandItem(), HwItems.Kind.FLASHLIGHT)) {
			return;
		}
		ItemStack light = player.getMainHandItem();
		boolean on = !SWITCHED_ON.contains(player.getUUID());
		if (on && HwItems.charge(light) <= 0) {
			// Flat battery: put in a spare one if there is one in the pockets.
			if (!Inventory.has(player, HwItems.Kind.BATTERY)) {
				player.sendOverlayMessage(Component.translatable("halloween.msg.battery_dead").withStyle(ChatFormatting.GRAY));
				player.level().playSound(null, player.getX(), player.getEyeY(), player.getZ(), SoundEvents.LEVER_CLICK, SoundSource.PLAYERS, 0.4f, 1.6f);
				return;
			}
			Inventory.consume(player, HwItems.Kind.BATTERY);
			HwItems.recharge(light);
			player.sendOverlayMessage(Component.translatable("halloween.msg.battery_new").withStyle(ChatFormatting.GRAY));
		}
		if (on) {
			SWITCHED_ON.add(player.getUUID());
		} else {
			SWITCHED_ON.remove(player.getUUID());
		}
		player.level().playSound(null, player.getX(), player.getEyeY(), player.getZ(), SoundEvents.LEVER_CLICK, SoundSource.PLAYERS, 0.5f, on ? 1.6f : 1.2f);
	}

	public static boolean flashlightOn(ServerPlayer player) {
		return SWITCHED_ON.contains(player.getUUID());
	}

	// ---------------------------------------------------------------- stun

	/** Adds to the Shape's stun meter on behalf of {@code by}. */
	public static void stun(ServerPlayer shape, float amount, ServerPlayer by) {
		ShapeState state = MatchManager.shapeState(shape);
		if (state == null || state.stunnedTicks > 0 || amount <= 0) {
			return;
		}
		HwConfig cfg = HwConfig.get();
		state.stun += amount;
		if (by != null) {
			Match.score(by, cfg.scoreHitShape, Component.translatable("halloween.score.hit_shape"));
		}
		ServerLevel level = shape.level();
		if (state.stun >= 1f) {
			state.stun = 0f;
			state.stunnedTicks = cfg.stunnedTicks;
			state.senseHeld = false;
			if (state.grab != null) {
				state.grab.end();
			}
			state.addBloodthirst(-cfg.bloodthirstDamageLoss);
			level.playSound(null, shape.getX(), shape.getY(), shape.getZ(), SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.HOSTILE, 2f, 0.5f);
			level.sendParticles(ParticleTypes.CRIT, shape.getX(), shape.getY() + 1.9, shape.getZ(), 20, 0.3, 0.1, 0.3, 0.05);
			shape.sendOverlayMessage(Component.translatable("halloween.msg.stunned").withStyle(ChatFormatting.DARK_RED));
			if (by != null) {
				Match.score(by, cfg.scoreStunShape, Component.translatable("halloween.score.stun_shape"));
				by.sendOverlayMessage(Component.translatable("halloween.msg.you_stunned").withStyle(ChatFormatting.GREEN));
			}
		}
	}

	/** A survivor's melee swing at the Shape. Always cancels vanilla damage. */
	public static InteractionResult onSurvivorAttack(ServerPlayer attacker, Entity target) {
		if (!(target instanceof ServerPlayer shape) || MatchManager.role(shape) != Role.SHAPE) {
			return InteractionResult.FAIL;
		}
		ItemStack weapon = attacker.getMainHandItem();
		HwItems.Kind kind = HwItems.kindOf(weapon);
		if (kind == null || !kind.melee()) {
			return InteractionResult.FAIL;
		}
		int now = attacker.level().getServer().getTickCount();
		Integer last = LAST_HIT.get(attacker.getUUID());
		// Heavy two-handed tools swing slower than a knife.
		int pause = kind.hitTicks > 0 ? kind.hitTicks : HwConfig.get().survivorHitCooldownTicks;
		if (last != null && now - last < pause) {
			return InteractionResult.FAIL;
		}
		// A swing takes breath.
		if (!Stamina.spend(attacker)) {
			return InteractionResult.FAIL;
		}
		LAST_HIT.put(attacker.getUUID(), now);
		ShapeState state = MatchManager.shapeState(shape);
		if (state != null && state.shadowTicks > 0) {
			return InteractionResult.FAIL;
		}
		attacker.level().playSound(null, shape.getX(), shape.getY(), shape.getZ(), SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.PLAYERS, 1.2f, 0.8f);
		stun(shape, kind.stun * (Perks.has(attacker, Perks.Perk.CONTENDER) ? HwConfig.get().perkContenderStun : 1f), attacker);
		if (HwItems.wear(weapon)) {
			attacker.level().playSound(null, attacker.getX(), attacker.getY(), attacker.getZ(), SoundEvents.ITEM_BREAK.value(), SoundSource.PLAYERS, 1f, 1f);
		}
		return InteractionResult.SUCCESS;
	}

	private static final Map<UUID, Integer> LAST_PUSH = new HashMap<>();

	/** Ticks until this survivor can shove again (0 = ready). */
	public static int pushCooldown(ServerPlayer player) {
		Integer last = LAST_PUSH.get(player.getUUID());
		int now = player.level().getServer().getTickCount();
		return last == null ? 0 : Math.max(0, HwConfig.get().survivorPushCooldownTicks - (now - last));
	}

	/**
	 * A survivor shoves the Shape standing right in front: he is thrown back a couple of blocks and
	 * staggers for a moment, time to get away. Not while he is in Shape Jump.
	 */
	public static boolean survivorPush(ServerPlayer player) {
		HwConfig cfg = HwConfig.get();
		if (!MatchManager.role(player).survivor() || player.isSpectator() || MatchManager.isGrabbed(player) || pushCooldown(player) > 0) {
			return false;
		}
		// So does a shove.
		if (!Stamina.spend(player)) {
			return false;
		}
		player.swing(net.minecraft.world.InteractionHand.OFF_HAND, true);
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getViewVector(1f);
		for (ServerPlayer shape : player.level().players()) {
			ShapeState state = MatchManager.shapeState(shape);
			if (state == null || state.shadowTicks > 0 || shape.isSpectator()) {
				continue;
			}
			Vec3 body = FocusPoints.bodyCenter(shape);
			if (eye.distanceTo(body) > cfg.survivorPushRange + 0.5 || Sight.angleTo(eye, look, body) > 60
				|| !Sight.canSee(player.level(), eye, body, player)) {
				continue;
			}
			LAST_PUSH.put(player.getUUID(), player.level().getServer().getTickCount());
			Vec3 away = shape.position().subtract(player.position()).multiply(1, 0, 1);
			away = away.lengthSqr() < 1.0E-4 ? look.multiply(1, 0, 1) : away;
			away = away.normalize().scale(cfg.survivorPushStrength);
			shape.setDeltaMovement(away.x, 0.25, away.z);
			shape.hurtMarked = true;
			state.pushedTicks = cfg.pushedSlowTicks;
			player.level().playSound(null, shape.getX(), shape.getY(), shape.getZ(), HwSounds.PUSH, SoundSource.PLAYERS, 1.2f, 0.9f);
			AiStats.add(player, "push");
			return true;
		}
		// Shoved at thin air: a short pause before trying again.
		LAST_PUSH.put(player.getUUID(), player.level().getServer().getTickCount() - cfg.survivorPushCooldownTicks + 10);
		return false;
	}

	/** Right-click with a lawn dart: throw it. */
	public static InteractionResult onUseItem(Player player, ItemStack stack) {
		if (!(player instanceof ServerPlayer sp) || !MatchManager.role(sp).survivor() || !HwItems.is(stack, HwItems.Kind.DART)) {
			return InteractionResult.PASS;
		}
		ServerLevel level = sp.level();
		Arrow dart = new Arrow(level, sp, stack.copyWithCount(1), null);
		dart.pickup = AbstractArrow.Pickup.DISALLOWED;
		dart.addTag(DART_TAG);
		dart.shootFromRotation(sp, sp.getXRot(), sp.getYRot(), 0f, HwConfig.get().dartSpeed, 1f);
		level.addFreshEntity(dart);
		level.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.TRIDENT_THROW.value(), SoundSource.PLAYERS, 1f, 1.3f);
		stack.shrink(1);
		return InteractionResult.SUCCESS;
	}

	/** Damage to the Shape: darts stun him instead of hurting. */
	public static void onShapeHurt(ServerPlayer shape, DamageSource source) {
		Entity direct = source.getDirectEntity();
		if (direct != null && direct.entityTags().contains(DART_TAG)) {
			ServerPlayer thrower = source.getEntity() instanceof ServerPlayer p ? p : null;
			stun(shape, HwItems.Kind.DART.stun * (thrower != null && Perks.has(thrower, Perks.Perk.LETHAL_PITCH) ? HwConfig.get().perkLethalPitchStun : 1f), thrower);
			direct.discard();
		}
	}

	// ---------------------------------------------------------------- tick: meters and flashlights

	public static void tick(MinecraftServer server) {
		HwConfig cfg = HwConfig.get();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			ShapeState state = MatchManager.shapeState(player);
			if (state != null) {
				if (state.stunnedTicks > 0) {
					state.stunnedTicks--;
				}
				state.stun = Math.max(0f, state.stun - cfg.stunDecayPerSecond / 20f);
				if (state.litTicks > 0) {
					state.litTicks--;
				}
				if (state.blindTicks > 0) {
					state.blindTicks--;
				}
			}
		}
		Iterator<Map.Entry<UUID, BlockPos>> it = LIGHTS.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, BlockPos> e = it.next();
			ServerPlayer holder = server.getPlayerList().getPlayer(e.getKey());
			if (holder == null || !holdsLight(holder)) {
				removeLight(server.overworld(), e.getValue());
				it.remove();
			}
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!holdsLight(player)) {
				continue;
			}
			// The battery runs down while it shines (counted in steps so the item isn't resent every tick).
			if (server.getTickCount() % 5 == 0 && HwItems.drain(player.getMainHandItem(), 5) <= 0) {
				SWITCHED_ON.remove(player.getUUID());
				player.sendOverlayMessage(Component.translatable("halloween.msg.battery_dead").withStyle(ChatFormatting.GRAY));
				player.level().playSound(null, player.getX(), player.getEyeY(), player.getZ(), SoundEvents.LEVER_CLICK, SoundSource.PLAYERS, 0.4f, 1.2f);
				continue;
			}
			// The battery about done: the light flickers, dropping out for a tick or a few.
			int left = HwItems.charge(player.getMainHandItem());
			if (left <= HwConfig.get().flashlightFlickerTicks && flickersOff(player, left)) {
				BlockPos lit = LIGHTS.remove(player.getUUID());
				if (lit != null) {
					removeLight(player.level(), lit);
				}
				AiStats.add(player, "flashlight.flicker");
				continue;
			}
			shine(player);
		}
	}

	/** Off this tick? The less charge, the more often and the longer it drops out. */
	private static boolean flickersOff(ServerPlayer player, int left) {
		Integer until = FLICKER.get(player.getUUID());
		int now = player.level().getServer().getTickCount();
		if (until != null && now < until) {
			return true;
		}
		float weak = 1f - left / (float) Math.max(1, HwConfig.get().flashlightFlickerTicks);
		if (player.getRandom().nextFloat() < 0.04f + 0.18f * weak) {
			FLICKER.put(player.getUUID(), now + 1 + player.getRandom().nextInt(2 + (int) (weak * 6)));
			player.level().playSound(null, player.getX(), player.getEyeY(), player.getZ(), SoundEvents.LEVER_CLICK, SoundSource.PLAYERS, 0.15f, 1.8f);
			return true;
		}
		return false;
	}

	private static final Map<UUID, Integer> FLICKER = new java.util.HashMap<>();

	private static boolean holdsLight(ServerPlayer player) {
		return SWITCHED_ON.contains(player.getUUID()) && MatchManager.role(player).survivor() && !player.isSpectator()
			&& HwItems.is(player.getMainHandItem(), HwItems.Kind.FLASHLIGHT);
	}

	/**
	 * The visible beam: short-lived glowing puffs along the light from the hand to where it lands, widening like a cone,
	 * with a brighter spot at the end. Everyone sees it, so it also gives the deputy away.
	 */
	private static void drawBeam(ServerPlayer player, Vec3 eye, Vec3 look, Vec3 end, boolean hitSomething) {
		ServerLevel level = player.level();
		Vec3 right = look.cross(new Vec3(0, 1, 0));
		right = right.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : right.normalize();
		Vec3 start = eye.add(right.scale(0.3)).add(0, -0.25, 0).add(look.scale(0.8));
		Vec3 path = end.subtract(start);
		double length = path.length();
		if (length < 0.5) {
			return;
		}
		Vec3 dir = path.scale(1 / length);
		double spread = Math.tan(Math.toRadians(HwConfig.get().flashlightConeDeg));
		for (double t = 0.3; t < length; t += 0.3 + t * 0.06) {
			Vec3 p = start.add(dir.scale(t));
			level.sendParticles(HwParticles.BEAM, true, false, p.x, p.y, p.z, 0, 0.12 + t * spread * 0.7, 0, 0, 1);
		}
		if (hitSomething) {
			Vec3 spot = end.subtract(dir.scale(0.1));
			level.sendParticles(HwParticles.BEAM, true, false, spot.x, spot.y, spot.z, 0, 0.45 + length * spread * 0.8, 0, 0, 1);
		}
	}

	private static void shine(ServerPlayer player) {
		HwConfig cfg = HwConfig.get();
		ServerLevel level = player.level();
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getViewVector(1f);
		Vec3 end = eye.add(look.scale(cfg.flashlightRange));
		BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		BlockPos spot = hit.getType() == HitResult.Type.MISS ? BlockPos.containing(end) : hit.getBlockPos().relative(hit.getDirection());
		drawBeam(player, eye, look, hit.getLocation(), hit.getType() != HitResult.Type.MISS);
		BlockPos old = LIGHTS.get(player.getUUID());
		if (!spot.equals(old)) {
			if (old != null) {
				removeLight(level, old);
			}
			if (level.getBlockState(spot).isAir()) {
				level.setBlock(spot, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, cfg.flashlightLightLevel), Block.UPDATE_CLIENTS);
				LIGHTS.put(player.getUUID(), spot.immutable());
			} else {
				LIGHTS.remove(player.getUUID());
			}
		}
		// The Shape in the beam: lit (no Shape Jump), and blinded if he looks into it.
		for (ServerPlayer other : level.players()) {
			ShapeState state = MatchManager.shapeState(other);
			if (state == null || other.isSpectator()) {
				continue;
			}
			Vec3 target = other.getEyePosition();
			double dist = eye.distanceTo(target);
			if (dist > cfg.flashlightRange || Sight.angleTo(eye, look, target) > cfg.flashlightConeDeg || !Sight.canSee(level, eye, target, player)) {
				continue;
			}
			state.litTicks = 3;
			if (Sight.angleTo(target, other.getViewVector(1f), eye) <= cfg.flashlightFaceDeg) {
				state.blindTicks = 3;
				state.senseHeld = false;
			}
		}
	}

	private static void removeLight(ServerLevel level, BlockPos pos) {
		if (level.getBlockState(pos).is(Blocks.LIGHT)) {
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
		}
	}

	public static void clear(MinecraftServer server) {
		LIGHTS.values().forEach(pos -> removeLight(server.overworld(), pos));
		LIGHTS.clear();
		LAST_HIT.clear();
		LAST_PUSH.clear();
		SWITCHED_ON.clear();
	}
}
