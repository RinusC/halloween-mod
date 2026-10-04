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
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
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
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;

/** Server-side match state: roles, the Shape's abilities, victims, grabs and kills. */
public final class MatchManager {
	public static final String VICTIM_TAG = "hw_victim";
	private static final String TEAM = "hw_match";
	private static final Identifier SPEED_ID = Halloween.id("speed");

	private static final Map<UUID, Role> ROLES = new HashMap<>();
	private static final Map<UUID, ShapeState> SHAPES = new HashMap<>();
	private static final Map<UUID, VictimState> VICTIMS = new HashMap<>();
	private static boolean dirty = true;

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

	public static void applyRole(ServerPlayer player, Role role) {
		HwConfig cfg = HwConfig.get();
		Role old = role(player);
		if (old == Role.SHAPE) {
			ShapeState state = SHAPES.remove(player.getUUID());
			if (state != null) {
				if (state.grab != null) {
					state.grab.end();
				}
				player.setInvisible(false);
				player.setSilent(false);
			}
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
		switch (role) {
			case SHAPE -> {
				ROLES.put(player.getUUID(), role);
				SHAPES.put(player.getUUID(), new ShapeState());
				player.setGameMode(GameType.ADVENTURE);
				player.getInventory().clearContent();
				ItemStack knife = new ItemStack(Items.IRON_SWORD);
				knife.set(DataComponents.CUSTOM_NAME, Component.translatable("halloween.item.knife"));
				player.getInventory().setItem(0, knife);
				player.getInventory().setSelectedSlot(0);
				maxHealth.setBaseValue(20);
				player.setHealth(player.getMaxHealth());
				scoreboard.addPlayerToTeam(player.getScoreboardName(), team);
			}
			case CIVILIAN -> {
				ROLES.put(player.getUUID(), role);
				player.setGameMode(GameType.ADVENTURE);
				player.getInventory().clearContent();
				maxHealth.setBaseValue(cfg.civilianMaxHealth);
				player.setHealth(player.getMaxHealth());
				scoreboard.addPlayerToTeam(player.getScoreboardName(), team);
			}
			case NONE -> {
				ROLES.remove(player.getUUID());
				maxHealth.setBaseValue(20);
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
			SHAPES.put(entry.getKey(), new ShapeState());
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player != null) {
				player.setInvisible(false);
				player.setSilent(false);
			}
		}
		VICTIMS.clear();
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
	}

	public static void markDirty() {
		dirty = true;
	}

	/** Stalk progress of a victim, 0 if untracked. Used by tests and debugging. */
	public static float stalkOf(Entity entity) {
		VictimState vs = VICTIMS.get(entity.getUUID());
		return vs == null ? 0 : vs.stalk;
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
			if (--vs.focusTimer <= 0) {
				vs.focus = vs.entity.getRandom().nextInt(FocusPoints.COUNT);
				vs.focusTimer = cfg.focusPointSwitchTicks;
			}
		}

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
				if (vs != null && vs.grabbed) {
					speed = -1;
				} else if (vs != null && vs.weakened()) {
					speed = cfg.weakenedSpeedModifier;
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
		if (state.grabCooldown > 0) {
			state.grabCooldown--;
		}
		if (state.jumpCooldown > 0) {
			state.jumpCooldown--;
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

		if (state.shadowTicks > 0) {
			state.shadowTicks--;
			if (state.shadowTicks == 0 || inLight(player)) {
				exitShadow(player, state);
			}
		}

		if (state.senseActive()) {
			state.fatigue = Math.min(1f, state.fatigue + 1f / (cfg.senseFatigueSeconds * 20f));
			tickStalk(player, state);
		} else {
			state.fatigue = Math.max(0f, state.fatigue - 1f / (cfg.senseRecoverySeconds * 20f));
		}

		if (state.updateTier()) {
			player.sendOverlayMessage(Component.translatable("halloween.msg.tier_up", tierName(state.tier)).withStyle(ChatFormatting.DARK_RED));
		}

		double speed = cfg.shapeSpeedModifier;
		if (state.busy()) {
			speed = -1;
		} else if (state.shadowTicks > 0) {
			speed += HwConfig.tierValue(cfg.shapeJumpSpeedModifier, state.tier);
		} else if (state.senseActive()) {
			speed += cfg.senseSpeedModifier;
		}
		setSpeed(player, speed);

		if (serverTick % 2 == 0) {
			sendShapeHud(player, state);
		}
	}

	private static void tickStalk(ServerPlayer player, ShapeState state) {
		HwConfig cfg = HwConfig.get();
		ServerLevel level = player.level();
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getViewVector(1f);
		float multiplier = 1f - cfg.senseFatiguePenalty * state.fatigue;
		for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(cfg.senseRange), MatchManager::isVictim)) {
			VictimState vs = victim(target);
			Vec3 focus = FocusPoints.get(target, vs.focus, 1f);
			double focusAngle = Sight.angleTo(eye, look, focus);
			double bodyAngle = Sight.angleTo(eye, look, FocusPoints.bodyCenter(target));
			float rate;
			if (focusAngle <= cfg.senseFocusAngleDeg) {
				rate = cfg.stalkPerSecondFocus;
			} else if (bodyAngle <= cfg.senseBodyAngleDeg) {
				rate = cfg.stalkPerSecondBody;
			} else {
				continue;
			}
			if (vs.stalk >= 100f || !Sight.canSee(level, eye, focus, player)) {
				continue;
			}
			float gain = Math.min(100f - vs.stalk, rate / 20f * multiplier);
			vs.stalk += gain;
			state.addBloodthirst(gain * cfg.bloodthirstPerStalk);
		}
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

	/** 0 ready, 1 light, 2 seen, 3 cooldown, 4 busy. */
	static int jumpBlockReason(ServerPlayer player, ShapeState state) {
		if (state.busy() || state.shadowTicks > 0) {
			return 4;
		}
		if (state.jumpCooldown > 0) {
			return 3;
		}
		if (inLight(player)) {
			return 1;
		}
		if (seenByCivilian(player)) {
			return 2;
		}
		return 0;
	}

	public static void onShapeJump(ServerPlayer player) {
		ShapeState state = SHAPES.get(player.getUUID());
		if (role(player) != Role.SHAPE || state == null) {
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
		player.setInvisible(true);
		player.setSilent(true);
		player.level().sendParticles(ParticleTypes.LARGE_SMOKE, player.getX(), player.getY() + 1, player.getZ(), 12, 0.3, 0.6, 0.3, 0.01);
		dirty = true;
	}

	static void exitShadow(ServerPlayer player, ShapeState state) {
		HwConfig cfg = HwConfig.get();
		state.shadowTicks = 0;
		state.jumpCooldownMax = HwConfig.tierValue(cfg.shapeJumpCooldownTicks, state.tier);
		state.jumpCooldown = state.jumpCooldownMax;
		player.setInvisible(false);
		player.setSilent(false);
		ServerLevel level = player.level();
		level.playSound(null, player.getX(), player.getY(), player.getZ(), HwSounds.STINGER, SoundSource.HOSTILE, cfg.stingerVolume, 1f);
		level.sendParticles(ParticleTypes.LARGE_SMOKE, player.getX(), player.getY() + 1, player.getZ(), 30, 0.3, 0.7, 0.3, 0.02);
		dirty = true;
	}

	// ---------------------------------------------------------------- combat

	public static InteractionResult onAttack(Player attacker, Entity target) {
		if (!(attacker instanceof ServerPlayer player)) {
			return InteractionResult.PASS;
		}
		Role role = role(player);
		if (role == Role.NONE) {
			return InteractionResult.PASS;
		}
		if (role == Role.CIVILIAN) {
			// Fighting back comes with weapons in a later slice; until then civilians can't hurt anyone.
			return isVictim(target) || target instanceof Player ? InteractionResult.FAIL : InteractionResult.PASS;
		}
		ShapeState state = SHAPES.get(player.getUUID());
		if (state == null || state.busy() || state.attackCooldown > 0 || !isVictim(target)) {
			return InteractionResult.FAIL;
		}
		HwConfig cfg = HwConfig.get();
		LivingEntity victim = (LivingEntity) target;
		VictimState vs = victim(victim);
		if (state.shadowTicks > 0) {
			exitShadow(player, state);
		}
		state.attackCooldown = cfg.shapeAttackCooldownTicks;
		float damage = cfg.shapeBaseDamage * HwConfig.tierValue(cfg.tierDamageMultiplier, state.tier);
		if (vs.weakened()) {
			damage *= cfg.weakenedDamageMultiplier;
		}
		ServerLevel level = player.level();
		level.playSound(null, victim.getX(), victim.getY(), victim.getZ(), HwSounds.STAB, SoundSource.HOSTILE, 1f, 1f);
		if (victim.getHealth() - damage <= 0) {
			kill(player, state, victim, false);
		} else {
			victim.hurtServer(level, player.damageSources().playerAttack(player), damage);
			bleed(level, victim, 8);
		}
		return InteractionResult.SUCCESS;
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
		state.grab = new GrabSession(player, state, victim, vs);
		return InteractionResult.SUCCESS;
	}

	public static void onQtePress(ServerPlayer player) {
		for (ShapeState state : SHAPES.values()) {
			if (state.grab != null && state.grab.victim == player) {
				state.grab.press();
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
		level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, net.minecraft.world.level.block.Blocks.REDSTONE_BLOCK.defaultBlockState()),
			victim.getX(), victim.getY() + victim.getBbHeight() * 0.6, victim.getZ(), count, 0.2, 0.25, 0.2, 0.1);
	}

	/** Kills a victim on behalf of the Shape (strike, finisher or window finisher). */
	static void kill(ServerPlayer shape, ShapeState state, LivingEntity victim, boolean window) {
		HwConfig cfg = HwConfig.get();
		ServerLevel level = (ServerLevel) victim.level();
		state.addBloodthirst(window ? cfg.bloodthirstPerWindowKill : cfg.bloodthirstPerKill);
		bleed(level, victim, 40);
		VICTIMS.remove(victim.getUUID());
		Component name = victim.getDisplayName();
		eliminate(victim);
		for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
			if (role(player) != Role.NONE) {
				player.sendSystemMessage(Component.translatable("halloween.msg.killed", name).withStyle(ChatFormatting.DARK_RED));
			}
		}
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
			victim.kill((ServerLevel) victim.level());
		}
		dirty = true;
	}

	/** Damage filter: the Shape can't be hurt yet (stuns come later), civilians can't hurt each other. */
	public static boolean allowDamage(LivingEntity entity, Entity attacker) {
		if (entity instanceof ServerPlayer player && role(player) == Role.SHAPE) {
			return false;
		}
		return !(attacker instanceof ServerPlayer source && role(source) == Role.CIVILIAN && isVictim(entity));
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
		Vec3 eye = player.getEyePosition();
		for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(cfg.senseRange), MatchManager::isVictim)) {
			VictimState vs = victim(target);
			infos.put(target.getId(), new HwNet.VictimInfo(target.getId(), vs.stalk, vs.focus,
				vs.marked() && !Sight.canSee(level, eye, FocusPoints.bodyCenter(target), player)));
		}
		for (VictimState vs : VICTIMS.values()) {
			if (vs.stalk > 0 && vs.entity.level() == level && !infos.containsKey(vs.entity.getId())) {
				infos.put(vs.entity.getId(), new HwNet.VictimInfo(vs.entity.getId(), vs.stalk, vs.focus, vs.marked()));
			}
		}
		ServerPlayNetworking.send(player, new HwNet.ShapeHud(
			state.bloodthirst, state.tier, state.fatigue, state.senseActive(), state.jumpCooldown, state.jumpCooldownMax,
			state.shadowTicks, state.grabCooldown, jumpBlockReason(player, state), new ArrayList<>(infos.values())
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

	public static Component tierName(int tier) {
		return Component.literal(switch (tier) {
			case 0 -> "I";
			case 1 -> "II";
			default -> "III";
		});
	}
}
