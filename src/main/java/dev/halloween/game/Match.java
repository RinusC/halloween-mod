package dev.halloween.game;

import dev.halloween.HwConfig;
import dev.halloween.Role;
import dev.halloween.net.HwNet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

/**
 * One match: countdown, 20-minute timer, civilian score, escapes, deputies and the final tally.
 * Civilians who escape or die come back as sheriff's deputies after a delay, at most two per match;
 * the rest watch. The match ends when time runs out or nobody is left on the civilians' side.
 * The winner is decided by score: the Shape's against the civilians' total.
 */
public final class Match {
	public enum State {
		IDLE, COUNTDOWN, RUNNING, ENDED
	}

	private static final class Participant {
		final String name;
		int score;
		boolean escaped;
		boolean died;
		boolean finalOut;
		int respawnAt = -1;
		/** Which way they got out (null: they did not). */
		Component exit;
		boolean deputy;

		Participant(String name) {
			this.name = name;
		}
	}

	private static State state = State.IDLE;
	private static int ticksLeft;
	private static int countdown;
	private static int deputiesUsed;
	private static UUID shapeId;
	private static final Map<UUID, Participant> PEOPLE = new LinkedHashMap<>();
	private static boolean dirty;
	private static int tickCounter;
	private static int startedAt;
	/** Server tick the match proper (after the countdown) began at. */
	private static int runningAt;
	/** What happened this match, in order, with the minute it happened (for the summary screen). */
	private static final List<Component> CHRONICLE = new ArrayList<>();
	private static final int CHRONICLE_MAX = 80;
	/** The biggest single moment for points this match: who, how many, what for. */
	private static Component best;
	private static int bestPoints;
	private static int kills;

	private Match() {
	}

	/** Server tick the current match was started at (identifies the match). */
	public static int startTick() {
		return startedAt;
	}

	/** Every match starts in a town as it was: breakers on (lights back), doors shut, wardrobes opening onto the room. */
	private static void restoreTown(ServerLevel level, BlockPos center, int radius) {
		for (int cx = (center.getX() - radius) >> 4; cx <= (center.getX() + radius) >> 4; cx++) {
			for (int cz = (center.getZ() - radius) >> 4; cz <= (center.getZ() + radius) >> 4; cz++) {
				net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunk(cx, cz);
				for (int i = 0; i < chunk.getSections().length; i++) {
					net.minecraft.world.level.chunk.LevelChunkSection section = chunk.getSections()[i];
					if (section.hasOnlyAir() || !section.maybeHas(s -> s.is(net.minecraft.world.level.block.Blocks.LEVER) || s.is(dev.halloween.HwBlocks.DEAD_LANTERN)
						|| s.getBlock() instanceof net.minecraft.world.level.block.DoorBlock || LockerBlock.is(s))) {
						continue;
					}
					int y0 = net.minecraft.core.SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i));
					for (int ly = 0; ly < 16; ly++) {
						for (int lz = 0; lz < 16; lz++) {
							for (int lx = 0; lx < 16; lx++) {
								net.minecraft.world.level.block.state.BlockState state = section.getBlockState(lx, ly, lz);
								BlockPos pos = new BlockPos(chunk.getPos().getMinBlockX() + lx, y0 + ly, chunk.getPos().getMinBlockZ() + lz);
								if (state.is(net.minecraft.world.level.block.Blocks.LEVER) && !state.getValue(net.minecraft.world.level.block.LeverBlock.POWERED)
									&& LightSystem.isBreaker(level, pos)) {
									level.setBlock(pos, state.setValue(net.minecraft.world.level.block.LeverBlock.POWERED, true), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
									LightSystem.setLights(level, pos, true);
								} else if (state.is(dev.halloween.HwBlocks.DEAD_LANTERN)) {
									// A blackout cut short (the game closed mid-match): the street lamps come back on.
									level.setBlock(pos, net.minecraft.world.level.block.Blocks.LANTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LanternBlock.HANGING,
										state.getValue(net.minecraft.world.level.block.LanternBlock.HANGING)), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
								} else if (state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock
									&& state.getValue(net.minecraft.world.level.block.DoorBlock.OPEN)) {
									level.setBlock(pos, state.setValue(net.minecraft.world.level.block.DoorBlock.OPEN, false),
										net.minecraft.world.level.block.Block.UPDATE_CLIENTS | net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE);
								} else if (LockerBlock.is(state) && state.getValue(LockerBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER) {
									Lockers.faceTheRoom(level, pos, state);
								}
							}
						}
					}
				}
			}
		}
	}

	public static boolean running() {
		return state == State.RUNNING;
	}

	/** Everyone stands still while the countdown runs. */
	public static boolean frozen() {
		return state == State.COUNTDOWN;
	}

	public static int ticksLeft() {
		return ticksLeft;
	}

	/** Survivors who have died this match (at least once). */
	public static int deadSurvivors() {
		int n = 0;
		for (Participant p : PEOPLE.values()) {
			n += p.died ? 1 : 0;
		}
		return n;
	}

	/** Survivors who got away. */
	public static int escaped() {
		int n = 0;
		for (Participant p : PEOPLE.values()) {
			n += p.exit != null ? 1 : 0;
		}
		return n;
	}

	public static void markDirty() {
		dirty = true;
	}

	// ---------------------------------------------------------------- start / stop

	/** Starts a match. {@code shape} may be null for a random Shape; a lone player plays a civilian. */
	public static Component start(MinecraftServer server, ServerPlayer shape) {
		ServerLevel level = server.overworld();
		HwConfig cfg = HwConfig.get();
		MapPoints points = MapPoints.get(level);
		BlockPos center = points.first(MapPoints.Type.CENTER);
		if (MapJob.busy()) {
			return Component.translatable("halloween.cmd.map_busy");
		}
		if (center == null || points.list(MapPoints.Type.SPAWN_CIVILIAN).isEmpty()) {
			return Component.translatable("halloween.cmd.no_points");
		}
		stopQuietly(server);
		Bots.removeAll(server);
		// What appears from here on belongs to this match (see Leftovers).
		Leftovers.begin(server);
		List<ServerPlayer> players = new ArrayList<>(server.getPlayerList().getPlayers());
		if (players.isEmpty()) {
			return Component.translatable("halloween.cmd.no_players");
		}
		if (shape == null && players.size() > 1) {
			shape = players.get(level.getRandom().nextInt(players.size()));
		}
		MatchManager.reset(server);
		DoorLocks.clear(server);
		Windows.clear(server);
		Holds.clear();
		Fight.clear(server);
		Weather.clear(server);
		Gear.clear(server);
		Exits.setup(level, level.getRandom());
		Lockers.clear(server);
		Talks.clear();
		Minigames.clear();
		Guns.clear();
		GunCase.clear(server.overworld());
		Nav.setArea(area(points));
		GunCase.setup(level, level.getRandom());
		Loot.setup(level, center, level.getRandom());
		StreetWeapons.setup(level, center, Loot.radius(), level.getRandom());
		restoreTown(level, center, Loot.radius());
		Minimap.bake(level, area(points), center);
		MapBoards.setup(level, area(points), center);

		CommandSourceStack console = server.createCommandSourceStack().withLevel(level).withSuppressedOutput();
		for (String command : new String[]{"time set midnight", "weather clear", "gamerule advance_time false",
			"gamerule advance_weather false", "gamerule spawn_mobs false", "gamerule natural_health_regeneration false",
			"gamerule show_death_messages false", "gamerule fire_spread_radius_around_player 0", "gamerule show_advancement_messages false", "gamerule locator_bar false", "kill @e[type=item]", "kill @e[type=arrow]",
			// The arena's practice mannequins have no place in a match; townsfolk take their role.
			"kill @e[type=minecraft:mannequin,tag=" + MatchManager.VICTIM_TAG + ",tag=!" + Townsfolk.TAG + "]"}) {
			server.getCommands().performPrefixedCommand(console, command);
		}
		Weather.setup(level, level.getRandom());
		Events.setup(level);

		PEOPLE.clear();
		CHRONICLE.clear();
		best = null;
		bestPoints = 0;
		kills = 0;
		Skins.clear();
		Music.clear();
		deputiesUsed = 0;
		shapeId = shape == null ? null : shape.getUUID();
		List<BlockPos> spawns = new ArrayList<>(points.list(MapPoints.Type.SPAWN_CIVILIAN));
		Collections.shuffle(spawns, new java.util.Random(level.getRandom().nextLong()));
		// Bots make up the civilians' side when people are missing (a lone civilian gets company too).
		// The Runt: a person if one was picked (/hw runt), else a bot (spawned once the civilians are made up).
		ServerPlayer runt = Runt.humanFor(players, shape);
		int civilians = players.size() - (shape == null ? 0 : 1) - (runt == null ? 0 : 1);
		if (cfg.botsFillTo > civilians) {
			List<String> names = new ArrayList<>(List.of(cfg.botNames));
			Collections.shuffle(names, new java.util.Random(level.getRandom().nextLong()));
			for (int b = 0; b < cfg.botsFillTo - civilians && b < names.size(); b++) {
				players.add(Bots.spawn(server, names.get(b), spawns.get((civilians + b) % spawns.size())));
			}
		}
		Police.clear(server);
		Townsfolk.spawnAll(level, level.getRandom());
		// Everybody's perk for this match (before the roles: the tough one starts with more health).
		Perks.begin(server, players);
		int i = 0;
		for (ServerPlayer player : players) {
			Inventory.dropAll(player);
			player.getInventory().clearContent();
			if (player == shape) {
				MatchManager.applyRole(player, Role.SHAPE);
				BlockPos spawn = points.first(MapPoints.Type.SPAWN_SHAPE);
				teleport(player, spawn != null ? spawn : center);
			} else if (player == runt) {
				MatchManager.applyRole(player, Role.RUNT);
				BlockPos spawn = points.first(MapPoints.Type.SPAWN_SHAPE);
				teleport(player, spawn != null ? spawn : center);
			} else {
				MatchManager.applyRole(player, Role.CIVILIAN);
				PEOPLE.put(player.getUUID(), new Participant(player.getScoreboardName()));
				teleport(player, spawns.get(i++ % spawns.size()));
			}
		}
		if (runt == null) {
			BlockPos lair = points.first(MapPoints.Type.SPAWN_SHAPE);
			ServerPlayer bot = Runt.botFor(server, lair != null ? lair : center);
			if (bot != null) {
				Inventory.dropAll(bot);
				MatchManager.applyRole(bot, Role.RUNT);
				teleport(bot, lair != null ? lair : center);
			}
		}
		state = State.COUNTDOWN;
		countdown = cfg.countdownSeconds * 20;
		Cutscenes.send(server, countdown, shape, players, center);
		startedAt = server.getTickCount();
		ticksLeft = cfg.matchSeconds * 20;
		dirty = true;
		return Component.translatable("halloween.cmd.started", players.size());
	}

	/** The match area: every map point, plus a block of slack. Bots never path or stay outside it. */
	private static net.minecraft.world.level.levelgen.structure.BoundingBox area(MapPoints points) {
		int minX = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (List<BlockPos> list : points.all().values()) {
			for (BlockPos p : list) {
				minX = Math.min(minX, p.getX());
				minZ = Math.min(minZ, p.getZ());
				maxX = Math.max(maxX, p.getX());
				maxZ = Math.max(maxZ, p.getZ());
			}
		}
		return new net.minecraft.world.level.levelgen.structure.BoundingBox(minX - 1, -2048, minZ - 1, maxX + 1, 2048, maxZ + 1);
	}

	private static void teleport(ServerPlayer player, BlockPos pos) {
		player.teleportTo(player.level(), pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, java.util.Set.of(), player.getYRot(), 0f, false);
	}

	public static void stop(MinecraftServer server) {
		stopQuietly(server);
		syncAll(server);
	}

	private static void stopQuietly(MinecraftServer server) {
		if (state == State.IDLE) {
			return;
		}
		state = State.IDLE;
		Events.clear(server);
		Exits.clear(server.overworld());
		Minimap.clear(server.overworld());
		Loot.clear();
		StreetWeapons.clear(server);
		Holds.clear();
		Fight.clear(server);
		Weather.clear(server);
		Perks.end();
		Traps.clear(server);
		Noise.clear();
		Leftovers.end();
		Panic.clear();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (MatchManager.role(player) != Role.NONE) {
				player.getInventory().clearContent();
				MatchManager.applyRole(player, Role.NONE);
			}
		}
		Runt.clear(server);
		Lockers.clear(server);
		Talks.clear();
		Minigames.clear();
		Guns.clear();
		GunCase.clear(server.overworld());
		PEOPLE.clear();
		Nav.setArea(null);
		Townsfolk.clear(server);
		Police.clear(server);
		Bots.removeAll(server);
	}

	// ---------------------------------------------------------------- tick

	public static void tick(MinecraftServer server) {
		if (state == State.IDLE) {
			return;
		}
		ServerLevel level = server.overworld();
		tickCounter++;
		if (state == State.COUNTDOWN) {
			// The opening scenes play meanwhile (no numbers counting down).
			if (--countdown <= 0) {
				state = State.RUNNING;
				runningAt = server.getTickCount();
				announce(server, Component.translatable("halloween.match.begin").withStyle(ChatFormatting.DARK_RED));
			}
		} else if (state == State.RUNNING) {
			ticksLeft--;
			Exits.tick(level);
			Events.tick(server);
			if (HwConfig.get().gateOpenLastSeconds > 0 && ticksLeft == HwConfig.get().gateOpenLastSeconds * 20) {
				announce(server, Component.translatable("halloween.match.gates").withStyle(ChatFormatting.GOLD));
			}
			for (Map.Entry<UUID, Participant> e : PEOPLE.entrySet()) {
				Participant p = e.getValue();
				if (p.respawnAt >= 0 && tickCounter >= p.respawnAt) {
					p.respawnAt = -1;
					ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
					if (player != null) {
						comeBackAsDeputy(player);
					}
				}
			}
			if (ticksLeft <= 0 || nobodyLeft(server) && !Tutorial.keepsMatch()) {
				end(server);
			}
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (MatchManager.role(player).survivor() && !player.isSpectator()) {
				Inventory.enforce(player);
			}
		}
		if (dirty || tickCounter % 10 == 0) {
			dirty = false;
			syncAll(server);
		}
	}

	/**
	 * Is nobody left on the civilians' side? A survivor still in play, someone about to come back, or a
	 * deputy still about (they can still bring townsfolk out) keeps the match going; police officers
	 * (townsfolk-like helpers, not players) don't.
	 */
	private static boolean nobodyLeft(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (MatchManager.role(player).survivor() && (!player.isSpectator() && player.isAlive() || Exits.isSeated(player))) {
				return false;
			}
		}
		for (Map.Entry<UUID, Participant> e : PEOPLE.entrySet()) {
			Participant p = e.getValue();
			if (p.respawnAt >= 0) {
				return false;
			}
			ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
			if (player != null && MatchManager.role(player).survivor() && !player.isSpectator()) {
				return false;
			}
		}
		return true;
	}

	// ---------------------------------------------------------------- events

	public static void score(ServerPlayer player, int points, Component reason) {
		Participant p = PEOPLE.get(player.getUUID());
		if (p == null || state != State.RUNNING) {
			return;
		}
		p.score += points;
		ServerPlayNetworking.send(player, new HwNet.ScoreEvent(points, List.of(reason)));
		noteBest(player.getDisplayName(), points, reason);
		dirty = true;
	}

	/** A civilian got out through an exit. */
	public static void escape(ServerPlayer player, Component exit) {
		Participant p = PEOPLE.get(player.getUUID());
		if (p == null) {
			return;
		}
		score(player, HwConfig.get().scoreEscape, Component.translatable("halloween.score.escape", exit));
		p.escaped = true;
		p.exit = exit;
		AiStats.add(player, "escaped");
		Inventory.dropAll(player);
		player.setGameMode(GameType.SPECTATOR);
		player.connection.send(new ClientboundSetTitleTextPacket(Component.translatable("halloween.match.you_escaped").withStyle(ChatFormatting.GREEN)));
		announce(player.level().getServer(), Component.translatable("halloween.match.escaped", player.getDisplayName(), exit).withStyle(ChatFormatting.GREEN));
		if (Tutorial.active()) {
			// Practice: getting out ends the survivor part, nobody comes back as a deputy.
			p.finalOut = true;
			return;
		}
		scheduleDeputy(player, p);
	}

	/** A survivor went down (killed by the Shape or otherwise). */
	public static void onSurvivorDown(ServerPlayer player) {
		Participant p = PEOPLE.get(player.getUUID());
		if (p == null || state == State.IDLE) {
			return;
		}
		AiStats.add(player, "died");
		Inventory.dropAll(player);
		if (MatchManager.role(player) == Role.DEPUTY || p.died) {
			p.finalOut = true;
			p.died = true;
			dirty = true;
			return;
		}
		p.died = true;
		scheduleDeputy(player, p);
	}

	private static void scheduleDeputy(ServerPlayer player, Participant p) {
		HwConfig cfg = HwConfig.get();
		if (Bots.isBot(player)) {
			// Bots don't come back as deputies: both places stay for people.
			p.finalOut = true;
			Bots.leaveLater(player);
			dirty = true;
			return;
		}
		if (deputiesUsed >= cfg.maxDeputies || state != State.RUNNING) {
			p.finalOut = true;
			player.connection.send(new ClientboundSetSubtitleTextPacket(Component.translatable("halloween.match.no_deputy").withStyle(ChatFormatting.GRAY)));
			dirty = true;
			return;
		}
		deputiesUsed++;
		p.respawnAt = tickCounter + cfg.deputyRespawnSeconds * 20;
		player.connection.send(new ClientboundSetSubtitleTextPacket(Component.translatable("halloween.match.deputy_soon", cfg.deputyRespawnSeconds)));
		dirty = true;
	}

	private static void comeBackAsDeputy(ServerPlayer player) {
		MapPoints points = MapPoints.get(player.level());
		BlockPos spawn = points.first(MapPoints.Type.SPAWN_DEPUTY);
		if (spawn == null) {
			spawn = points.first(MapPoints.Type.SPAWN_CIVILIAN);
		}
		MatchManager.applyRole(player, Role.DEPUTY);
		Participant p = PEOPLE.get(player.getUUID());
		if (p != null) {
			p.deputy = true;
		}
		Inventory.give(player, HwItems.create(HwItems.Kind.REVOLVER));
		Inventory.give(player, HwItems.create(HwItems.Kind.BATON));
		Inventory.give(player, HwItems.create(HwItems.Kind.FLASHLIGHT));
		teleport(player, spawn);
		player.connection.send(new ClientboundSetSubtitleTextPacket(Component.translatable("halloween.match.deputy_hint").withStyle(ChatFormatting.GRAY)));
		player.connection.send(new ClientboundSetTitleTextPacket(Component.translatable("halloween.match.you_deputy").withStyle(ChatFormatting.BLUE)));
		announce(player.level().getServer(), Component.translatable("halloween.match.deputy_arrives", player.getDisplayName()).withStyle(ChatFormatting.BLUE));
		dirty = true;
	}

	public static void announce(MinecraftServer server, Component message) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Notices.send(player, message);
		}
		chronicle(server, message);
	}

	/** Writes a line in the match's chronicle, with the minute and second of the match it happened at. */
	public static void chronicle(MinecraftServer server, Component what) {
		if (state != State.RUNNING || CHRONICLE.size() >= CHRONICLE_MAX) {
			return;
		}
		int t = Math.max(0, server.getTickCount() - runningAt) / 20;
		CHRONICLE.add(Component.literal(String.format(java.util.Locale.ROOT, "%d:%02d  ", t / 60, t % 60)).withStyle(ChatFormatting.DARK_GRAY)
			.append(what.copy()));
	}

	/** The Shape took a life: counted, and written in the chronicle. */
	public static void noteKill(MinecraftServer server, Component victim, String how) {
		if (state != State.RUNNING) {
			return;
		}
		kills++;
		chronicle(server, Component.translatable("halloween.chronicle.kill." + how, victim).withStyle(ChatFormatting.RED));
	}

	/** Remembers the match's best moment for points (anyone's, the Shape's too). */
	public static void noteBest(Component who, int points, Component reason) {
		if (state == State.RUNNING && points > bestPoints) {
			bestPoints = points;
			best = Component.translatable("halloween.match.best", who, points, reason);
		}
	}

	// ---------------------------------------------------------------- end

	private static void end(MinecraftServer server) {
		Townsfolk.payHidden(server);
		if (!Tutorial.active()) {
			// Match points go into everybody's purse, survivors' and Michael's alike: perks are bought with them.
			Map<UUID, Integer> scores = new HashMap<>();
			PEOPLE.forEach((id, p) -> scores.put(id, p.score));
			ServerPlayer theShape = shapeId == null ? null : server.getPlayerList().getPlayer(shapeId);
			ShapeState theState = theShape == null ? null : MatchManager.shapeState(theShape);
			if (theState != null) {
				scores.merge(shapeId, theState.score, Integer::sum);
			}
			Runt.scores().forEach((id, pts) -> {
				if (!(server.getPlayerList().getPlayer(id) instanceof BotPlayer)) {
					scores.merge(id, pts, Integer::sum);
				}
			});
			Perks.award(server, scores);
		}
		state = State.ENDED;
		int civilians = 0;
		int escapes = 0;
		List<Component> lines = new ArrayList<>();
		for (Participant p : PEOPLE.values()) {
			civilians += p.score;
			if (p.escaped) {
				escapes++;
			}
		}
		ServerPlayer shape = shapeId == null ? null : server.getPlayerList().getPlayer(shapeId);
		ShapeState shapeState = shape == null ? null : MatchManager.shapeState(shape);
		int shapeScore = shapeState == null ? 0 : shapeState.score;
		// Michael's side: his points and his Runt's.
		boolean shapeWon = shapeId != null && shapeScore + Runt.totalScore() > civilians;
		lines.add(Component.translatable(shapeWon ? "halloween.match.shape_wins" : "halloween.match.civilians_win")
			.withStyle(shapeWon ? ChatFormatting.DARK_RED : ChatFormatting.GREEN));
		if (shapeId != null) {
			lines.add(Component.translatable("halloween.match.line_shape", shape == null ? "?" : shape.getScoreboardName(), shapeScore));
		}
		for (ServerPlayer r : Runt.all(server)) {
			Runt.State rs = Runt.state(r);
			lines.add(Component.translatable("halloween.match.line_runt", r.getScoreboardName(), rs == null ? 0 : rs.score, rs == null ? 0 : rs.level));
		}
		lines.add(Component.translatable("halloween.match.line_civilians", civilians, escapes));
		for (Participant p : PEOPLE.values()) {
			Component status;
			if (p.escaped) {
				status = Component.translatable("halloween.match.status_escaped_by", p.exit == null ? Component.literal("?") : p.exit).withStyle(ChatFormatting.GREEN);
			} else if (p.died && p.finalOut) {
				status = Component.translatable(p.deputy ? "halloween.match.status_dead_deputy" : "halloween.match.status_dead").withStyle(ChatFormatting.RED);
			} else if (p.deputy) {
				status = Component.translatable("halloween.match.status_deputy").withStyle(ChatFormatting.BLUE);
			} else {
				status = Component.translatable(p.died ? "halloween.match.status_dead" : "halloween.match.status_alive")
					.withStyle(p.died ? ChatFormatting.RED : ChatFormatting.WHITE);
			}
			lines.add(Component.translatable("halloween.match.line_civilian", p.name, p.score, status));
		}
		int elapsed = Math.max(0, server.getTickCount() - runningAt) / 20;
		lines.add(Component.empty());
		lines.add(Component.translatable("halloween.match.line_night", String.format(java.util.Locale.ROOT, "%d:%02d", elapsed / 60, elapsed % 60),
			Component.translatable("halloween.weather.short." + Weather.key()), kills).withStyle(ChatFormatting.GRAY));
		if (best != null) {
			lines.add(best.copy().withStyle(ChatFormatting.GOLD));
		}
		HwNet.MatchEnd payload = new HwNet.MatchEnd(shapeWon, lines, List.copyOf(CHRONICLE));
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			ServerPlayNetworking.send(player, payload);
		}
		syncAll(server);
		stopQuietly(server);
		state = State.IDLE;
		syncAll(server);
		Tutorial.matchEnded(server);
		// Everyone back to the gym (the summary screen is up meanwhile).
		Lobby.returnAll(server);
	}

	/** Hands the Shape's place in the final tally to {@code shape} (the AI simulation gives the role to a bot). */
	public static void setShape(ServerPlayer shape) {
		shapeId = shape.getUUID();
	}

	public static void forceTimeLeft(int seconds) {
		ticksLeft = seconds * 20;
		dirty = true;
	}

	// ---------------------------------------------------------------- sync

	private static void syncAll(MinecraftServer server) {
		List<String> names = new ArrayList<>();
		List<Integer> states = new ArrayList<>();
		for (Participant p : PEOPLE.values()) {
			names.add(p.name);
			states.add(p.escaped ? 1 : p.died && (p.finalOut || p.respawnAt >= 0) ? 2 : 0);
		}
		int rescued = Townsfolk.rescued();
		int dead = Townsfolk.dead();
		HwNet.Roster roster = new HwNet.Roster(names, states, Math.max(0, Townsfolk.spawned() - rescued - dead), rescued, dead);
		List<HwNet.ExitInfo> exits = Exits.infos();
		List<BlockPos> containers = Loot.containers();
		List<BlockPos> searched = Loot.searched();
		List<BlockPos> phones = MapPoints.get(server.overworld()).list(MapPoints.Type.PHONE);
		List<BlockPos> cut = Exits.cutPhones();
		List<Integer> npcs = Townsfolk.ids();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (Bots.isBot(player)) {
				continue;
			}
			ServerPlayNetworking.send(player, roster);
			Participant p = PEOPLE.get(player.getUUID());
			int respawn = p != null && p.respawnAt >= 0 ? Math.max(0, p.respawnAt - tickCounter) : 0;
			ServerPlayNetworking.send(player, new HwNet.MatchSync(state.ordinal(), ticksLeft, countdown, exits, containers, searched,
				phones, cut, respawn, p == null ? 0 : p.score, npcs, Townsfolk.followerIds(player), Police.wanted(), Police.reportable(), GunCase.closed()));
		}
	}

	public static void syncTo(ServerPlayer player) {
		dirty = true;
	}

	/** Score per participant name (tests, debugging). */
	public static Map<String, Integer> scores() {
		Map<String, Integer> map = new HashMap<>();
		PEOPLE.values().forEach(p -> map.put(p.name, p.score));
		return map;
	}

	public static State state() {
		return state;
	}
}
