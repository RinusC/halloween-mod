package dev.halloween.game;

import dev.halloween.HwConfig;
import dev.halloween.HwSounds;
import dev.halloween.net.HwNet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.commands.SummonCommand;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.phys.Vec3;

/**
 * Townsfolk: neighbours at home in the houses. They are victims like anyone (the Shape can stalk, grab
 * and kill them for Bloodthirst and score). When one sees the Shape it screams, which shows survivors
 * where he was, then runs and hides, or goes for the nearest working phone to call the police.
 * A civilian can take one along (use: "follow me") to an exit, which rescues it for points, or tell it
 * to hide (sneak + use), which pays out at the end of the match if it is still alive.
 */
public final class Townsfolk {
	public static final String TAG = "hw_npc";
	private static final String[] SKINS = {"alex", "ari", "efe", "kai", "makena", "noor", "steve", "sunny", "zuri"};

	private enum State {
		HOME, PANIC, HIDE, CALL, FOLLOW, HIDDEN
	}

	private static final class Npc {
		final Mannequin body;
		final Walker walker;
		final String name;
		BlockPos home;
		State state = State.HOME;
		int nextStroll;
		int screamReadyAt;
		Vec3 threat;
		int threatTick = -100000;
		int calmSince;
		int hideUntil;
		boolean pathSet;
		int repathAt;
		BlockPos phone;
		int callTicks;
		UUID leader;
		UUID hider;
		boolean wasGrabbed;

		Npc(Mannequin body, String name) {
			this.body = body;
			this.walker = new Walker(body);
			this.name = name;
			this.home = body.blockPosition();
		}
	}

	private static final Map<UUID, Npc> NPCS = new LinkedHashMap<>();
	/** One click reaches the server as two interactions; only the first counts. */
	private static final Map<UUID, Integer> LAST_COMMAND = new java.util.HashMap<>();

	private Townsfolk() {
	}

	public static boolean isNpc(Entity entity) {
		return entity != null && entity.entityTags().contains(TAG);
	}

	public static List<Integer> ids() {
		List<Integer> ids = new ArrayList<>();
		for (Npc npc : NPCS.values()) {
			if (!npc.body.isRemoved()) {
				ids.add(npc.body.getId());
			}
		}
		return ids;
	}

	/** Screams so far (tests, debugging). */
	public static int screams;

	/** What a townsperson is doing (tests, debugging): home, panic, hide, call, follow, hidden; or none. */
	public static String stateOf(Entity entity) {
		Npc npc = entity == null ? null : NPCS.get(entity.getUUID());
		return npc == null ? "none" : npc.state.name().toLowerCase(Locale.ROOT);
	}

	public static int count() {
		return ids().size();
	}

	// ---------------------------------------------------------------- spawn / clear

	/** Puts townsfolk on the map's npc points (at most {@link HwConfig#npcMax}). */
	public static void spawnAll(ServerLevel level, RandomSource random) {
		clear(level.getServer());
		HwConfig cfg = HwConfig.get();
		List<BlockPos> points = new ArrayList<>(MapPoints.get(level).list(MapPoints.Type.NPC));
		Collections.shuffle(points, new java.util.Random(random.nextLong()));
		List<String> names = new ArrayList<>(List.of(cfg.npcNames));
		Collections.shuffle(names, new java.util.Random(random.nextLong()));
		CommandSourceStack console = level.getServer().createCommandSourceStack().withLevel(level).withSuppressedOutput();
		for (int i = 0; i < Math.min(points.size(), cfg.npcMax); i++) {
			BlockPos p = points.get(i);
			String name = names.isEmpty() ? "Neighbour" : names.get(i % names.size());
			String skin = SKINS[random.nextInt(SKINS.length)];
			boolean slim = random.nextBoolean();
			String snbt = String.format(Locale.ROOT,
				"{Tags:[\"%s\",\"%s\"],hide_description:1b,CustomName:\"%s\",profile:{texture:\"minecraft:entity/player/%s/%s\",model:\"%s\"},Rotation:[%d.0f,0f]}",
				MatchManager.VICTIM_TAG, TAG, name.replace("\"", ""), slim ? "slim" : "wide", skin, slim ? "slim" : "wide", random.nextInt(360));
			Entity entity;
			try {
				// Not through /summon: inside /hw start that would only run after the command is over.
				entity = SummonCommand.createEntity(console, EntityTypes.MANNEQUIN.builtInRegistryHolder(), Vec3.atBottomCenterOf(p),
					TagParser.parseCompoundFully(snbt), false);
			} catch (CommandSyntaxException e) {
				dev.halloween.Halloween.LOGGER.error("Could not spawn townsperson {}", name, e);
				continue;
			}
			if (entity instanceof Mannequin body) {
				body.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(cfg.npcWalkSpeed);
				Npc npc = new Npc(body, name);
				npc.walker.closeDoors = false;
				npc.nextStroll = level.getServer().getTickCount() + random.nextInt(100);
				NPCS.put(body.getUUID(), npc);
			}
		}
	}

	public static void clear(MinecraftServer server) {
		for (Npc npc : NPCS.values()) {
			npc.body.discard();
		}
		NPCS.clear();
		// Leftovers from a match that never ended cleanly.
		for (ServerLevel level : server.getAllLevels()) {
			for (Mannequin body : level.getEntities(EntityTypes.MANNEQUIN, Townsfolk::isNpc)) {
				body.discard();
			}
		}
	}

	// ---------------------------------------------------------------- commands from civilians

	/** A civilian used a townsperson: follow me, or (sneaking) hide here. */
	public static InteractionResult command(ServerPlayer player, Entity target) {
		Npc npc = NPCS.get(target.getUUID());
		if (npc == null || !Match.running() || !MatchManager.role(player).survivor() || player.isSpectator()) {
			return InteractionResult.PASS;
		}
		if (MatchManager.isHeld(npc.body)) {
			return InteractionResult.FAIL;
		}
		int now = player.level().getServer().getTickCount();
		Integer last = LAST_COMMAND.put(player.getUUID(), now);
		if (last != null && now - last < 6) {
			return InteractionResult.SUCCESS;
		}
		npc.walker.stop();
		npc.pathSet = false;
		npc.body.setPose(Pose.STANDING);
		if (player.isShiftKeyDown()) {
			npc.state = State.HIDDEN;
			npc.hider = player.getUUID();
			npc.leader = null;
			player.sendOverlayMessage(Component.translatable("halloween.npc.hide", npc.name).withStyle(ChatFormatting.GRAY));
		} else if (npc.state == State.FOLLOW && player.getUUID().equals(npc.leader)) {
			npc.state = State.HOME;
			npc.home = npc.body.blockPosition();
			npc.leader = null;
			player.sendOverlayMessage(Component.translatable("halloween.npc.stay", npc.name).withStyle(ChatFormatting.GRAY));
		} else {
			npc.state = State.FOLLOW;
			npc.leader = player.getUUID();
			npc.hider = null;
			npc.repathAt = now;
			player.sendOverlayMessage(Component.translatable("halloween.npc.follow", npc.name).withStyle(ChatFormatting.GRAY));
		}
		return InteractionResult.SUCCESS;
	}

	/** End of the match: townsfolk told to hide that are still alive pay out to whoever hid them. */
	public static void payHidden(MinecraftServer server) {
		HwConfig cfg = HwConfig.get();
		for (Npc npc : NPCS.values()) {
			if (npc.state == State.HIDDEN && npc.hider != null && !npc.body.isRemoved() && npc.body.isAlive()) {
				ServerPlayer hider = server.getPlayerList().getPlayer(npc.hider);
				if (hider != null) {
					Match.score(hider, cfg.scoreHiddenNpc, Component.translatable("halloween.score.npc_hidden", npc.name));
				}
			}
		}
	}

	// ---------------------------------------------------------------- tick

	public static void tick(MinecraftServer server) {
		if (NPCS.isEmpty()) {
			return;
		}
		NPCS.values().removeIf(npc -> npc.body.isRemoved() || !npc.body.isAlive());
		if (!Match.running()) {
			for (Npc npc : NPCS.values()) {
				npc.walker.halt();
			}
			return;
		}
		int now = server.getTickCount();
		for (Npc npc : new ArrayList<>(NPCS.values())) {
			tickNpc(npc, now);
		}
	}

	private static void tickNpc(Npc npc, int now) {
		HwConfig cfg = HwConfig.get();
		Mannequin body = npc.body;
		ServerLevel level = (ServerLevel) body.level();
		boolean held = MatchManager.isHeld(body);
		if (held) {
			if (!npc.wasGrabbed) {
				npc.wasGrabbed = true;
				scream(npc, now, null);
			}
			npc.walker.halt();
			return;
		}
		npc.wasGrabbed = false;

		if (now % 4 == 0) {
			ServerPlayer seen = Senses.seenShape(body, cfg.npcSightRange, cfg.npcDarkSightRange, cfg.npcFovDeg);
			if (seen != null) {
				npc.threat = seen.position();
				npc.threatTick = now;
				double dist = body.distanceTo(seen);
				boolean quiet = npc.state == State.HIDDEN && dist > 5;
				if (!quiet && now >= npc.screamReadyAt) {
					scream(npc, now, seen);
				}
				switch (npc.state) {
					case HOME -> {
						BlockPos phone = dist > 7 && level.getRandom().nextFloat() < cfg.npcCallChance ? nearestPhone(level, body) : null;
						if (phone != null) {
							startCall(npc, phone);
						} else {
							panic(npc, now);
						}
					}
					case CALL -> {
						if (dist < 8) {
							panic(npc, now);
						}
					}
					case HIDE -> {
						if (dist < 10) {
							panic(npc, now);
						}
					}
					case HIDDEN -> {
						if (dist < 5) {
							panic(npc, now);
						}
					}
					default -> {
					}
				}
			}
		}

		switch (npc.state) {
			case HOME -> tickHome(npc, now, level);
			case PANIC -> tickPanic(npc, now, level);
			case HIDE, HIDDEN -> tickHide(npc, now, level);
			case CALL -> tickCall(npc, now, level);
			case FOLLOW -> tickFollow(npc, now, level);
		}
	}

	private static void setSpeed(Npc npc, double speed) {
		npc.body.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(speed);
	}

	private static void scream(Npc npc, int now, ServerPlayer shape) {
		HwConfig cfg = HwConfig.get();
		Mannequin body = npc.body;
		ServerLevel level = (ServerLevel) body.level();
		npc.screamReadyAt = now + cfg.npcScreamCooldownTicks;
		screams++;
		level.playSound(null, body.getX(), body.getEyeY(), body.getZ(), HwSounds.NPC_SCREAM, SoundSource.NEUTRAL, 3f, 0.9f + level.getRandom().nextFloat() * 0.3f);
		Vec3 where = shape != null ? shape.position() : body.position();
		HwNet.Alert alert = new HwNet.Alert(where, cfg.alertTicks);
		for (ServerPlayer player : level.players()) {
			if (MatchManager.role(player).survivor() && !player.isSpectator() && !Bots.isBot(player)) {
				ServerPlayNetworking.send(player, alert);
			}
		}
		Bots.alert(where, now);
		if (shape != null) {
			shape.sendOverlayMessage(Component.translatable("halloween.msg.npc_saw_you", npc.name).withStyle(ChatFormatting.GOLD));
		}
	}

	private static void panic(Npc npc, int now) {
		if (npc.state == State.FOLLOW) {
			return;
		}
		npc.state = State.PANIC;
		npc.calmSince = now;
		npc.repathAt = now;
		npc.pathSet = false;
		npc.body.setPose(Pose.STANDING);
		setSpeed(npc, HwConfig.get().npcRunSpeed);
	}

	private static void tickHome(Npc npc, int now, ServerLevel level) {
		Walker.Status status = npc.walker.tick();
		if (status == Walker.Status.MOVING) {
			return;
		}
		if (now < npc.nextStroll) {
			return;
		}
		RandomSource random = level.getRandom();
		npc.nextStroll = now + 60 + random.nextInt(140);
		setSpeed(npc, HwConfig.get().npcWalkSpeed);
		boolean indoors = !level.canSeeSky(npc.home);
		for (int tries = 0; tries < 8; tries++) {
			BlockPos spot = npc.home.offset(random.nextInt(9) - 4, 0, random.nextInt(9) - 4);
			spot = Nav.snap(level, spot);
			if (Nav.standable(level, spot) && level.canSeeSky(spot) != indoors) {
				npc.walker.speed = 0.6f;
				npc.walker.follow(Nav.to(level, npc.body.blockPosition(), spot, 0.8, Nav.Options.walker()));
				return;
			}
		}
	}

	private static void tickPanic(Npc npc, int now, ServerLevel level) {
		HwConfig cfg = HwConfig.get();
		if (now - npc.threatTick < 40) {
			npc.calmSince = now;
		} else if (now - npc.calmSince > cfg.npcCalmTicks) {
			npc.state = State.HIDE;
			npc.pathSet = false;
			npc.hideUntil = now + cfg.npcHideMinTicks + level.getRandom().nextInt(Math.max(1, cfg.npcHideMaxTicks - cfg.npcHideMinTicks));
			return;
		}
		if (now >= npc.repathAt || npc.walker.idle()) {
			npc.repathAt = now + 30;
			Vec3 from = npc.threat;
			Nav.Options opt = Nav.Options.walker();
			opt.maxNodes = 2000;
			npc.walker.speed = 1f;
			npc.walker.follow(Nav.search(level, npc.body.blockPosition(), 24, opt, (p, cost) -> BotBrain.fleeScore(level, p, cost, from, npc.body)));
		}
		if (npc.walker.tick() == Walker.Status.STUCK) {
			npc.repathAt = now;
		}
	}

	private static void tickHide(Npc npc, int now, ServerLevel level) {
		if (npc.state == State.HIDE && now > npc.hideUntil) {
			npc.state = State.HOME;
			npc.body.setPose(Pose.STANDING);
			npc.walker.speed = 0.6f;
			setSpeed(npc, HwConfig.get().npcWalkSpeed);
			npc.walker.follow(Nav.to(level, npc.body.blockPosition(), npc.home, 1.0, Nav.Options.walker()));
			return;
		}
		if (!npc.pathSet) {
			npc.pathSet = true;
			Vec3 from = npc.state == State.HIDE ? npc.threat : null;
			Nav.Options opt = Nav.Options.walker();
			opt.maxNodes = 2000;
			npc.walker.speed = 1f;
			npc.walker.follow(Nav.search(level, npc.body.blockPosition(), npc.state == State.HIDE ? 18 : 12, opt,
				(p, cost) -> BotBrain.hideScore(level, p, cost, from, npc.body)));
		}
		Walker.Status status = npc.walker.tick();
		if (status != Walker.Status.MOVING) {
			npc.walker.halt();
			npc.body.setPose(Pose.CROUCHING);
		}
	}

	private static BlockPos nearestPhone(ServerLevel level, LivingEntity body) {
		if (!Exits.policeCallable()) {
			return null;
		}
		BlockPos best = null;
		double bestDist = HwConfig.get().npcPhoneRange;
		for (BlockPos phone : MapPoints.get(level).list(MapPoints.Type.PHONE)) {
			double d = body.position().distanceTo(Vec3.atCenterOf(phone));
			if (d < bestDist && Exits.phoneWorks(phone) && Exits.isPhone(level, phone)) {
				bestDist = d;
				best = phone;
			}
		}
		return best;
	}

	private static void startCall(Npc npc, BlockPos phone) {
		npc.state = State.CALL;
		npc.phone = phone;
		npc.callTicks = 0;
		npc.pathSet = false;
		npc.body.setPose(Pose.STANDING);
		setSpeed(npc, HwConfig.get().npcRunSpeed);
	}

	private static void tickCall(Npc npc, int now, ServerLevel level) {
		HwConfig cfg = HwConfig.get();
		if (!Exits.policeCallable() || !Exits.phoneWorks(npc.phone)) {
			npc.state = State.HIDE;
			npc.pathSet = false;
			npc.hideUntil = now + cfg.npcHideMinTicks;
			return;
		}
		if (!npc.pathSet) {
			npc.pathSet = true;
			npc.walker.speed = 1f;
			npc.walker.follow(Nav.to(level, npc.body.blockPosition(), npc.phone, 1.8, Nav.Options.walker()));
		}
		Walker.Status status = npc.walker.tick();
		if (status == Walker.Status.MOVING) {
			return;
		}
		if (npc.body.position().distanceTo(Vec3.atCenterOf(npc.phone)) > 3.2) {
			// Couldn't get there.
			panic(npc, now);
			return;
		}
		npc.walker.halt();
		npc.walker.face(Vec3.atCenterOf(npc.phone));
		if (npc.callTicks % 40 == 0) {
			level.playSound(null, npc.phone, net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.BLOCKS, 0.6f, 1.6f);
		}
		if (++npc.callTicks >= cfg.callPoliceTicks) {
			Exits.callPolice(level, Component.translatable("halloween.match.npc_called_police", npc.name));
			npc.state = State.HIDE;
			npc.pathSet = false;
			npc.hideUntil = now + cfg.npcHideMinTicks;
		}
	}

	private static void tickFollow(Npc npc, int now, ServerLevel level) {
		HwConfig cfg = HwConfig.get();
		ServerPlayer leader = npc.leader == null ? null : level.getServer().getPlayerList().getPlayer(npc.leader);
		if (leader == null || leader.isSpectator() || !MatchManager.role(leader).survivor() || leader.distanceTo(npc.body) > cfg.npcFollowLoseDistance) {
			npc.state = State.HOME;
			npc.home = npc.body.blockPosition();
			npc.leader = null;
			npc.walker.stop();
			return;
		}
		if (now % 10 == 0) {
			for (HwNet.ExitInfo exit : Exits.infos()) {
				if (npc.body.position().distanceTo(Vec3.atCenterOf(exit.pos())) <= cfg.npcRescueRadius) {
					rescue(npc, leader, level);
					return;
				}
			}
		}
		double dist = leader.distanceTo(npc.body);
		setSpeed(npc, dist > 5 ? cfg.npcRunSpeed : cfg.npcWalkSpeed + 0.02);
		if (dist <= 2.2) {
			npc.walker.stop();
			npc.walker.face(leader.getEyePosition());
			return;
		}
		if (now >= npc.repathAt || npc.walker.idle()) {
			npc.repathAt = now + 10;
			npc.walker.speed = 1f;
			npc.walker.follow(Nav.to(level, npc.body.blockPosition(), leader.blockPosition(), 1.8, Nav.Options.walker()));
		}
		npc.walker.tick();
	}

	private static void rescue(Npc npc, ServerPlayer leader, ServerLevel level) {
		HwConfig cfg = HwConfig.get();
		Mannequin body = npc.body;
		level.sendParticles(ParticleTypes.HAPPY_VILLAGER, body.getX(), body.getY() + 1, body.getZ(), 15, 0.4, 0.6, 0.4, 0);
		Match.score(leader, cfg.scoreRescueNpc, Component.translatable("halloween.score.npc_rescued", npc.name));
		Match.announce(level.getServer(), Component.translatable("halloween.match.npc_rescued", leader.getDisplayName(), npc.name).withStyle(ChatFormatting.GREEN));
		NPCS.remove(body.getUUID());
		MatchManager.eliminate(body);
	}
}
