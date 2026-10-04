package dev.halloween.game;

import dev.halloween.Halloween;
import dev.halloween.HwConfig;
import dev.halloween.HwSounds;
import dev.halloween.Role;
import dev.halloween.net.HwNet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;

/**
 * Server-side match state: roles, the Shape's abilities, stalking, Bloodthirst, grabs, kills,
 * corpses and score. Mechanics follow docs/ORIGINAL_NOTES.md.
 */
public final class MatchManager {
	public static final String VICTIM_TAG = "hw_victim";
	public static final String CORPSE_TAG = "hw_corpse";
	private static final String TEAM = "hw_match";
	private static final Identifier SPEED_ID = Halloween.id("speed");

	private static final Map<UUID, Role> ROLES = new HashMap<>();
	private static final Map<UUID, ShapeState> SHAPES = new HashMap<>();
	private static final Map<UUID, VictimState> VICTIMS = new HashMap<>();
	private static final List<Corpse> CORPSES = new ArrayList<>();
	private static boolean dirty = true;

	public enum KillKind {
		STRIKE, EXECUTION, WINDOW
	}

	/** A body left after a kill; the Shape gains Bloodthirst by admiring it for a while. */
	private static final class Corpse {
		final ServerLevel level;
		final Vec3 pos;
		final Component name;
		int admireLeft;

		Corpse(ServerLevel level, Vec3 pos, Component name, int admireTicks) {
			this.level = level;
			this.pos = pos;
			this.name = name;
			this.admireLeft = admireTicks;
		}
	}

	private MatchManager() {
	}

	// ---------------------------------------------------------------- roles

	public static Role role(Player player) {
		return ROLES.getOrDefault(player.getUUID(), Role.NONE);
	}

	public static ShapeState shapeState(Player player) {
		return SHAPES.get(player.getUUID());
	}

	public static boolean isVictim(Entity entity) {
		if (!(entity instanceof LivingEntity living) || !living.isAlive() || living.isRemoved()) {
			return false;
		}
		if (living instanceof ServerPlayer player) {
			return role(player) == Role.CIVILIAN && !player.isSpectator();
		}
		return !(living instanceof Player) && living.entityTags().contains(VICTIM_TAG);
	}

	private static VictimState victim(LivingEntity entity) {
		return VICTIMS.computeIfAbsent(entity.getUUID(), id -> new VictimState(entity));
	}

	/** Stalk progress (0..3) of a victim, 0 if untracked. Used by tests and debugging. */
	public static float stalkOf(Entity entity) {
		VictimState vs = entity == null ? null : VICTIMS.get(entity.getUUID());
		return vs == null ? 0 : vs.stalk;
	}

	public static void applyRole(ServerPlayer player, Role role) {
		HwConfig cfg = HwConfig.get();
		Role old = role(player);
		if (old == Role.SHAPE) {
			ShapeState state = SHAPES.remove(player.getUUID());
			if (state != null && state.grab != null) {
				state.grab.end();
			}
			player.setInvisible(false);
			player.setSilent(false);
		}
		VictimState vs = VICTIMS.remove(player.getUUID());
		if (vs != null) {
			releaseGrabsOf(vs.entity);
		}

		Scoreboard scoreboard = player.level().getServer().getScoreboard();
		PlayerTeam team = scoreboard.getPlayerTeam(TEAM);
		if (team == null) {
			team = scoreboard.addPlayerTeam(TEAM);
			// Name tags would give away positions through walls.
			team.setNameTagVisibility(Team.Visibility.NEVER);
		}

		AttributeInstance maxHealth = player.getAttribute(Attributes.MAX_HEALTH);
		AttributeInstance camera = player.getAttribute(Attributes.CAMERA_DISTANCE);
		switch (role) {
			case SHAPE -> {
				ROLES.put(player.getUUID(), role);
				ShapeState state = new ShapeState();
				state.lastX = player.getX();
				state.lastZ = player.getZ();
				SHAPES.put(player.getUUID(), state);
				player.setGameMode(GameType.ADVENTURE);
				player.getInventory().clearContent();
				ItemStack knife = new ItemStack(Items.IRON_SWORD);
				knife.set(DataComponents.CUSTOM_NAME, Component.translatable("halloween.item.knife"));
				player.getInventory().setItem(0, knife);
				player.getInventory().setSelectedSlot(0);
				maxHealth.setBaseValue(20);
				camera.setBaseValue(cfg.shapeCameraDistance);
				player.setHealth(player.getMaxHealth());
				scoreboard.addPlayerToTeam(player.getScoreboardName(), team);
			}
			case CIVILIAN -> {
				ROLES.put(player.getUUID(), role);
				player.setGameMode(GameType.ADVENTURE);
				player.getInventory().clearContent();
				maxHealth.setBaseValue(cfg.civilianMaxHealth);
				camera.setBaseValue(camera.getAttribute().value().getDefaultValue());
				player.setHealth(player.getMaxHealth());
				scoreboard.addPlayerToTeam(player.getScoreboardName(), team);
			}
			case NONE -> {
				ROLES.remove(player.getUUID());
				maxHealth.setBaseValue(20);
				camera.setBaseValue(camera.getAttribute().value().getDefaultValue());
				player.getAttribute(Attributes.MOVEMENT_SPEED).removeModifier(SPEED_ID);
				if (player.isSpectator()) {
					player.setGameMode(GameType.SURVIVAL);
				}
				if (scoreboard.getPlayersTeam(player.getScoreboardName()) == team) {
					scoreboard.removePlayerFromTeam(player.getScoreboardName(), team);
				}
			}
		}
		ServerPlayNetworking.send(player, HwNet.Qte.INACTIVE);
		dirty = true;
	}

	/** Clears match progress but keeps roles. */
	public static void reset(MinecraftServer server) {
		for (Map.Entry<UUID, ShapeState> entry : SHAPES.entrySet()) {
			ShapeState state = entry.getValue();
			if (state.grab != null) {
				state.grab.end();
			}
			ShapeState fresh = new ShapeState();
			SHAPES.put(entry.getKey(), fresh);
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player != null) {
				player.setInvisible(false);
				player.setSilent(false);
				fresh.lastX = player.getX();
				fresh.lastZ = player.getZ();
			}
		}
		VICTIMS.clear();
		CORPSES.clear();
		CommandSourceStack console = server.createCommandSourceStack().withSuppressedOutput();
		server.getCommands().performPrefixedCommand(console, "kill @e[tag=" + CORPSE_TAG + "]");
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (role(player) == Role.CIVILIAN) {
				player.setGameMode(GameType.ADVENTURE);
				player.setHealth(player.getMaxHealth());
				ServerPlayNetworking.send(player, HwNet.Qte.INACTIVE);
			}
		}
		dirty = true;
	}

	public static void clearAll() {
		ROLES.clear();
		SHAPES.clear();
		VICTIMS.clear();
		CORPSES.clear();
	}

	public static void markDirty() {
		dirty = true;
	}

	// ---------------------------------------------------------------- tick

	public static void tick(MinecraftServer server) {
		HwConfig cfg = HwConfig.get();

		Iterator<VictimState> it = VICTIMS.values().iterator();
		while (it.hasNext()) {
			VictimState vs = it.next();
			if (!isVictim(vs.entity)) {
				it.remove();
				continue;
			}
			if (vs.markTicks > 0) {
				vs.markTicks--;
			}
			if (vs.staggerTicks > 0) {
				vs.staggerTicks--;
			}
		}
		CORPSES.removeIf(c -> c.admireLeft <= 0);

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Role role = role(player);
			if (role == Role.NONE) {
				continue;
			}
			player.getFoodData().setFoodLevel(20);
			player.getFoodData().setSaturation(5f);
			if (role == Role.SHAPE) {
				ShapeState state = SHAPES.computeIfAbsent(player.getUUID(), id -> new ShapeState());
				tickShape(player, state, server.getTickCount());
			} else {
				VictimState vs = VICTIMS.get(player.getUUID());
				double speed = 0;
				if (vs != null) {
					if (vs.grabbed) {
						speed = -1;
					} else {
						speed = HwConfig.at(cfg.speedByStalk, vs.level()) + (vs.staggerTicks > 0 ? cfg.staggerSpeedModifier : 0);
					}
				}
				setSpeed(player, speed);
			}
		}

		if (dirty || server.getTickCount() % 40 == 0) {
			dirty = false;
			broadcastWorld(server);
		}
	}

	private static void tickShape(ServerPlayer player, ShapeState state, int serverTick) {
		HwConfig cfg = HwConfig.get();
		if (state.attackCooldown > 0) {
			state.attackCooldown--;
		}
		if (state.comboTimer > 0 && --state.comboTimer == 0) {
			state.comboCount = 0;
		}
		if (state.grabCooldown > 0) {
			state.grabCooldown--;
		}
		if (state.pushCooldown > 0) {
			state.pushCooldown--;
		}
		if (state.jumpCooldown > 0) {
			state.jumpCooldown--;
		}
		if (state.impulseCooldown > 0) {
			state.impulseCooldown--;
		}
		if (state.dashCooldown > 0) {
			state.dashCooldown--;
		}
		if (state.recoverTicks > 0) {
			state.recoverTicks--;
		}
		if (state.grab != null) {
			state.grab.tick();
			if (state.grab != null && state.grab.isDone()) {
				state.grab = null;
			}
		}

		// Stillness is part of how fast stalking goes.
		double moved = Math.hypot(player.getX() - state.lastX, player.getZ() - state.lastZ);
		state.still = moved < 0.02;
		state.lastX = player.getX();
		state.lastZ = player.getZ();

		if (state.dashTicks > 0) {
			state.dashTicks--;
			if (state.dashTicks % 5 == 0) {
				player.level().playSound(null, player.getX(), player.getY(), player.getZ(), HwSounds.HEAVY_STEP, SoundSource.HOSTILE, cfg.dashStepVolume, 1f);
			}
			if (state.dashTicks == 0 || state.busy() || state.shadowTicks > 0) {
				state.dashTicks = 0;
			}
		}

		if (state.shadowTicks > 0) {
			state.shadowTicks--;
			if (state.shadowTicks == 0 || serverTick % 5 == 0 && exposed(player)) {
				exitShadow(player, state);
			}
		}

		if (state.senseActive()) {
			state.fatigue = Math.min(1f, state.fatigue + 1f / (cfg.senseFatigueSeconds * 20f));
		} else {
			state.fatigue = Math.max(0f, state.fatigue - 1f / (cfg.senseRecoverySeconds * 20f));
		}
		if (state.grab == null && state.shadowTicks == 0) {
			tickStalk(player, state);
			tickCorpses(player, state);
		}

		// Standing in light drains Bloodthirst.
		if (inLight(player)) {
			state.addBloodthirst(-cfg.bloodthirstLightLossPerSecond / 20f);
		}

		double speed = cfg.shapeSpeedModifier;
		if (state.busy()) {
			speed = -1;
		} else if (state.shadowTicks > 0) {
			speed += HwConfig.at(cfg.shapeJumpSpeedByTier, state.tier());
		} else if (state.focusActive()) {
			speed += cfg.focusSpeedModifier;
		} else if (state.senseActive()) {
			speed += cfg.senseSpeedModifier;
		} else if (state.dashTicks > 0) {
			speed += cfg.dashSpeedModifier;
		}
		setSpeed(player, speed);

		if (serverTick % 2 == 0) {
			sendShapeHud(player, state);
		}
	}

	private static void tickStalk(ServerPlayer player, ShapeState state) {
		HwConfig cfg = HwConfig.get();
		ServerLevel level = player.level();
		boolean sense = state.senseActive();
		double range = sense ? cfg.senseRange : cfg.passiveStalkRange;
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getViewVector(1f);
		for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(range), MatchManager::isVictim)) {
			VictimState vs = victim(target);
			float cap = sense ? 3f : cfg.passiveStalkCap;
			if (vs.stalk >= cap || vs.grabbed) {
				continue;
			}
			Vec3 body = FocusPoints.bodyCenter(target);
			double dist = eye.distanceTo(body);
			if (dist > range) {
				continue;
			}
			double bodyAngle = Sight.angleTo(eye, look, body);
			float aim;
			Vec3 seen = body;
			if (sense) {
				double best = Double.MAX_VALUE;
				for (int i = 0; i < FocusPoints.COUNT; i++) {
					Vec3 p = FocusPoints.get(target, i, 1f);
					double a = Sight.angleTo(eye, look, p);
					if (a < best) {
						best = a;
						seen = p;
					}
				}
				if (best <= cfg.reticleFocusAngleDeg) {
					aim = 1f;
				} else if (bodyAngle <= cfg.reticleBodyAngleDeg) {
					aim = cfg.bodyAimMultiplier;
					seen = body;
				} else {
					continue;
				}
			} else if (bodyAngle <= cfg.passiveStalkAngleDeg) {
				aim = 1f;
			} else {
				continue;
			}
			if (!Sight.canSee(level, eye, seen, player)) {
				continue;
			}
			double t = Math.max(0, Math.min(1, (dist - cfg.proximityNear) / Math.max(1, cfg.senseRange - cfg.proximityNear)));
			float proximity = (float) (cfg.proximityNearMultiplier + (cfg.proximityFarMultiplier - cfg.proximityNearMultiplier) * t);
			float rate = sense ? cfg.senseStalkPerSecond : cfg.passiveStalkPerSecond;
			float multiplier = aim * proximity * (state.still ? cfg.stillnessMultiplier : cfg.movingMultiplier);
			if (sense) {
				multiplier *= 1f - cfg.senseFatiguePenalty * state.fatigue;
				if (state.focusActive()) {
					multiplier *= cfg.focusMultiplier;
				}
			}
			int before = vs.level();
			vs.stalk = Math.min(cap, vs.stalk + rate / 20f * multiplier);
			int after = vs.level();
			if (after >= 1) {
				vs.markTicks = Math.max(vs.markTicks, HwConfig.at(cfg.markTicksByStalk, after));
			}
			if (after > before) {
				gainBloodthirst(player, state, cfg.bloodthirstPerStalkLevel);
				score(player, state, cfg.scoreStalkLevel * after,
					Component.translatable("halloween.score.stalk", target.getDisplayName(), after));
			}
		}
	}

	private static void tickCorpses(ServerPlayer player, ShapeState state) {
		HwConfig cfg = HwConfig.get();
		if (!state.still) {
			return;
		}
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getViewVector(1f);
		for (Corpse corpse : CORPSES) {
			if (corpse.level != player.level() || corpse.admireLeft <= 0) {
				continue;
			}
			Vec3 target = corpse.pos.add(0, 0.3, 0);
			if (eye.distanceTo(target) > cfg.corpseAdmireRange || Sight.angleTo(eye, look, target) > 30
				|| !Sight.canSee(player.level(), eye, target, player)) {
				continue;
			}
			corpse.admireLeft--;
			gainBloodthirst(player, state, cfg.bloodthirstPerCorpseSecond / 20f);
			if (corpse.admireLeft == 0) {
				score(player, state, cfg.scoreCorpse, Component.translatable("halloween.score.corpse", corpse.name));
			}
		}
	}

	private static void gainBloodthirst(ServerPlayer player, ShapeState state, float amount) {
		if (state.addBloodthirst(amount)) {
			player.sendOverlayMessage(Component.translatable("halloween.msg.tier_up", state.level()).withStyle(ChatFormatting.DARK_RED));
		}
	}

	private static void score(ServerPlayer player, ShapeState state, int points, Component... lines) {
		state.score += points;
		ServerPlayNetworking.send(player, new HwNet.ScoreEvent(points, List.of(lines)));
	}

	private static void setSpeed(ServerPlayer player, double amount) {
		AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
		if (amount == 0) {
			speed.removeModifier(SPEED_ID);
		} else {
			speed.addOrUpdateTransientModifier(new AttributeModifier(SPEED_ID, Math.max(-1, amount), AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		}
	}

	// ---------------------------------------------------------------- Shape Jump

	static boolean inLight(ServerPlayer player) {
		int threshold = HwConfig.get().lightThreshold;
		ServerLevel level = player.level();
		return level.getBrightness(LightLayer.BLOCK, player.blockPosition()) > threshold
			|| level.getBrightness(LightLayer.BLOCK, BlockPos.containing(player.getEyePosition())) > threshold;
	}

	static boolean seenByCivilian(ServerPlayer shape) {
		HwConfig cfg = HwConfig.get();
		ServerLevel level = shape.level();
		Vec3 target = FocusPoints.bodyCenter(shape);
		for (ServerPlayer civilian : level.players()) {
			if (role(civilian) != Role.CIVILIAN || civilian.isSpectator()) {
				continue;
			}
			Vec3 eye = civilian.getEyePosition();
			if (eye.distanceTo(target) > cfg.civilianSightRange) {
				continue;
			}
			if (Sight.angleTo(eye, civilian.getViewVector(1f), target) <= cfg.civilianSightFovDeg && Sight.canSee(level, eye, target, civilian)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Shape Jump works when nobody sees the Shape OR he stands in shadow (per the in-game tutorial);
	 * only being watched while lit blocks it.
	 */
	static boolean exposed(ServerPlayer player) {
		return inLight(player) && seenByCivilian(player);
	}

	/** 0 ready, 2 watched while lit, 3 cooldown, 4 busy. */
	static int jumpBlockReason(ServerPlayer player, ShapeState state) {
		if (state.busy() || state.shadowTicks > 0) {
			return 4;
		}
		if (state.jumpCooldown > 0) {
			return 3;
		}
		return exposed(player) ? 2 : 0;
	}

	public static void onShapeJump(ServerPlayer player) {
		ShapeState state = SHAPES.get(player.getUUID());
		if (role(player) != Role.SHAPE || state == null) {
			return;
		}
		// Pressing again cancels the jump early.
		if (state.shadowTicks > 0) {
			exitShadow(player, state);
			return;
		}
		int reason = jumpBlockReason(player, state);
		if (reason != 0) {
			if (reason != 4) {
				player.sendOverlayMessage(Component.translatable("halloween.msg.jump_blocked." + reason).withStyle(ChatFormatting.GRAY));
			}
			return;
		}
		state.shadowTicks = HwConfig.get().shapeJumpDurationTicks;
		state.dashTicks = 0;
		player.setInvisible(true);
		player.setSilent(true);
		player.level().sendParticles(ParticleTypes.LARGE_SMOKE, player.getX(), player.getY() + 1, player.getZ(), 12, 0.3, 0.6, 0.3, 0.01);
		dirty = true;
	}

	static void exitShadow(ServerPlayer player, ShapeState state) {
		HwConfig cfg = HwConfig.get();
		state.shadowTicks = 0;
		state.jumpCooldownMax = HwConfig.at(cfg.shapeJumpCooldownByTier, state.tier());
		state.jumpCooldown = state.jumpCooldownMax;
		player.setInvisible(false);
		player.setSilent(false);
		ServerLevel level = player.level();
		level.playSound(null, player.getX(), player.getY(), player.getZ(), HwSounds.STINGER, SoundSource.HOSTILE, cfg.stingerVolume, 1f);
		level.sendParticles(ParticleTypes.LARGE_SMOKE, player.getX(), player.getY() + 1, player.getZ(), 30, 0.3, 0.7, 0.3, 0.02);
		dirty = true;
	}

	// ---------------------------------------------------------------- other abilities

	public static void onDash(ServerPlayer player) {
		ShapeState state = SHAPES.get(player.getUUID());
		if (role(player) != Role.SHAPE || state == null || state.busy() || state.shadowTicks > 0 || state.dashCooldown > 0 || state.dashTicks > 0) {
			return;
		}
		HwConfig cfg = HwConfig.get();
		state.dashTicks = cfg.dashTicks;
		state.dashCooldownMax = HwConfig.at(cfg.dashCooldownByTier, state.tier());
		state.dashCooldown = state.dashCooldownMax;
	}

	public static void onPush(ServerPlayer player) {
		ShapeState state = SHAPES.get(player.getUUID());
		if (role(player) != Role.SHAPE || state == null || state.busy() || state.pushCooldown > 0) {
			return;
		}
		HwConfig cfg = HwConfig.get();
		if (state.shadowTicks > 0) {
			exitShadow(player, state);
		}
		player.swing(InteractionHand.OFF_HAND, true);
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getViewVector(1f);
		LivingEntity best = null;
		double bestDist = Double.MAX_VALUE;
		for (LivingEntity target : player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(cfg.pushRange), MatchManager::isVictim)) {
			Vec3 body = FocusPoints.bodyCenter(target);
			double d = eye.distanceTo(body);
			if (d <= cfg.pushRange + 0.5 && Sight.angleTo(eye, look, body) <= 50 && d < bestDist) {
				best = target;
				bestDist = d;
			}
		}
		if (best == null) {
			state.pushCooldown = 10;
			return;
		}
		state.pushCooldown = cfg.pushCooldownTicks;
		Vec3 away = best.position().subtract(player.position()).multiply(1, 0, 1).normalize().scale(cfg.pushStrength);
		best.setDeltaMovement(away.x, 0.3, away.z);
		best.hurtMarked = true;
		victim(best).staggerTicks = cfg.pushStaggerTicks;
		player.level().playSound(null, best.getX(), best.getY(), best.getZ(), HwSounds.PUSH, SoundSource.HOSTILE, 1f, 1f);
	}

	public static void onImpulse(ServerPlayer player) {
		ShapeState state = SHAPES.get(player.getUUID());
		if (role(player) != Role.SHAPE || state == null || state.busy() || state.impulseCooldown > 0) {
			return;
		}
		HwConfig cfg = HwConfig.get();
		ServerLevel level = player.level();
		List<Integer> outdoor = new ArrayList<>();
		List<Vec3> indoor = new ArrayList<>();
		for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(cfg.impulseRange), MatchManager::isVictim)) {
			if (level.canSeeSky(BlockPos.containing(target.getEyePosition()).above())) {
				outdoor.add(target.getId());
			} else {
				// Indoors only the building gives itself away, not the exact spot.
				var r = player.getRandom();
				indoor.add(target.position().add((r.nextDouble() - 0.5) * 6, 0, (r.nextDouble() - 0.5) * 6));
			}
		}
		state.impulseCooldownMax = HwConfig.at(cfg.impulseCooldownByTier, state.tier());
		state.impulseCooldown = state.impulseCooldownMax;
		level.playSound(null, player.getX(), player.getY(), player.getZ(), HwSounds.IMPULSE, SoundSource.HOSTILE, 1f, 1f);
		ServerPlayNetworking.send(player, new HwNet.Impulse(outdoor, indoor, cfg.impulseDurationTicks));
	}

	// ---------------------------------------------------------------- combat

	/** Vanilla attacks: the Shape attacks through {@link #onShapeAttack}, civilians can't hurt anyone yet. */
	public static InteractionResult onVanillaAttack(Player attacker, Entity target) {
		if (!(attacker instanceof ServerPlayer player)) {
			return InteractionResult.PASS;
		}
		Role role = role(player);
		if (role == Role.SHAPE) {
			return InteractionResult.FAIL;
		}
		if (role == Role.CIVILIAN) {
			return isVictim(target) || target instanceof Player ? InteractionResult.FAIL : InteractionResult.PASS;
		}
		return InteractionResult.PASS;
	}

	public static void onShapeAttack(ServerPlayer player, int targetId, boolean heavy) {
		ShapeState state = SHAPES.get(player.getUUID());
		if (role(player) != Role.SHAPE || state == null || state.busy() || state.attackCooldown > 0) {
			return;
		}
		HwConfig cfg = HwConfig.get();
		ServerLevel level = player.level();
		if (state.shadowTicks > 0) {
			exitShadow(player, state);
		}
		state.dashTicks = 0;
		player.swing(InteractionHand.MAIN_HAND, true);
		state.attackCooldown = heavy ? cfg.heavyCooldownTicks : cfg.lightCooldownTicks;
		Entity entity = targetId >= 0 ? level.getEntity(targetId) : null;
		Vec3 eye = player.getEyePosition();
		if (!(entity instanceof LivingEntity victim) || !isVictim(victim)
			|| eye.distanceTo(FocusPoints.bodyCenter(victim)) > cfg.attackReach + 0.6
			|| !Sight.canSee(level, eye, FocusPoints.bodyCenter(victim), player)) {
			level.playSound(null, player.getX(), player.getY(), player.getZ(), HwSounds.SWING, SoundSource.HOSTILE, 0.8f, heavy ? 0.7f : 1f);
			state.comboCount = 0;
			state.comboTimer = 0;
			return;
		}
		VictimState vs = victim(victim);
		state.comboCount = state.comboTimer > 0 ? Math.min(state.comboCount + 1, cfg.comboMultiplier.length - 1) : 0;
		state.comboTimer = cfg.comboWindowTicks;
		float damage = (heavy ? cfg.heavyDamage : cfg.lightDamage)
			* HwConfig.at(cfg.comboMultiplier, state.comboCount)
			* HwConfig.at(cfg.damageMultiplierByTier, state.tier())
			* vs.damageTakenMultiplier();
		level.playSound(null, victim.getX(), victim.getY(), victim.getZ(), HwSounds.STAB, SoundSource.HOSTILE, 1f, heavy ? 0.8f : 1.05f);
		if (heavy) {
			vs.staggerTicks = Math.max(vs.staggerTicks, 15);
		}
		if (victim.getHealth() - damage <= 0) {
			kill(player, state, victim, KillKind.STRIKE, false);
		} else {
			victim.hurtServer(level, player.damageSources().playerAttack(player), damage);
			// Strikes don't throw victims around; only the push does.
			victim.setDeltaMovement(Vec3.ZERO);
			victim.hurtMarked = true;
			bleed(level, victim, heavy ? 14 : 8);
		}
	}

	public static InteractionResult onUseEntity(Player user, InteractionHand hand, Entity target) {
		if (!(user instanceof ServerPlayer player) || role(player) != Role.SHAPE) {
			return InteractionResult.PASS;
		}
		if (hand != InteractionHand.MAIN_HAND) {
			return InteractionResult.FAIL;
		}
		ShapeState state = SHAPES.get(player.getUUID());
		HwConfig cfg = HwConfig.get();
		if (state == null || state.busy() || state.grabCooldown > 0 || !isVictim(target)
			|| player.distanceTo(target) > cfg.grabRange) {
			return InteractionResult.FAIL;
		}
		LivingEntity victim = (LivingEntity) target;
		VictimState vs = victim(victim);
		if (vs.grabbed) {
			return InteractionResult.FAIL;
		}
		if (state.shadowTicks > 0) {
			exitShadow(player, state);
		}
		state.dashTicks = 0;
		gainBloodthirst(player, state, cfg.bloodthirstPerGrab);
		score(player, state, cfg.scoreGrab, Component.translatable("halloween.score.grab", victim.getDisplayName(), vs.level()));
		state.grab = new GrabSession(player, state, victim, vs);
		return InteractionResult.SUCCESS;
	}

	public static void onQtePress(ServerPlayer player) {
		for (ShapeState state : SHAPES.values()) {
			if (state.grab != null && (state.grab.victim == player || SHAPES.get(player.getUUID()) == state)) {
				state.grab.press(player);
				return;
			}
		}
	}

	private static void releaseGrabsOf(LivingEntity victim) {
		for (ShapeState state : SHAPES.values()) {
			if (state.grab != null && state.grab.victim == victim) {
				state.grab.end();
			}
		}
	}

	static void bleed(ServerLevel level, LivingEntity victim, int count) {
		level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.REDSTONE_BLOCK.defaultBlockState()),
			victim.getX(), victim.getY() + victim.getBbHeight() * 0.6, victim.getZ(), count, 0.2, 0.25, 0.2, 0.1);
	}

	/** Kills a victim on behalf of the Shape, leaves a corpse and pays out Bloodthirst and score. */
	static void kill(ServerPlayer shape, ShapeState state, LivingEntity victim, KillKind kind, boolean bloody) {
		HwConfig cfg = HwConfig.get();
		ServerLevel level = (ServerLevel) victim.level();
		VictimState vs = VICTIMS.get(victim.getUUID());
		int stalkLevel = vs == null ? 0 : vs.level();
		Component name = victim.getDisplayName();
		Vec3 pos = victim.position();
		float yaw = victim.getYRot();

		gainBloodthirst(shape, state, cfg.bloodthirstPerKill + (kind == KillKind.WINDOW ? cfg.bloodthirstPerWindowKill : 0));
		bleed(level, victim, bloody ? 80 : 40);
		List<Component> lines = new ArrayList<>();
		int points = cfg.scoreKill + cfg.scoreKillPerStalkLevel * stalkLevel;
		lines.add(Component.translatable("halloween.score.kill", name, stalkLevel));
		if (kind == KillKind.WINDOW) {
			points += cfg.scoreWindow;
			lines.add(Component.translatable("halloween.score.window"));
		}
		if (bloody) {
			points += cfg.scoreBloody;
			lines.add(Component.translatable("halloween.score.bloody"));
		}
		score(shape, state, points, lines.toArray(Component[]::new));

		eliminate(victim);
		spawnCorpse(level, pos, yaw);
		CORPSES.add(new Corpse(level, pos, name, cfg.corpseAdmireTicks));
		for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
			if (role(player) != Role.NONE) {
				player.sendSystemMessage(Component.translatable("halloween.msg.killed", name).withStyle(ChatFormatting.DARK_RED));
			}
		}
	}

	private static void spawnCorpse(ServerLevel level, Vec3 pos, float yaw) {
		CommandSourceStack console = level.getServer().createCommandSourceStack().withLevel(level).withSuppressedOutput();
		String command = String.format(Locale.ROOT,
			"summon minecraft:mannequin %.2f %.2f %.2f {Tags:[\"%s\"],pose:\"sleeping\",immovable:1b,Invulnerable:1b,hide_description:1b,Rotation:[%.1ff,0f]}",
			pos.x, pos.y, pos.z, CORPSE_TAG, yaw);
		level.getServer().getCommands().performPrefixedCommand(console, command);
	}

	/** Takes a victim out of the match: civilian players become spectators, mannequins die. */
	public static void eliminate(LivingEntity victim) {
		releaseGrabsOf(victim);
		VICTIMS.remove(victim.getUUID());
		if (victim instanceof ServerPlayer player) {
			player.setHealth(player.getMaxHealth());
			player.setGameMode(GameType.SPECTATOR);
			player.connection.send(new ClientboundSetTitleTextPacket(Component.translatable("halloween.msg.you_died").withStyle(ChatFormatting.DARK_RED)));
			ServerPlayNetworking.send(player, HwNet.Qte.INACTIVE);
		} else {
			victim.discard();
		}
		dirty = true;
	}

	/**
	 * Damage filter. The Shape can't die (stuns come later) but loses Bloodthirst from any damage
	 * or a hard fall; civilians can't hurt each other.
	 */
	public static boolean allowDamage(LivingEntity entity, DamageSource source) {
		if (entity instanceof ServerPlayer player && role(player) == Role.SHAPE) {
			ShapeState state = SHAPES.get(player.getUUID());
			if (state != null) {
				HwConfig cfg = HwConfig.get();
				state.addBloodthirst(-(source.is(DamageTypeTags.IS_FALL) ? cfg.bloodthirstFallLoss : cfg.bloodthirstDamageLoss));
			}
			return false;
		}
		return !(source.getEntity() instanceof ServerPlayer attacker && role(attacker) == Role.CIVILIAN && isVictim(entity));
	}

	/** Civilians who would die from anything else (falls etc.) are eliminated instead. */
	public static boolean allowDeath(LivingEntity entity) {
		if (entity instanceof ServerPlayer player) {
			Role role = role(player);
			if (role == Role.SHAPE) {
				player.setHealth(player.getMaxHealth());
				return false;
			}
			if (role == Role.CIVILIAN) {
				eliminate(player);
				return false;
			}
		}
		return true;
	}

	// ---------------------------------------------------------------- sync

	private static void sendShapeHud(ServerPlayer player, ShapeState state) {
		HwConfig cfg = HwConfig.get();
		ServerLevel level = player.level();
		Map<Integer, HwNet.VictimInfo> infos = new LinkedHashMap<>();
		List<Vec3> beams = new ArrayList<>();
		boolean showBeams = state.shadowTicks > 0 && state.level() >= cfg.beamsMinLevel;
		int alive = 0;
		boolean anyUnderThree = false;
		for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(256), MatchManager::isVictim)) {
			alive++;
			VictimState vs = VICTIMS.get(target.getUUID());
			float stalk = vs == null ? 0 : vs.stalk;
			if (stalk < 3f) {
				anyUnderThree = true;
			}
			if (showBeams) {
				beams.add(target.position());
			}
			if (stalk > 0 || player.distanceTo(target) <= cfg.senseRange) {
				infos.put(target.getId(), new HwNet.VictimInfo(target.getId(), stalk, vs != null && vs.marked(), target.getDisplayName()));
			}
		}
		List<Component> objectives = new ArrayList<>();
		objectives.add(Component.translatable("halloween.objective.kill", alive));
		if (anyUnderThree && alive > 0) {
			objectives.add(Component.translatable("halloween.objective.stalk"));
		}
		ServerPlayNetworking.send(player, new HwNet.ShapeHud(
			state.bloodthirst, state.score, state.fatigue, state.senseActive(), state.focusActive(),
			state.jumpCooldown, state.jumpCooldownMax, state.shadowTicks, jumpBlockReason(player, state),
			state.dashCooldown, state.dashCooldownMax, state.dashTicks,
			state.impulseCooldown, state.impulseCooldownMax,
			state.pushCooldown, state.grabCooldown, state.attackCooldown,
			new ArrayList<>(infos.values()), beams, objectives
		));
	}

	private static void broadcastWorld(MinecraftServer server) {
		List<HwNet.RoleEntry> roles = new ArrayList<>();
		int shapeId = -1;
		boolean shadow = false;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Role role = role(player);
			if (role == Role.NONE) {
				continue;
			}
			roles.add(new HwNet.RoleEntry(player.getUUID(), role));
			ShapeState state = SHAPES.get(player.getUUID());
			if (role == Role.SHAPE && state != null && shapeId == -1) {
				shapeId = player.getId();
				shadow = state.shadowTicks > 0;
			}
		}
		HwNet.WorldSync sync = new HwNet.WorldSync(roles, shapeId, shadow);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			ServerPlayNetworking.send(player, sync);
		}
	}
}
