package dev.halloween.game;

import dev.halloween.HwConfig;
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

	private Fight() {
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
		if (last != null && now - last < HwConfig.get().survivorHitCooldownTicks) {
			return InteractionResult.FAIL;
		}
		LAST_HIT.put(attacker.getUUID(), now);
		ShapeState state = MatchManager.shapeState(shape);
		if (state != null && state.shadowTicks > 0) {
			return InteractionResult.FAIL;
		}
		attacker.level().playSound(null, shape.getX(), shape.getY(), shape.getZ(), SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.PLAYERS, 1.2f, 0.8f);
		stun(shape, kind.stun, attacker);
		if (HwItems.wear(weapon)) {
			attacker.level().playSound(null, attacker.getX(), attacker.getY(), attacker.getZ(), SoundEvents.ITEM_BREAK.value(), SoundSource.PLAYERS, 1f, 1f);
		}
		return InteractionResult.SUCCESS;
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
			stun(shape, HwItems.Kind.DART.stun, source.getEntity() instanceof ServerPlayer thrower ? thrower : null);
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
			if (holdsLight(player)) {
				shine(player);
			}
		}
	}

	private static boolean holdsLight(ServerPlayer player) {
		return MatchManager.role(player).survivor() && !player.isSpectator() && HwItems.is(player.getMainHandItem(), HwItems.Kind.FLASHLIGHT);
	}

	private static void shine(ServerPlayer player) {
		HwConfig cfg = HwConfig.get();
		ServerLevel level = player.level();
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getViewVector(1f);
		Vec3 end = eye.add(look.scale(cfg.flashlightRange));
		BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		BlockPos spot = hit.getType() == HitResult.Type.MISS ? BlockPos.containing(end) : hit.getBlockPos().relative(hit.getDirection());
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
	}
}
