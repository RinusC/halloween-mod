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
	private static final Identifier NO_JUMP_ID = Halloween.id("no_jump");

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
		boolean police;
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
			return role(player).survivor() && !player.isSpectator();
		}
		return !(living instanceof Player) && living.entityTags().contains(VICTIM_TAG);
	}

	/** A victim the Shape can learn about and reach: not hiding in a locker. */
	public static boolean exposed(Entity entity) {
		return isVictim(entity) && !Lockers.isInside(entity) && !Exits.isSeated(entity);
	}

	public static boolean isStunned(ServerPlayer player) {
		ShapeState state = SHAPES.get(player.getUUID());
		return state != null && state.stunnedTicks > 0;
	}

	public static boolean isGrabbed(ServerPlayer player) {
		VictimState vs = VICTIMS.get(player.getUUID());
		return vs != null && vs.grabbed;
	}

	/** Is this victim (player or not) in the Shape's hands right now? */
	public static boolean isHeld(LivingEntity entity) {
		VictimState vs = VICTIMS.get(entity.getUUID());
		return vs != null && vs.grabbed;
	}

	private static VictimState victim(LivingEntity entity) {
		return VICTIMS.computeIfAbsent(entity.getUUID(), id -> new VictimState(entity));
	}

	/** Stalk progress (0..3) of a victim, 0 if untracked. Used by tests and debugging. */
	public static float stalkOf(Entity entity) {
		VictimState vs = entity == null ? null : VICTIMS.get(entity.getUUID());
		return vs == null ? 0 : vs.stalk;
	}

	/** Marked by his stalking: the Shape's screen outlines them red wherever they are in his line of sight. */
	public static boolean marked(Entity entity) {
		VictimState vs = entity == null ? null : VICTIMS.get(entity.getUUID());
		return vs != null && vs.marked();
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
		if (old == Role.RUNT && role != Role.RUNT) {
			Runt.leave(player);
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
				// The Detection Impulse isn't ready at the start: he has to earn the first one.
				state.impulseCooldownMax = HwConfig.at(cfg.impulseCooldownByTier, 0);
				state.impulseCooldown = state.impulseCooldownMax;
				SHAPES.put(player.getUUID(), state);
				player.setGameMode(GameType.ADVENTURE);
				player.getInventory().clearContent();
				if (!Demogorgon.active()) {
					// Michael's kitchen knife (the Demogorgon has his claws).
					ItemStack knife = new ItemStack(Items.IRON_SWORD);
					knife.set(DataComponents.CUSTOM_NAME, Component.translatable("halloween.item.knife"));
					knife.set(DataComponents.ITEM_MODEL, Halloween.id("michael_knife"));
					player.getInventory().setItem(0, knife);
				}
				player.getInventory().setSelectedSlot(0);
				maxHealth.setBaseValue(20);
				camera.setBaseValue(cfg.shapeCameraDistance);
				player.setHealth(player.getMaxHealth());
				scoreboard.addPlayerToTeam(player.getScoreboardName(), team);
			}
			case CIVILIAN, DEPUTY -> {
				ROLES.put(player.getUUID(), role);
				player.setGameMode(GameType.ADVENTURE);
				player.getInventory().clearContent();
				maxHealth.setBaseValue(cfg.civilianMaxHealth + Perks.extraHealth(player));
				camera.setBaseValue(camera.getAttribute().value().getDefaultValue());
				player.setHealth(player.getMaxHealth());
				scoreboard.addPlayerToTeam(player.getScoreboardName(), team);
			}
			case RUNT -> {
				ROLES.put(player.getUUID(), role);
				Runt.become(player);
				camera.setBaseValue(camera.getAttribute().value().getDefaultValue());
				scoreboard.addPlayerToTeam(player.getScoreboardName(), team);
			}
			case NONE -> {
				ROLES.remove(player.getUUID());
				maxHealth.setBaseValue(20);
				camera.setBaseValue(camera.getAttribute().value().getDefaultValue());
				player.getAttribute(Attributes.MOVEMENT_SPEED).removeModifier(SPEED_ID);
				noJumping(player, false);
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
		// Michael, a survivor of the game, the police uniform, or their own skin again.
		Skins.sync(player.level().getServer());
	}

	/** Clears match progress but keeps roles. */
	public static void reset(MinecraftServer server) {
		for (Map.Entry<UUID, ShapeState> entry : SHAPES.entrySet()) {
			ShapeState state = entry.getValue();
			if (state.grab != null) {
				state.grab.end();
			}
			ShapeState fresh = new ShapeState();
			fresh.impulseCooldownMax = HwConfig.at(HwConfig.get().impulseCooldownByTier, 0);
			fresh.impulseCooldown = fresh.impulseCooldownMax;
			SHAPES.put(entry.getKey(), fresh);
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player != null) {
				player.setInvisible(false);
				player.setSilent(false);
				fresh.lastX = player.getX();
				fresh.lastZ = player.getZ();
			}
		}
		DoorLocks.clear(server);
		Windows.clear(server);
		VICTIMS.clear();
		CORPSES.clear();
		CommandSourceStack console = server.createCommandSourceStack().withSuppressedOutput();
		server.getCommands().performPrefixedCommand(console, "kill @e[tag=" + CORPSE_TAG + "]");
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (role(player).survivor()) {
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
		MapJob.tick(server);
		Match.tick(server);
		Tutorial.tick(server);
		Lobby.tick(server);
		Leftovers.tick(server);
		Noise.tick(server);
		Traps.tick(server);
		Runt.tick(server);
		ShapeBrain.tick(server);
		Demogorgon.tick(server);
		Panic.tick(server);
		Stamina.tick(server);
		Holds.tick(server);
		Fight.tick(server);
		Gear.tick(server);
		Music.tick(server);
		DoorLocks.tick(server);
		Windows.tick(server);
		Lockers.tick(server);
		Talks.tick(server);
		Minigames.tick(server);
		Bots.tick(server);
		Townsfolk.tick(server);
		Police.tick(server);
		Guns.tick(server);
		Blood.tick(server);

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
			noJumping(player, true);
			if (role == Role.SHAPE) {
				ShapeState state = SHAPES.computeIfAbsent(player.getUUID(), id -> new ShapeState());
				tickShape(player, state, server.getTickCount());
			} else {
				VictimState vs = VICTIMS.get(player.getUUID());
				double speed = 0;
				if (Windows.isVaulting(player) || Match.frozen() || Lockers.isInside(player) || Minigames.active(player)) {
					speed = -1;
				} else if (vs != null) {
					if (vs.grabbed) {
						speed = -1;
					} else {
						speed = HwConfig.at(cfg.speedByStalk, vs.level()) + (vs.staggerTicks > 0 ? cfg.staggerSpeedModifier : 0);
					}
				}
				if (speed > -1 && Perks.has(player, Perks.Perk.STUBBORN) && player.getHealth() < player.getMaxHealth() * cfg.perkStubbornBelow) {
					// Wounded and stubborn: pushing on harder.
					speed += cfg.perkStubbornSpeed;
				}
				if (speed > -1 && Guns.aiming(player)) {
					// Gun raised: careful steps.
					speed = Math.max(-1, speed + cfg.aimSpeedModifier);
				}
				// Rummaging through a container you shuffle along at best.
				if (speed > -1 && Holds.searching(player)) {
					speed = Math.max(-1, speed + cfg.searchSpeedModifier);
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
			if (state.dashTicks == 0 || state.busy() || state.shadowTicks > 0) {
				state.dashTicks = 0;
			}
		}

		// Shape Jump lasts until the Shape leaves it himself.
		if (state.shadowTicks > 0) {
			state.shadowTicks++;
		}

		if (state.senseActive() && !Events.noFatigue()) {
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
		if (state.grab != null && state.grab.struggling() && state.stunnedTicks == 0 && !Match.frozen()) {
			// Holding someone: he can still shuffle along, very slowly, dragging them.
			// (The Demogorgon, eating, stays put over them.)
			speed = Demogorgon.active() ? -1 : cfg.grabSpeedModifier;
		} else if (state.busy() || Match.frozen() || state.trappedTicks > 0 && state.shadowTicks == 0) {
			speed = -1;
		} else if (state.shadowTicks > 0) {
			speed += HwConfig.at(cfg.shapeJumpSpeedByTier, state.tier());
		} else if (Demogorgon.charging()) {
			// Gathering himself for a Shred.
			speed += cfg.senseSpeedModifier;
		} else if (state.focusActive()) {
			speed += cfg.focusSpeedModifier;
		} else if (state.senseActive()) {
			speed += cfg.senseSpeedModifier;
		} else if (state.dashTicks > 0) {
			speed += cfg.dashSpeedModifier;
		}
		if (Demogorgon.active() && speed > -1) {
			// The Demogorgon is faster than Michael.
			speed = (1 + speed) * cfg.demoSpeedFactor - 1;
		}
		if (state.trappedTicks > 0) {
			state.trappedTicks--;
		}
		if (state.pushedTicks > 0) {
			// Shoved by a survivor: staggering for a moment.
			state.pushedTicks--;
			if (speed > -1) {
				speed += cfg.pushedSlowModifier;
			}
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
		// Aim angles come from the camera ray (what the reticle shows); sight is still checked from the eyes.
		Vec3 aimFrom = aimOrigin(player, state);
		Vec3 look = aimDirection(player, state);
		for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(range), MatchManager::exposed)) {
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
			double bodyAngle = Sight.angleTo(aimFrom, look, body);
			float aim;
			Vec3 seen = body;
			// The blue rings (focus points) on head, chest, hands, legs: the reticle on one stalks much faster.
			double best = Double.MAX_VALUE;
			Vec3 point = body;
			for (int i = 0; i < FocusPoints.COUNT; i++) {
				Vec3 p = FocusPoints.get(target, i, 1f);
				double a = Sight.angleTo(aimFrom, look, p);
				if (a < best) {
					best = a;
					point = p;
				}
			}
			if (sense && best <= cfg.reticleFocusAngleDeg && Sight.canSee(level, eye, point, player)) {
				aim = cfg.focusPointMultiplier;
				seen = point;
			} else if (sense && bodyAngle <= cfg.reticleBodyAngleDeg) {
				aim = cfg.bodyAimMultiplier;
			} else if (!sense && bodyAngle <= cfg.passiveStalkAngleDeg) {
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
			if (Perks.has(target, Perks.Perk.ELUSORY)) {
				multiplier *= cfg.perkElusoryFactor;
			}
			// Samhain night (see Events).
			multiplier *= Events.stalkFactor();
			int before = vs.level();
			vs.stalk = Math.min(cap, vs.stalk + rate / 20f * multiplier);
			int after = vs.level();
			if (after >= 1) {
				vs.markTicks = Math.max(vs.markTicks, HwConfig.at(cfg.markTicksByStalk, after));
			}
			if (after > before) {
				gainBloodthirst(player, state, cfg.bloodthirstPerStalkLevel);
				score(player, state, policeHalf(target, cfg.scoreStalkLevel * after),
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
				score(player, state, corpse.police ? cfg.scoreCorpse / 2 : cfg.scoreCorpse, Component.translatable("halloween.score.corpse", corpse.name));
			}
		}
	}

	private static void gainBloodthirst(ServerPlayer player, ShapeState state, float amount) {
		if (state.addBloodthirst(amount)) {
			player.sendOverlayMessage(Component.translatable("halloween.msg.tier_up", state.level()).withStyle(ChatFormatting.DARK_RED));
		}
	}

	/** Officers are worth half: they came looking for him. */
	private static int policeHalf(LivingEntity victim, int points) {
		return Police.isOfficer(victim) ? points / 2 : points;
	}

	private static void score(ServerPlayer player, ShapeState state, int points, Component... lines) {
		state.score += points;
		ServerPlayNetworking.send(player, new HwNet.ScoreEvent(points, List.of(lines)));
		if (lines.length > 0) {
			Match.noteBest(player.getDisplayName(), points, lines[0]);
		}
	}

	/** Nobody jumps in a match: the game is on foot, and the maps are built for it. */
	public static void noJumping(net.minecraft.world.entity.LivingEntity body, boolean on) {
		AttributeInstance jump = body.getAttribute(Attributes.JUMP_STRENGTH);
		if (jump == null) {
			return;
		}
		if (on && !HwConfig.get().jumping) {
			if (!jump.hasModifier(NO_JUMP_ID)) {
				jump.addTransientModifier(new AttributeModifier(NO_JUMP_ID, -1, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
			}
		} else {
			jump.removeModifier(NO_JUMP_ID);
		}
	}

	private static void setSpeed(ServerPlayer player, double amount) {
		AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
		AttributeModifier current = speed.getModifier(SPEED_ID);
		if (amount == 0) {
			speed.removeModifier(SPEED_ID);
		} else if (current == null || Math.abs(current.amount() - Math.max(-1, amount)) > 1.0E-6) {
			// Only when it changes: a new modifier every tick sends his speed to every client every tick, and his
			// own client, wiping and re-adding it as it lands, steps unevenly.
			speed.addOrUpdateTransientModifier(new AttributeModifier(SPEED_ID, Math.max(-1, amount), AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		}
	}

	// ---------------------------------------------------------------- Shape Jump

	/** Camera ray from the client if it is fresh and plausible, otherwise the eyes. */
	static Vec3 aimOrigin(ServerPlayer player, ShapeState state) {
		return freshAim(player, state) ? state.aimOrigin : player.getEyePosition();
	}

	static Vec3 aimDirection(ServerPlayer player, ShapeState state) {
		return freshAim(player, state) ? state.aimDirection : player.getViewVector(1f);
	}

	private static boolean freshAim(ServerPlayer player, ShapeState state) {
		return state.aimOrigin != null && player.level().getServer().getTickCount() - state.aimTick <= 10
			&& state.aimOrigin.distanceTo(player.getEyePosition()) <= 6;
	}

	public static void onAim(ServerPlayer player, Vec3 origin, Vec3 direction) {
		ShapeState state = SHAPES.get(player.getUUID());
		if (state == null || direction.lengthSqr() < 1.0E-6) {
			return;
		}
		state.aimOrigin = origin;
		state.aimDirection = direction.normalize();
		state.aimTick = player.level().getServer().getTickCount();
	}

	static boolean inLight(ServerPlayer player) {
		int threshold = HwConfig.get().lightThreshold;
		ServerLevel level = player.level();
		ShapeState state = SHAPES.get(player.getUUID());
		if (state != null && state.litTicks > 0) {
			// Caught in a flashlight beam.
			return true;
		}
		return level.getBrightness(LightLayer.BLOCK, player.blockPosition()) > threshold
			|| level.getBrightness(LightLayer.BLOCK, BlockPos.containing(player.getEyePosition())) > threshold;
	}

	static boolean seenByCivilian(ServerPlayer shape) {
		HwConfig cfg = HwConfig.get();
		ServerLevel level = shape.level();
		Vec3 target = FocusPoints.bodyCenter(shape);
		for (ServerPlayer civilian : level.players()) {
			if (!role(civilian).survivor() || civilian.isSpectator()) {
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
	 * Entering and leaving Shape Jump both need darkness and nobody looking at that spot:
	 * 0 allowed, 1 in light, 2 a civilian is looking.
	 */
	/** The Shape overlaps a door block (he can be inside one only during Shape Jump). */
	static boolean insideDoor(ServerPlayer player) {
		var box = player.getBoundingBox().deflate(0.05);
		for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
			var state = player.level().getBlockState(pos);
			if (DoorLocks.isDoor(state)) {
				var shape = state.getShape(player.level(), pos);
				if (!shape.isEmpty() && shape.bounds().move(pos).intersects(box)) {
					return true;
				}
			}
		}
		return false;
	}

	/** No stepping out of Shape Jump right on top of someone: a survivor or townsperson closer than {@link HwConfig#shapeJumpExitMinDistance}. */
	public static boolean tooCloseToVictim(ServerPlayer player) {
		double min = HwConfig.get().shapeJumpExitMinDistance;
		for (ServerPlayer other : player.level().players()) {
			if (other != player && role(other).survivor() && !other.isSpectator() && other.distanceTo(player) < min) {
				return true;
			}
		}
		return !player.level().getEntities(player, player.getBoundingBox().inflate(min), e -> Townsfolk.isNpc(e) && e.distanceTo(player) < min).isEmpty();
	}

	static int exposure(ServerPlayer player) {
		if (inLight(player)) {
			return 1;
		}
		return seenByCivilian(player) ? 2 : 0;
	}

	/**
	 * Out of Shape Jump: 0 ready, 1 light, 2 watched, 3 cooldown, 4 busy.
	 * In Shape Jump: whether leaving is allowed right now (0, 1 light, 2 watched, 5 inside a door, 6 right next to a victim).
	 */
	static int jumpBlockReason(ServerPlayer player, ShapeState state) {
		if (Demogorgon.active()) {
			// The Upside Down: only being busy or the rest after a crossing hold him back.
			return Demogorgon.crossing() ? 0 : state.busy() ? 4 : state.jumpCooldown > 0 ? 3 : 0;
		}
		if (state.shadowTicks > 0) {
			return insideDoor(player) ? 5 : tooCloseToVictim(player) ? 6 : exposure(player);
		}
		if (state.busy()) {
			return 4;
		}
		if (state.jumpCooldown > 0) {
			return 3;
		}
		return exposure(player);
	}

	public static void onShapeJump(ServerPlayer player) {
		ShapeState state = SHAPES.get(player.getUUID());
		if (role(player) != Role.SHAPE || state == null) {
			return;
		}
		if (Demogorgon.active()) {
			// The Demogorgon goes down into the Upside Down in place of Shape Jump.
			Demogorgon.riftKey(player, state);
			return;
		}
		if (Windows.isVaulting(player)) {
			// Halfway through a window: finish slipping through first.
			return;
		}
		int reason = jumpBlockReason(player, state);
		// Pressing again leaves the jump, but never in light or in a civilian's view.
		if (state.shadowTicks > 0) {
			if (reason != 0) {
				player.sendOverlayMessage(Component.translatable("halloween.msg.exit_blocked." + reason).withStyle(ChatFormatting.GRAY));
			} else {
				exitShadow(player, state);
			}
			return;
		}
		if (reason != 0) {
			if (reason != 4) {
				player.sendOverlayMessage(Component.translatable("halloween.msg.jump_blocked." + reason).withStyle(ChatFormatting.GRAY));
			}
			return;
		}
		state.shadowTicks = 1;
		state.dashTicks = 0;
		player.setInvisible(true);
		player.setSilent(true);
		// The smoke as he goes is for his eyes only: to everyone else he is simply gone, without a trace or a sound.
		player.level().sendParticles(player, ParticleTypes.LARGE_SMOKE, false, false, player.getX(), player.getY() + 1, player.getZ(), 12, 0.3, 0.6, 0.3, 0.01);
		dirty = true;
	}

	static void exitShadow(ServerPlayer player, ShapeState state) {
		HwConfig cfg = HwConfig.get();
		state.shadowTicks = 0;
		state.jumpCooldownMax = HwConfig.at(cfg.shapeJumpCooldownByTier, state.tier());
		state.jumpCooldown = state.jumpCooldownMax;
		// Out of Shape Jump he can't speed up at once.
		if (state.dashCooldown < cfg.dashAfterShadowTicks) {
			state.dashCooldown = cfg.dashAfterShadowTicks;
			state.dashCooldownMax = cfg.dashAfterShadowTicks;
		}
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
		if (Demogorgon.active() && role(player) == Role.SHAPE && state != null) {
			// The Demogorgon's Shred in place of the dash.
			Demogorgon.shredKey(player, state);
			return;
		}
		if (role(player) != Role.SHAPE || state == null || state.busy() || state.shadowTicks > 0 || state.dashCooldown > 0 || state.dashTicks > 0) {
			return;
		}
		HwConfig cfg = HwConfig.get();
		state.dashTicks = cfg.dashTicks;
		state.dashCooldownMax = HwConfig.at(cfg.dashCooldownByTier, state.tier());
		state.dashCooldown = state.dashCooldownMax;
	}

	/** The push key: survivors shove the Shape away (he no longer pushes anyone). */
	public static void onPush(ServerPlayer player) {
		if (role(player).survivor()) {
			Fight.survivorPush(player);
		}
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
		for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(cfg.impulseRange), MatchManager::exposed)) {
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

	/**
	 * Vanilla attacks: the Shape attacks through {@link #onShapeAttack}; survivors can only hit the Shape
	 * (with a weapon, filling his stun meter) and never each other.
	 */
	public static InteractionResult onVanillaAttack(Player attacker, Entity target) {
		if (!(attacker instanceof ServerPlayer player)) {
			return InteractionResult.PASS;
		}
		Role role = role(player);
		if (role == Role.SHAPE) {
			return InteractionResult.FAIL;
		}
		if (role == Role.RUNT) {
			// He can't hurt anyone: his blow is a fright.
			Runt.scare(player, target);
			return InteractionResult.FAIL;
		}
		if (role.survivor()) {
			if (target instanceof ServerPlayer other && (role(other) == Role.SHAPE || role(other) == Role.RUNT)) {
				return Fight.onSurvivorAttack(player, target);
			}
			return isVictim(target) || target instanceof Player ? InteractionResult.FAIL : InteractionResult.PASS;
		}
		return InteractionResult.PASS;
	}

	/** Unbroken window glass under the reticle within striking reach, if any. */
	private static BlockPos windowInReach(ServerPlayer player, ShapeState state) {
		Vec3 camera = aimOrigin(player, state);
		Vec3 dir = aimDirection(player, state);
		Vec3 eye = player.getEyePosition();
		Vec3 from = camera.add(dir.scale(Math.max(0, eye.subtract(camera).dot(dir))));
		Vec3 to = from.add(dir.scale(HwConfig.get().attackReach + 0.5));
		var hit = player.level().clip(new net.minecraft.world.level.ClipContext(from, to,
			net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, player));
		if (hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
			return null;
		}
		var glass = player.level().getBlockState(hit.getBlockPos());
		return Windows.isWindow(glass) && !Windows.isBrokenPane(glass) ? hit.getBlockPos() : null;
	}

	/** Door block under the reticle within striking reach, if any. */
	private static BlockPos doorInReach(ServerPlayer player, ShapeState state) {
		Vec3 camera = aimOrigin(player, state);
		Vec3 dir = aimDirection(player, state);
		Vec3 eye = player.getEyePosition();
		Vec3 from = camera.add(dir.scale(Math.max(0, eye.subtract(camera).dot(dir))));
		Vec3 to = from.add(dir.scale(HwConfig.get().attackReach + 0.5));
		var hit = player.level().clip(new net.minecraft.world.level.ClipContext(from, to,
			net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, player));
		if (hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS || !DoorLocks.isDoor(player.level().getBlockState(hit.getBlockPos()))) {
			return null;
		}
		return hit.getBlockPos();
	}

	public static void onShapeAttack(ServerPlayer player, int targetId, boolean heavy) {
		ShapeState state = SHAPES.get(player.getUUID());
		if (role(player) != Role.SHAPE || state == null || state.busy() || state.attackCooldown > 0) {
			return;
		}
		HwConfig cfg = HwConfig.get();
		ServerLevel level = player.level();
		// No attacking from inside Shape Jump: the Shape has to come out first.
		if (state.shadowTicks > 0) {
			return;
		}
		state.dashTicks = 0;
		player.swing(InteractionHand.MAIN_HAND, true);
		state.attackCooldown = heavy ? cfg.heavyCooldownTicks : cfg.lightCooldownTicks;
		Entity entity = targetId >= 0 ? level.getEntity(targetId) : null;
		Vec3 eye = player.getEyePosition();
		if (!(entity instanceof LivingEntity victim) || !exposed(victim)
			|| eye.distanceTo(FocusPoints.bodyCenter(victim)) > cfg.attackReach + 0.6
			|| !Sight.canSee(level, eye, FocusPoints.bodyCenter(victim), player)) {
			state.comboCount = 0;
			state.comboTimer = 0;
			BlockPos glass = windowInReach(player, state);
			if (glass != null) {
				// One blow and the window is in pieces; everyone around hears it.
				Windows.breakColumn(level, glass);
				Bots.alert(Vec3.atCenterOf(glass), level.getServer().getTickCount());
				Townsfolk.hearNoise(level, Vec3.atCenterOf(glass), level.getServer().getTickCount());
				return;
			}
			BlockPos door = doorInReach(player, state);
			if (door == null || !DoorLocks.hit(player, door, heavy)) {
				level.playSound(null, player.getX(), player.getY(), player.getZ(), HwSounds.SWING, SoundSource.HOSTILE, 0.8f, heavy ? 0.7f : 1f);
			}
			return;
		}
		if (Gear.amuletSaves(victim, player)) {
			// The amulet takes the blow.
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
			Voices.play(victim, Voices.Kind.HURT);
			Panic.add(victim, cfg.panicOnHit);
			// Strikes don't throw victims around; only the push does.
			victim.setDeltaMovement(Vec3.ZERO);
			victim.hurtMarked = true;
			bleed(level, victim, heavy ? 14 : 8);
		}
	}

	/** Vanilla right-click on entities: the Shape grabs through {@link #onGrab} along his camera ray instead. */
	public static InteractionResult onUseEntity(Player user, InteractionHand hand, Entity target) {
		if (!(user instanceof ServerPlayer player)) {
			return InteractionResult.PASS;
		}
		if (StreetWeapons.take(player, target)) {
			return InteractionResult.SUCCESS;
		}
		if (role(player) == Role.SHAPE || role(player) == Role.RUNT) {
			return InteractionResult.FAIL;
		}
		if (role(player).survivor() && Townsfolk.isNpc(target)) {
			// Talking happens through the radial menu (client) and Talks (server).
			return InteractionResult.FAIL;
		}
		return InteractionResult.PASS;
	}

	public static void onGrab(ServerPlayer player, int targetId) {
		ShapeState state = SHAPES.get(player.getUUID());
		HwConfig cfg = HwConfig.get();
		if (role(player) != Role.SHAPE || state == null || state.busy() || state.shadowTicks > 0 || state.grabCooldown > 0) {
			return;
		}
		// He lunges for them, whether or not he gets hold.
		GrabSession.anim(player, dev.halloween.net.HwNet.Anim.REACH);
		Entity target = player.level().getEntity(targetId);
		if (!exposed(target) || player.distanceTo(target) > cfg.grabRange
			|| !Sight.canSee(player.level(), player.getEyePosition(), FocusPoints.bodyCenter((LivingEntity) target), player)) {
			return;
		}
		startGrab(player, state, (LivingEntity) target);
	}

	/** The Shape pulls someone out of a locker he threw open: straight into a grab. */
	public static boolean grabOut(ServerPlayer player, LivingEntity victim) {
		ShapeState state = SHAPES.get(player.getUUID());
		if (role(player) != Role.SHAPE || state == null || state.grab != null || state.stunnedTicks > 0 || !isVictim(victim)) {
			return false;
		}
		return startGrab(player, state, victim);
	}

	private static boolean startGrab(ServerPlayer player, ShapeState state, LivingEntity victim) {
		HwConfig cfg = HwConfig.get();
		VictimState vs = victim(victim);
		if (vs.grabbed) {
			return false;
		}
		if (Gear.amuletSaves(victim, player)) {
			// The amulet breaks his hold before it closes.
			state.grabCooldown = HwConfig.at(cfg.grabCooldownByTier, state.tier());
			return false;
		}
		state.dashTicks = 0;
		gainBloodthirst(player, state, cfg.bloodthirstPerGrab);
		score(player, state, policeHalf(victim, cfg.scoreGrab), Component.translatable("halloween.score.grab", victim.getDisplayName(), vs.level()));
		AiStats.add(victim, "grabbed");
		Panic.add(victim, cfg.panicOnGrab);
		if (victim instanceof ServerPlayer vp) {
			AiStats.add(victim, "grabbed.while." + Bots.modeOf(vp));
		}
		state.grab = new GrabSession(player, state, victim, vs);
		return true;
	}

	/** The grab this victim is caught in, if any. */
	public static GrabSession grabHolding(LivingEntity victim) {
		for (ShapeState state : SHAPES.values()) {
			if (state.grab != null && state.grab.victim == victim) {
				return state.grab;
			}
		}
		return null;
	}

	public static void onQtePress(ServerPlayer player) {
		if (SkillChecks.press(player)) {
			return;
		}
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
		Blood.splash(level, victim, count);
	}

	/** Kills a victim on behalf of the Shape, leaves a corpse and pays out Bloodthirst and score. */
	static void kill(ServerPlayer shape, ShapeState state, LivingEntity victim, KillKind kind, boolean bloody) {
		HwConfig cfg = HwConfig.get();
		// The music falls silent for the kill; the victim's last scream.
		Music.killing(shape.level().getServer(), 70);
		Voices.play(victim, Voices.Kind.DEATH);
		ServerLevel level = (ServerLevel) victim.level();
		VictimState vs = VICTIMS.get(victim.getUUID());
		int stalkLevel = vs == null ? 0 : vs.level();
		Component name = victim.getDisplayName();
		Vec3 pos = victim.position();
		float yaw = victim.getYRot();

		AiStats.add(victim, "killed." + kind.name().toLowerCase(Locale.ROOT));
		Townsfolk.died(victim);
		Tutorial.killed(shape);
		Match.noteKill(level.getServer(), name, Police.isOfficer(victim) ? "officer" : kind == KillKind.WINDOW ? "window" : "knife");
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
		score(shape, state, policeHalf(victim, points), lines.toArray(Component[]::new));

		String profile = corpseProfile(victim);
		eliminate(victim);
		spawnCorpse(level, pos, yaw, profile);
		Blood.pool(level, pos);
		// His knife is bloody from now on.
		ItemStack knife = shape.getInventory().getItem(0);
		if (knife.is(Items.IRON_SWORD)) {
			knife.set(DataComponents.ITEM_MODEL, Halloween.id("michael_knife_bloody"));
		}
		Corpse corpse = new Corpse(level, pos, name, cfg.corpseAdmireTicks);
		corpse.police = Police.isOfficer(victim);
		CORPSES.add(corpse);
		// Somebody has to find the body before the police can be called about it.
		Police.addBody(pos, name);
		// Survivors only learn about a death when they find the body (or hear the scream).
		for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
			if (role(player) == Role.SHAPE) {
				Notices.send(player, Component.translatable("halloween.msg.killed", name).withStyle(ChatFormatting.DARK_RED));
			}
		}
	}

	/** Killed by a stray bullet: a body is left like after any death, and somebody has to find it. */
	public static void shotDead(LivingEntity victim) {
		ServerLevel level = (ServerLevel) victim.level();
		Vec3 pos = victim.position();
		float yaw = victim.getYRot();
		Component name = victim.getDisplayName();
		AiStats.add(victim, "killed.shot");
		Townsfolk.died(victim);
		Match.chronicle(level.getServer(), Component.translatable("halloween.chronicle.shot", name).withStyle(ChatFormatting.RED));
		bleed(level, victim, 40);
		String profile = corpseProfile(victim);
		eliminate(victim);
		spawnCorpse(level, pos, yaw, profile);
		Blood.pool(level, pos);
		Police.addBody(pos, name);
	}

	/**
	 * The look the body keeps (SNBT of a mannequin profile): a townsperson's or officer's own, a survivor's or
	 * deputy's match skin. Null: the default look.
	 */
	static String corpseProfile(LivingEntity victim) {
		if (victim instanceof net.minecraft.world.entity.decoration.Mannequin body) {
			return net.minecraft.world.item.component.ResolvableProfile.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, body.getProfile())
				.result().map(Object::toString).orElse(null);
		}
		if (victim instanceof ServerPlayer player) {
			String key = Skins.skinOf(player);
			if (key == null) {
				return null;
			}
			int index = Skins.SURVIVORS.indexOf(key);
			String texture = key.equals("police") ? "halloween:entity/police" : "halloween:entity/skin/" + key;
			return "{texture:\"" + texture + "\",model:\"" + (index >= 0 && index < Skins.SLIM_COUNT ? "slim" : "wide") + "\"}";
		}
		return null;
	}

	private static void spawnCorpse(ServerLevel level, Vec3 pos, float yaw, String profile) {
		CommandSourceStack console = level.getServer().createCommandSourceStack().withLevel(level).withSuppressedOutput();
		String command = String.format(Locale.ROOT,
			"summon minecraft:mannequin %.2f %.2f %.2f {Tags:[\"%s\",\"%s\"],pose:\"sleeping\",immovable:1b,Invulnerable:1b,hide_description:1b,Rotation:[%.1ff,0f]%s}",
			pos.x, pos.y, pos.z, CORPSE_TAG, Leftovers.stamp(), yaw, profile == null ? "" : ",profile:" + profile);
		level.getServer().getCommands().performPrefixedCommand(console, command);
	}

	/** Takes a victim out of the match: civilian players become spectators, mannequins die. */
	public static void eliminate(LivingEntity victim) {
		releaseGrabsOf(victim);
		VICTIMS.remove(victim.getUUID());
		if (victim instanceof ServerPlayer player) {
			player.setHealth(player.getMaxHealth());
			Match.onSurvivorDown(player);
			player.setGameMode(GameType.SPECTATOR);
			player.connection.send(new ClientboundSetTitleTextPacket(Component.translatable("halloween.msg.you_died").withStyle(ChatFormatting.DARK_RED)));
			ServerPlayNetworking.send(player, HwNet.Qte.INACTIVE);
		} else {
			// A fallen officer leaves the revolver and rounds lying there.
			Police.dropGear(victim);
			victim.discard();
		}
		dirty = true;
	}

	/**
	 * Damage filter. The Shape can't die (stuns come later) but loses Bloodthirst from any damage
	 * or a hard fall; civilians can't hurt each other.
	 */
	public static boolean allowDamage(LivingEntity entity, DamageSource source) {
		if (entity instanceof ServerPlayer runt && role(runt) == Role.RUNT) {
			return false;
		}
		if (entity instanceof ServerPlayer player && role(player) == Role.SHAPE) {
			ShapeState state = SHAPES.get(player.getUUID());
			Fight.onShapeHurt(player, source);
			if (state != null) {
				HwConfig cfg = HwConfig.get();
				state.addBloodthirst(-(source.is(DamageTypeTags.IS_FALL) ? cfg.bloodthirstFallLoss : cfg.bloodthirstDamageLoss));
			}
			return false;
		}
		return !(source.getEntity() instanceof ServerPlayer attacker && role(attacker).survivor() && isVictim(entity));
	}

	/** Civilians who would die from anything else (falls etc.) are eliminated instead. */
	public static boolean allowDeath(LivingEntity entity) {
		if (entity instanceof ServerPlayer player) {
			Role role = role(player);
			if (role == Role.SHAPE || role == Role.RUNT) {
				player.setHealth(player.getMaxHealth());
				return false;
			}
			if (role.survivor()) {
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
		// The beams flash up now and then (from the moment he slips into Shape Jump), not all the time.
		boolean showBeams = state.shadowTicks > 0 && state.level() >= cfg.beamsMinLevel
			&& (state.shadowTicks - 1) % Math.max(1, cfg.beamsPeriodTicks) < cfg.beamsShowTicks;
		int alive = 0;
		int townsfolk = 0;
		boolean anyUnderThree = false;
		for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(256), MatchManager::isVictim)) {
			if (Townsfolk.isNpc(target)) {
				townsfolk++;
			} else if (!Police.isOfficer(target)) {
				alive++;
			}
			if (Lockers.isInside(target)) {
				// Counted, but nothing gives away where they hide.
				continue;
			}
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
		if (townsfolk > 0) {
			objectives.add(Component.translatable("halloween.objective.townsfolk", townsfolk));
		}
		ServerPlayNetworking.send(player, new HwNet.ShapeHud(
			state.bloodthirst, state.score, state.fatigue, state.senseActive(), state.focusActive(),
			state.jumpCooldown, state.jumpCooldownMax, state.shadowTicks, jumpBlockReason(player, state),
			state.dashCooldown, state.dashCooldownMax, state.dashTicks,
			state.impulseCooldown, state.impulseCooldownMax,
			state.pushCooldown, state.grabCooldown, state.attackCooldown,
			new ArrayList<>(infos.values()), beams, objectives,
			state.stun, state.stunnedTicks, state.blindTicks > 0
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
		HwNet.WorldSync sync = new HwNet.WorldSync(roles, shapeId, shadow, Demogorgon.active() ? 1 : 0);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			ServerPlayNetworking.send(player, sync);
		}
	}
}
