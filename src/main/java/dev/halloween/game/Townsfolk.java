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
	/** The townsfolk's own looks (textures/entity/townsfolk): eight women, eight men of 1978. */
	private static final int SKINS_EACH = 8;

	private enum State {
		HOME, PANIC, HIDE, CALL, FOLLOW, HIDDEN, LOCKING, TALK
	}

	private static final class Npc {
		final Mannequin body;
		final Walker walker;
		/** Standing about or hiding, it looks around. */
		final LookAround look = new LookAround();
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
		BlockPos locker;
		boolean noLocker;
		BlockPos lockDoor;
		int lockTicks;
		/** Where it stood when it last saw the Shape. */
		Vec3 seenFrom;
		State beforeTalk;
		UUID talkTo;
		/** Who asked it to call the police (gets the credit). */
		UUID callFor;

		Npc(Mannequin body, String name) {
			this.body = body;
			this.walker = new Walker(body);
			this.name = name;
			this.home = body.blockPosition();
		}
	}

	private static final Map<UUID, Npc> NPCS = new LinkedHashMap<>();

	private Townsfolk() {
	}

	/** This match's townsfolk: how many were put out, how many got away. */
	private static int spawnedCount;
	private static int rescuedCount;

	public static int spawned() {
		return spawnedCount;
	}

	public static int rescued() {
		return rescuedCount;
	}

	/** Where the townsfolk are now (for the opening scene). */
	public static List<Mannequin> bodies() {
		List<Mannequin> out = new ArrayList<>();
		for (Npc npc : NPCS.values()) {
			if (!npc.body.isRemoved()) {
				out.add(npc.body);
			}
		}
		return out;
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

	/** Could this townsperson scream right now (not still hoarse from the last one)? Tests, debugging. */
	public static boolean canScream(Entity entity) {
		Npc npc = entity == null ? null : NPCS.get(entity.getUUID());
		return npc != null && entity.level().getServer().getTickCount() >= npc.screamReadyAt;
	}

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
			// A woman's look for Mrs., Miss and Ms., a man's for the rest (their voices go the same way).
			boolean slim = name.startsWith("Mrs") || name.startsWith("Miss") || name.startsWith("Ms");
			String skin = (slim ? "w" : "m") + (1 + random.nextInt(SKINS_EACH));
			String snbt = String.format(Locale.ROOT,
				"{Tags:[\"%s\",\"%s\"],hide_description:1b,CustomName:\"%s\",profile:{texture:\"halloween:entity/townsfolk/%s\",model:\"%s\"},Rotation:[%d.0f,0f]}",
				MatchManager.VICTIM_TAG, TAG, name.replace("\"", ""), skin, slim ? "slim" : "wide", random.nextInt(360));
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
				MatchManager.noJumping(body, true);
				Npc npc = new Npc(body, name);
				npc.walker.closeDoors = false;
				npc.nextStroll = level.getServer().getTickCount() + random.nextInt(100);
				NPCS.put(body.getUUID(), npc);
				spawnedCount++;
			}
		}
	}

	public static void clear(MinecraftServer server) {
		spawnedCount = 0;
		rescuedCount = 0;
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

	public static final int FOLLOW = 0;
	public static final int HIDE = 1;
	public static final int CALL = 2;

	/** Can {@code player} start talking to this townsperson now? */
	public static boolean canTalk(ServerPlayer player, Entity target) {
		Npc npc = target == null ? null : NPCS.get(target.getUUID());
		return npc != null && Match.running() && MatchManager.role(player).survivor() && !player.isSpectator()
			&& !MatchManager.isHeld(npc.body) && !Lockers.isInside(npc.body) && npc.state != State.PANIC && npc.state != State.TALK
			&& npc.body.distanceTo(player) <= HwConfig.get().talkRange;
	}

	/** Is following {@code player} already (then "follow me" just means "stay here", said at once). */
	public static boolean followsPlayer(Entity target, ServerPlayer player) {
		return follows(target, player);
	}

	/** The townsperson stops and listens while someone talks it round. */
	public static void beginTalk(ServerPlayer player, Entity target) {
		Npc npc = NPCS.get(target.getUUID());
		if (npc == null) {
			return;
		}
		npc.beforeTalk = npc.state;
		npc.state = State.TALK;
		npc.talkTo = player.getUUID();
		npc.walker.stop();
		npc.body.setPose(Pose.STANDING);
	}

	/** Still listening to {@code player}? (It stops if it panics.) */
	public static boolean listensTo(Entity target, ServerPlayer player) {
		Npc npc = target == null ? null : NPCS.get(target.getUUID());
		return npc != null && !npc.body.isRemoved() && npc.state == State.TALK && player.getUUID().equals(npc.talkTo);
	}

	/** The talk broke off: back to whatever it was doing. */
	public static void endTalk(Entity target) {
		Npc npc = target == null ? null : NPCS.get(target.getUUID());
		if (npc != null && npc.state == State.TALK) {
			npc.state = npc.beforeTalk == null ? State.HOME : npc.beforeTalk;
			npc.pathSet = false;
		}
	}

	/** The talk worked: follow me, hide here, or go call the police. */
	public static void apply(ServerPlayer player, Entity target, int option) {
		Npc npc = target == null ? null : NPCS.get(target.getUUID());
		if (npc == null) {
			return;
		}
		int now = player.level().getServer().getTickCount();
		ServerLevel level = player.level();
		npc.walker.stop();
		npc.pathSet = false;
		npc.body.setPose(Pose.STANDING);
		if (option == FOLLOW && npc.beforeTalk == State.FOLLOW && player.getUUID().equals(npc.leader)) {
			npc.state = State.HOME;
			npc.home = npc.body.blockPosition();
			npc.leader = null;
			player.sendOverlayMessage(Component.translatable("halloween.npc.stay", npc.name).withStyle(ChatFormatting.GRAY));
		} else if (option == HIDE) {
			npc.state = State.HIDDEN;
			npc.hider = player.getUUID();
			npc.leader = null;
			player.sendOverlayMessage(Component.translatable("halloween.npc.hide", npc.name).withStyle(ChatFormatting.GRAY));
		} else if (option == CALL) {
			BlockPos phone = nearestPhone(level, npc.body);
			if (phone == null) {
				npc.state = npc.beforeTalk == null || npc.beforeTalk == State.TALK ? State.HOME : npc.beforeTalk;
				player.sendOverlayMessage(Component.translatable("halloween.npc.no_phone", npc.name).withStyle(ChatFormatting.GRAY));
			} else {
				startCall(npc, phone);
				npc.callFor = player.getUUID();
				player.sendOverlayMessage(Component.translatable("halloween.npc.call", npc.name).withStyle(ChatFormatting.GRAY));
			}
		} else {
			npc.state = State.FOLLOW;
			npc.leader = player.getUUID();
			npc.hider = null;
			npc.repathAt = now;
			player.sendOverlayMessage(Component.translatable("halloween.npc.follow", npc.name).withStyle(ChatFormatting.GRAY));
		}
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
				npc.seenFrom = body.position();
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
					case TALK -> panic(npc, now);
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
			case LOCKING -> tickLocking(npc, now, level);
			case TALK -> tickTalk(npc, level);
		}
	}

	private static void tickTalk(Npc npc, ServerLevel level) {
		npc.walker.halt();
		ServerPlayer talker = npc.talkTo == null ? null : level.getServer().getPlayerList().getPlayer(npc.talkTo);
		if (talker != null) {
			npc.walker.face(talker.getEyePosition());
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
		AiStats.add(body, "npc.scream");
		Voices.play(body, Voices.Kind.SCREAM);
		Vec3 where = shape != null ? shape.position() : body.position();
		// The neighbours hear it too.
		hearNoise(level, where, now, npc);
		HwNet.Alert alert = new HwNet.Alert(where, cfg.alertTicks);
		for (ServerPlayer player : level.players()) {
			if (MatchManager.role(player).survivor() && !player.isSpectator() && !Bots.isBot(player)) {
				ServerPlayNetworking.send(player, alert);
			}
		}
		Bots.alert(where, now);
	}

	public static void hearNoise(ServerLevel level, Vec3 pos, int now) {
		hearNoise(level, pos, now, null);
	}

	/**
	 * A scream or a door being battered nearby: townsfolk at home that hear it don't wait to see him. They
	 * hide (in a locker if one is close), or, if a working phone is nearer to them than to the noise, call
	 * the police.
	 */
	private static void hearNoise(ServerLevel level, Vec3 pos, int now, Npc source) {
		HwConfig cfg = HwConfig.get();
		for (Npc npc : NPCS.values()) {
			if (npc == source || npc.state != State.HOME || npc.body.position().distanceTo(pos) > cfg.npcHearRange) {
				continue;
			}
			AiStats.add(npc.body, "npc.heard");
			npc.threat = pos;
			npc.threatTick = now;
			BlockPos phone = level.getRandom().nextFloat() < cfg.npcCallChance ? nearestPhone(level, npc.body) : null;
			if (phone != null && Vec3.atCenterOf(phone).distanceTo(pos) > Vec3.atCenterOf(phone).distanceTo(npc.body.position()) + 4) {
				startCall(npc, phone);
			} else {
				npc.state = State.HIDE;
				npc.pathSet = false;
				npc.hideUntil = now + cfg.npcHideMinTicks + level.getRandom().nextInt(Math.max(1, cfg.npcHideMaxTicks - cfg.npcHideMinTicks));
				setSpeed(npc, cfg.npcRunSpeed);
			}
		}
	}

	/**
	 * A townsperson came upon a body: it screams (survivors see where), then goes to call the police if a
	 * working phone is in reach, or else hides.
	 */
	public static void sawBody(Entity entity, Vec3 pos, int now) {
		Npc npc = entity == null ? null : NPCS.get(entity.getUUID());
		if (npc == null) {
			return;
		}
		HwConfig cfg = HwConfig.get();
		ServerLevel level = (ServerLevel) npc.body.level();
		AiStats.add(npc.body, "npc.saw_body");
		if (now >= npc.screamReadyAt) {
			scream(npc, now, null);
		}
		// Someone told to stay hidden screams but stays put.
		if (npc.state != State.HOME && npc.state != State.HIDE) {
			return;
		}
		npc.threat = pos;
		npc.threatTick = now;
		BlockPos phone = nearestPhone(level, npc.body);
		if (phone != null) {
			startCall(npc, phone);
		} else if (npc.state == State.HOME) {
			npc.state = State.HIDE;
			npc.pathSet = false;
			npc.hideUntil = now + cfg.npcHideMinTicks;
			setSpeed(npc, cfg.npcRunSpeed);
		}
	}

	/** Townsfolk minding their own business at home near {@code pos}: ones a civilian could warn. */
	public static List<Mannequin> idleNear(Vec3 pos, double radius) {
		List<Mannequin> list = new ArrayList<>();
		for (Npc npc : NPCS.values()) {
			if (npc.state == State.HOME && !npc.body.isRemoved() && !Lockers.isInside(npc.body) && !MatchManager.isHeld(npc.body)
				&& npc.body.position().distanceTo(pos) <= radius) {
				list.add(npc.body);
			}
		}
		return list;
	}

	/** Townsfolk following {@code player} (for the radial menu's "stay here"). */
	public static List<Integer> followerIds(ServerPlayer player) {
		List<Integer> ids = new ArrayList<>();
		for (Npc npc : NPCS.values()) {
			if (npc.state == State.FOLLOW && player.getUUID().equals(npc.leader)) {
				ids.add(npc.body.getId());
			}
		}
		return ids;
	}

	/** Is this townsperson following {@code leader} right now? */
	public static boolean follows(Entity npc, Entity leader) {
		Npc n = npc == null ? null : NPCS.get(npc.getUUID());
		return n != null && n.state == State.FOLLOW && leader.getUUID().equals(n.leader);
	}

	private static void panic(Npc npc, int now) {
		if (npc.state == State.FOLLOW) {
			return;
		}
		AiStats.add(npc.body, "npc.panic");
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
			npc.look.tick(npc.body, now, level.getRandom(), false, null, null);
			return;
		}
		npc.look.reset();
		RandomSource random = level.getRandom();
		npc.nextStroll = now + 60 + random.nextInt(140);
		setSpeed(npc, HwConfig.get().npcWalkSpeed);
		boolean indoors = !level.canSeeSky(npc.home);
		// Indoors they potter about the whole house: now and then up the stairs, or back down.
		boolean upstairs = npc.body.getY() > npc.home.getY() + 2;
		int floor = upstairs ? UPSTAIRS : 0;
		if (indoors && random.nextInt(3) == 0) {
			floor = UPSTAIRS - floor;
		}
		for (int tries = 0; tries < 8; tries++) {
			BlockPos spot = npc.home.offset(random.nextInt(11) - 5, floor, random.nextInt(11) - 5);
			spot = Nav.snap(level, spot);
			if (!Nav.standable(level, spot) || level.canSeeSky(spot) == indoors) {
				continue;
			}
			List<Nav.Node> path = Nav.to(level, npc.body.blockPosition(), spot, 0.8, Nav.Options.walker().nodes(2500));
			if (!path.isEmpty() && path.getLast().pos().distSqr(spot) <= 2) {
				npc.walker.speed = 0.6f;
				npc.walker.follow(path);
				return;
			}
		}
	}

	/** How far above the ground floor a house's upper floor is walked on. */
	private static final int UPSTAIRS = 5;

	/**
	 * Where a townsperson hides: indoors, away from the windows, out of his sight. A dark yard is no place
	 * to hide for someone who lives here: a room with walls all round is.
	 */
	private static double hideHere(ServerLevel level, BlockPos p, double cost, Vec3 from, LivingEntity body) {
		double score = BotBrain.hideScore(level, p, cost, from, body);
		if (level.canSeeSky(p)) {
			return score - 12;
		}
		boolean enclosed = true;
		for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
			enclosed &= !level.canSeeSky(p.relative(d, 2));
		}
		// Under a porch roof is still outside.
		return score + (enclosed ? 12 : -4);
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
			npc.walker.follow(Nav.search(level, npc.body.blockPosition(), 24, opt, (p, cost) -> BotBrain.fleeScore(level, p, cost, from, npc.body) + refuge(level, p)));
		}
		if (npc.walker.tick() == Walker.Status.STUCK) {
			npc.repathAt = now;
		}
		// Ran in through a door with him some way back: lock it behind (takes as long as for anyone).
		BlockPos door = npc.walker.takePassedDoor();
		if (door != null && npc.threat != null && npc.threat.distanceTo(Vec3.atCenterOf(door)) > HwConfig.get().botLockMinDistance
			&& !DoorLocks.isLocked(level, door) && !DoorLocks.isLockBroken(level, door)) {
			npc.lockDoor = DoorLocks.base(level, door);
			npc.lockTicks = 0;
			npc.state = State.LOCKING;
		}
	}

	private static void tickLocking(Npc npc, int now, ServerLevel level) {
		HwConfig cfg = HwConfig.get();
		npc.walker.halt();
		npc.walker.face(Vec3.atCenterOf(npc.lockDoor.above()));
		npc.body.setPose(Pose.CROUCHING);
		boolean close = npc.threat != null && now - npc.threatTick < 20 && npc.body.position().distanceTo(npc.threat) < 3;
		if (close || DoorLocks.isLockBroken(level, npc.lockDoor) || npc.body.position().distanceTo(Vec3.atCenterOf(npc.lockDoor)) > 3) {
			npc.body.setPose(Pose.STANDING);
			panic(npc, now);
			return;
		}
		if (++npc.lockTicks >= cfg.lockTicks) {
			DoorLocks.forceLock(level, npc.lockDoor);
			AiStats.add(npc.body, "npc.locked_door");
			npc.body.setPose(Pose.STANDING);
			npc.state = State.HIDE;
			npc.pathSet = false;
			npc.hideUntil = now + cfg.npcHideMinTicks + level.getRandom().nextInt(Math.max(1, cfg.npcHideMaxTicks - cfg.npcHideMinTicks));
		}
	}

	/** Running townsfolk head for light (he can't Shape Jump there) and for other people. */
	private static double refuge(ServerLevel level, BlockPos p) {
		double score = level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, p) >= 8 ? 3 : 0;
		for (ServerPlayer player : level.players()) {
			if (MatchManager.role(player).survivor() && !player.isSpectator() && player.position().distanceTo(Vec3.atBottomCenterOf(p)) < 5) {
				score += 5;
				break;
			}
		}
		return score;
	}

	private static void tickHide(Npc npc, int now, ServerLevel level) {
		if (npc.state == State.HIDE && now > npc.hideUntil) {
			if (Lockers.isInside(npc.body)) {
				Lockers.leave(npc.body, false);
			}
			npc.locker = null;
			npc.state = State.HOME;
			npc.body.setPose(Pose.STANDING);
			npc.walker.speed = 0.6f;
			setSpeed(npc, HwConfig.get().npcWalkSpeed);
			npc.walker.follow(Nav.to(level, npc.body.blockPosition(), npc.home, 1.0, Nav.Options.walker()));
			return;
		}
		if (Lockers.isInside(npc.body)) {
			npc.walker.halt();
			npc.look.tick(npc.body, now, level.getRandom(), true, npc.threat, npc.locker);
			return;
		}
		if (!npc.pathSet) {
			npc.pathSet = true;
			npc.locker = npc.noLocker ? null
				: Lockers.nearestFreeAway(level, npc.body.blockPosition(), 12, npc.seenFrom, HwConfig.get().botLockerAwayFromSighting);
			if (npc.locker != null) {
				npc.walker.speed = 1f;
				npc.walker.follow(Nav.to(level, npc.body.blockPosition(), Lockers.front(level, npc.locker), 0.6, Nav.Options.walker()));
			}
		}
		if (npc.locker != null) {
			if (npc.walker.tick() != Walker.Status.MOVING && Lockers.climbIn(npc.body, npc.locker) < 0) {
				// Taken or out of reach: a dark corner will do.
				npc.locker = null;
				npc.pathSet = false;
				npc.noLocker = true;
			}
			return;
		}
		if (!npc.pathSet) {
			npc.pathSet = true;
			Vec3 from = npc.state == State.HIDE ? npc.threat : null;
			Nav.Options opt = Nav.Options.walker();
			opt.maxNodes = 5000;
			npc.walker.speed = 1f;
			npc.walker.follow(Nav.search(level, npc.body.blockPosition(), npc.state == State.HIDE ? 26 : 16, opt,
				(p, cost) -> hideHere(level, p, cost, from, npc.body)));
		}
		Walker.Status status = npc.walker.tick();
		if (status != Walker.Status.MOVING) {
			npc.walker.halt();
			npc.body.setPose(Pose.CROUCHING);
			npc.look.tick(npc.body, now, level.getRandom(), true, npc.threat, null);
		} else {
			npc.look.reset();
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
			ServerPlayer asked = npc.callFor == null ? null : level.getServer().getPlayerList().getPlayer(npc.callFor);
			if (asked != null) {
				Match.score(asked, cfg.scoreCallPolice, Component.translatable("halloween.score.police_called"));
			}
			npc.callFor = null;
			AiStats.add(npc.body, "npc.called_police");
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
			// Out through an open exit (it is used up, like for a civilian).
			Component exit = Exits.npcEscape(level, npc.body.position(), cfg.npcRescueRadius);
			if (exit != null) {
				rescue(npc, leader, level, exit);
				return;
			}
		}
		double dist = leader.distanceTo(npc.body);
		// Keep up: run when the leader runs or falls behind.
		setSpeed(npc, dist > 5 || leader.isSprinting() ? cfg.npcRunSpeed : cfg.npcWalkSpeed + 0.02);
		// The leader at an open way out: come right up to it, not wait a couple of steps behind.
		double keep = Exits.openExitNear(leader.position(), cfg.npcRescueRadius + 1) ? 0.9 : 2.2;
		if (dist <= keep) {
			npc.walker.stop();
			npc.walker.face(leader.getEyePosition());
			return;
		}
		if (now >= npc.repathAt || npc.walker.idle()) {
			npc.repathAt = now + 10;
			npc.walker.speed = 1f;
			npc.walker.follow(Nav.to(level, npc.body.blockPosition(), leader.blockPosition(), keep < 1 ? 0.6 : 1.8, Nav.Options.walker()));
		}
		npc.walker.tick();
	}

	private static void rescue(Npc npc, ServerPlayer leader, ServerLevel level, Component exit) {
		HwConfig cfg = HwConfig.get();
		Mannequin body = npc.body;
		level.sendParticles(ParticleTypes.HAPPY_VILLAGER, body.getX(), body.getY() + 1, body.getZ(), 15, 0.4, 0.6, 0.4, 0);
		Match.score(leader, cfg.scoreRescueNpc, Component.translatable("halloween.score.npc_rescued", npc.name));
		rescuedCount++;
		Match.announce(level.getServer(), Component.translatable("halloween.match.npc_rescued", leader.getDisplayName(), npc.name, exit).withStyle(ChatFormatting.GREEN));
		AiStats.add(body, "npc.rescued");
		NPCS.remove(body.getUUID());
		MatchManager.eliminate(body);
	}
}
