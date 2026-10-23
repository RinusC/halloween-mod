package dev.halloween.game;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.halloween.Halloween;
import dev.halloween.Role;
import dev.halloween.net.HwNet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.Vec3;

/**
 * Where players wait between matches: Haddonfield High on Halloween night, far out of town (out of sight from any
 * match). A schoolyard with a Halloween fair out front (a pumpkin patch, a bonfire, a candy stall, a scarecrow,
 * lanterns along the path), where everyone arrives; inside, the hallway with its lockers, a trophy case and the
 * stands that explain the game (step onto the mat and it tells you); the gym, twice the old one, with "Ready" in
 * the middle of the court and the parkour climbing round its walls to the rafters (ParkourCourse: sixty-odd jumps,
 * three checkpoints and the finish, best times kept); and the shooting range (ShootingRange: rounds against pop-up targets, best
 * scores kept). "Ready": once everyone in is ready, two at least, the match starts in 10 s, Michael picked at
 * random. Built once (with the town, or by /hw lobby), rebuilt when this layout is newer than the one standing.
 *
 * <p>Relative to the lobby's corner (y 0 the ground floor, its floor at y -1): the yard is z 0..21; the school's
 * front wall z 22; the hallway z 23..31 (6 high) across the whole width; behind it the gym x 1..58 and the range
 * x 60..98, z 33..78.
 */
public final class Lobby extends SavedData {
	static final int W = 100;
	static final int D = 80;
	static final int H = 23;
	/** The layout standing: older lobbies (the single gym) are rebuilt. */
	private static final int LAYOUT = 2;
	/** How far north of the town centre the school stands. */
	private static final int AWAY = 420;
	private static final int READY_TICKS = 200;
	private static final int PARKOUR_LIMIT = 20 * 60 * 15;
	/** The gym, inside. */
	static final int GYM_X0 = 1;
	static final int GYM_X1 = 58;
	static final int GYM_Z0 = 33;
	static final int GYM_Z1 = 78;
	static final int GYM_H = 21;

	/** A stand: its mat (where to stand), the board behind it facing {@code faces}. */
	private record Station(String key, int x, int z, Direction faces) {
	}

	/** Stands along the hallway's front wall, and the range's scores in the range. */
	private static final List<Station> STATIONS = List.of(
		new Station("goal", 6, 24, Direction.SOUTH), new Station("search", 14, 24, Direction.SOUTH), new Station("doors", 22, 24, Direction.SOUTH),
		new Station("lockers", 30, 24, Direction.SOUTH), new Station("grab", 38, 24, Direction.SOUTH), new Station("stamina", 61, 24, Direction.SOUTH),
		new Station("shape", 69, 24, Direction.SOUTH), new Station("perks", 77, 24, Direction.SOUTH), new Station("records", 85, 24, Direction.SOUTH),
		new Station("practice", 93, 24, Direction.SOUTH), new Station("range", 94, 34, Direction.SOUTH));

	private record Saved(Optional<BlockPos> origin, Map<String, Integer> records, Map<String, Integer> rangeRecords, int layout) {
		static final Codec<Saved> CODEC = RecordCodecBuilder.create(i -> i.group(
			BlockPos.CODEC.optionalFieldOf("origin").forGetter(Saved::origin),
			Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("records", Map.of()).forGetter(Saved::records),
			Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("range_records", Map.of()).forGetter(Saved::rangeRecords),
			Codec.INT.optionalFieldOf("layout", 1).forGetter(Saved::layout)).apply(i, Saved::new));
	}

	public static final SavedDataType<Lobby> TYPE = new SavedDataType<>(Halloween.id("lobby"), Lobby::new,
		Saved.CODEC.xmap(Lobby::fromSaved, Lobby::toSaved), DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

	private BlockPos origin;
	private final Map<String, Integer> records = new HashMap<>();
	private final Map<String, Integer> rangeRecords = new HashMap<>();
	private int layout = LAYOUT;

	private static final class Run {
		boolean armed;
		int startTick = -1;
		/** The last checkpoint reached (-1: none yet). */
		int checkpoint = -1;
	}

	private static final Map<UUID, Run> RUNS = new HashMap<>();
	private static final Set<UUID> READY = new HashSet<>();
	private static int readyCountdown = -1;

	public Lobby() {
	}

	private static Lobby fromSaved(Saved s) {
		Lobby l = new Lobby();
		l.origin = s.origin().orElse(null);
		l.records.putAll(s.records());
		l.rangeRecords.putAll(s.rangeRecords());
		l.layout = s.layout();
		return l;
	}

	private Saved toSaved() {
		return new Saved(Optional.ofNullable(this.origin), Map.copyOf(this.records), Map.copyOf(this.rangeRecords), this.layout);
	}

	private static Lobby get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	public static BlockPos origin(MinecraftServer server) {
		return get(server).origin;
	}

	public static boolean exists(MinecraftServer server) {
		return origin(server) != null;
	}

	/** Where the school goes for a town centred here. */
	public static BlockPos placeFor(BlockPos center) {
		return center.offset(-W / 2, 0, -AWAY);
	}

	/** Builds the school if there is a map to put it beside and none yet (or an older one). */
	public static void ensure(MinecraftServer server) {
		BlockPos center = MapPoints.get(server.overworld()).first(MapPoints.Type.CENTER);
		Lobby data = get(server);
		if (center != null && (data.origin == null || data.layout < LAYOUT)) {
			build(server.overworld(), placeFor(center));
		}
	}

	private static Vec3 spawn(BlockPos o) {
		return new Vec3(o.getX() + W / 2.0 + 0.5, o.getY(), o.getZ() + 5.5);
	}

	/** Is this spot in the school or its yard? */
	public static boolean contains(MinecraftServer server, Vec3 at) {
		BlockPos o = origin(server);
		if (o == null) {
			return false;
		}
		double x = at.x - o.getX();
		double y = at.y - o.getY();
		double z = at.z - o.getZ();
		return x >= -1 && x <= W + 1 && z >= -1 && z <= D + 1 && y >= -2 && y <= H + 2;
	}

	public static boolean inside(ServerPlayer p) {
		return contains(p.level().getServer(), p.position());
	}

	/** Into the schoolyard, on their feet, nothing in hand. */
	public static void sendTo(ServerPlayer p) {
		BlockPos o = origin(p.level().getServer());
		if (o == null || Bots.isBot(p)) {
			return;
		}
		if (p.gameMode.getGameModeForPlayer() != GameType.CREATIVE) {
			p.setGameMode(GameType.ADVENTURE);
		}
		Vec3 at = spawn(o);
		dev.halloween.Vr.place(p, p.level().getServer().overworld(), at.x, at.y, at.z, Set.of(), 0f, 0f);
		p.setHealth(p.getMaxHealth());
		p.getFoodData().setFoodLevel(20);
		ShootingRange.stripGuns(p);
		Demogorgon.reset(p);
		Vecna.reset(p);
		RUNS.remove(p.getUUID());
	}

	/** The match is over: everybody back to school. */
	public static void returnAll(MinecraftServer server) {
		if (!exists(server)) {
			return;
		}
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			sendTo(p);
		}
	}

	public static void onJoin(ServerPlayer p) {
		MinecraftServer server = p.level().getServer();
		ensure(server);
		if (exists(server) && !Match.running() && !Match.frozen()) {
			sendTo(p);
		}
	}

	// ---------------------------------------------------------------- ticking

	public static void tick(MinecraftServer server) {
		BlockPos o = origin(server);
		if (o == null) {
			return;
		}
		int now = server.getTickCount();
		boolean idle = !Match.running() && !Match.frozen();
		List<ServerPlayer> humans = new ArrayList<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!Bots.isBot(p)) {
				humans.add(p);
			}
		}
		READY.removeIf(id -> server.getPlayerList().getPlayer(id) == null);
		if (!idle) {
			READY.clear();
			readyCountdown = -1;
			ShootingRange.stop(server.overworld());
		} else {
			ShootingRange.tick(server, o, get(server));
			if (humans.size() >= 2 && READY.size() >= humans.size()) {
				if (readyCountdown < 0) {
					readyCountdown = READY_TICKS;
					announce(server, Component.translatable("halloween.lobby.all_ready").withStyle(ChatFormatting.GOLD));
				} else if (--readyCountdown <= 0) {
					readyCountdown = -1;
					READY.clear();
					Component result = Match.start(server, null);
					announce(server, result);
				}
			} else if (readyCountdown >= 0) {
				readyCountdown = -1;
				announce(server, Component.translatable("halloween.lobby.ready_cancel").withStyle(ChatFormatting.GRAY));
			}
		}
		for (ServerPlayer p : humans) {
			boolean in = idle && MatchManager.role(p) == Role.NONE && inside(p);
			if (!in) {
				RUNS.remove(p.getUUID());
				if (now % 10 == 0) {
					ServerPlayNetworking.send(p, HwNet.LobbyInfo.NONE);
				}
				continue;
			}
			// A safe place: no falls hurt, nobody goes hungry; the guns stay in the range.
			p.resetFallDistance();
			p.getFoodData().setFoodLevel(20);
			if (now % 5 == 0 && !ShootingRange.inside(o, p.getX(), p.getY(), p.getZ())) {
				ShootingRange.stripGuns(p);
			}
			Component status = ShootingRange.status(p, now);
			Component run = parkour(server, p, o, now);
			if (status == null) {
				status = run;
			}
			if (now % 10 == 0) {
				ServerPlayNetworking.send(p, new HwNet.LobbyInfo(true, status != null ? status : readyLine(humans.size()), panel(server, p, o)));
			}
		}
	}

	private static Component readyLine(int humans) {
		if (readyCountdown >= 0) {
			return Component.translatable("halloween.lobby.starting", (readyCountdown + 19) / 20).withStyle(ChatFormatting.GOLD);
		}
		return Component.translatable(humans >= 2 ? "halloween.lobby.ready_count" : "halloween.lobby.ready_alone", READY.size(), humans)
			.withStyle(ChatFormatting.YELLOW);
	}

	/** What the stand they are at says (nothing away from the stands). */
	private static List<Component> panel(MinecraftServer server, ServerPlayer p, BlockPos o) {
		for (Station s : STATIONS) {
			if (p.position().distanceTo(Vec3.atBottomCenterOf(o.offset(s.x(), 0, s.z()))) > 2.6) {
				continue;
			}
			List<Component> lines = new ArrayList<>();
			lines.add(Component.translatable("halloween.lobby.st." + s.key()).withStyle(ChatFormatting.GOLD));
			if (s.key().equals("records") || s.key().equals("range")) {
				boolean range = s.key().equals("range");
				Lobby data = get(server);
				List<Map.Entry<String, Integer>> best = new ArrayList<>((range ? data.rangeRecords : data.records).entrySet());
				best.sort(range ? Map.Entry.<String, Integer>comparingByValue().reversed() : Map.Entry.comparingByValue());
				for (int i = 0; i < best.size() && i < 5; i++) {
					String value = range ? Component.translatable("halloween.lobby.points", best.get(i).getValue()).getString() : seconds(best.get(i).getValue());
					lines.add(Component.literal((i + 1) + ". " + best.get(i).getKey() + " — " + value));
				}
				if (best.isEmpty()) {
					lines.add(Component.translatable(range ? "halloween.lobby.no_range_records" : "halloween.lobby.no_records").withStyle(ChatFormatting.GRAY));
				}
				return lines;
			}
			for (int i = 1; i <= 4; i++) {
				lines.add(Component.translatable("halloween.lobby.st." + s.key() + "." + i));
			}
			return lines;
		}
		return List.of();
	}

	private static String seconds(int ticks) {
		return String.format(java.util.Locale.ROOT, "%.2f", ticks / 20f).replace('.', ',') + " s";
	}

	private static boolean inGym(BlockPos rel) {
		return rel.getX() >= GYM_X0 && rel.getX() <= GYM_X1 && rel.getZ() >= GYM_Z0 && rel.getZ() <= GYM_Z1;
	}

	/**
	 * The course: armed on the start pad, the clock runs from leaving it. Each checkpoint in turn is noted; a fall
	 * to the gym's floor puts you back on the last one (the start before the first), the clock running on; walking
	 * out of the gym gives the run up. The finish counts once every checkpoint is behind you.
	 */
	private static Component parkour(MinecraftServer server, ServerPlayer p, BlockPos o, int now) {
		ParkourCourse course = ParkourCourse.get();
		BlockPos under = BlockPos.containing(p.getX(), p.getY() - 0.2, p.getZ());
		BlockPos rel = under.subtract(o);
		Run run = RUNS.computeIfAbsent(p.getUUID(), k -> new Run());
		BlockPos start = course.pads.getFirst().pos();
		boolean grounded = p.onGround();
		if (grounded && rel.equals(start)) {
			run.armed = true;
			run.startTick = -1;
			run.checkpoint = -1;
			return Component.translatable("halloween.lobby.parkour_ready").withStyle(ChatFormatting.GREEN);
		}
		if (run.armed && run.startTick < 0 && !grounded) {
			run.startTick = now;
			run.armed = false;
			run.checkpoint = -1;
		}
		if (run.startTick < 0) {
			return null;
		}
		int time = now - run.startTick;
		int total = course.checkpoints.size();
		if (grounded) {
			Integer cp = course.checkpointOf(rel);
			if (cp != null && cp == run.checkpoint + 1) {
				run.checkpoint = cp;
				Notices.send(p, Component.translatable("halloween.lobby.parkour_checkpoint", cp + 1, total, seconds(time)).withStyle(ChatFormatting.AQUA));
				p.level().playSound(null, p.blockPosition(), SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.PLAYERS, 0.8f, 1.3f);
			}
			if (course.isFinish(rel) && run.checkpoint == total - 1) {
				run.startTick = -1;
				Lobby data = get(server);
				String name = p.getScoreboardName();
				Integer best = data.records.get(name);
				if (best == null || time < best) {
					data.records.put(name, time);
					data.setDirty();
					Notices.send(p, Component.translatable("halloween.lobby.parkour_record", seconds(time)).withStyle(ChatFormatting.GOLD));
				} else {
					Notices.send(p, Component.translatable("halloween.lobby.parkour_done", seconds(time), seconds(best)).withStyle(ChatFormatting.GREEN));
				}
				p.level().playSound(null, p.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6f, 1.2f);
				return null;
			}
			if (rel.getY() <= -1) {
				if (!inGym(rel)) {
					run.startTick = -1;
					return null;
				}
				// Down on the floor: back up to the last checkpoint.
				int pad = run.checkpoint < 0 ? 0 : course.checkpoints.get(run.checkpoint);
				BlockPos at = o.offset(course.pads.get(pad).pos());
				BlockPos next = course.pads.get(Math.min(pad + 1, course.pads.size() - 1)).pos();
				BlockPos from = course.pads.get(pad).pos();
				float yaw = (float) (Mth.atan2(-(next.getX() - from.getX()), next.getZ() - from.getZ()) * Mth.RAD_TO_DEG);
				dev.halloween.Vr.place(p, server.overworld(), at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5, Set.of(), yaw, 10f);
				Notices.send(p, Component.translatable(run.checkpoint < 0 ? "halloween.lobby.parkour_fell" : "halloween.lobby.parkour_back").withStyle(ChatFormatting.GRAY));
				if (run.checkpoint < 0) {
					run.startTick = -1;
					return null;
				}
			}
		}
		if (time > PARKOUR_LIMIT) {
			run.startTick = -1;
			return null;
		}
		return Component.translatable("halloween.lobby.parkour_time", seconds(time), run.checkpoint + 1, total).withStyle(ChatFormatting.AQUA);
	}

	/** Use on a button: "Ready" in the middle of the court, the practice on its stand, the range's. */
	public static InteractionResult onUse(ServerPlayer p, BlockPos pos) {
		BlockPos o = origin(p.level().getServer());
		if (o == null) {
			return InteractionResult.PASS;
		}
		if (pos.equals(readyButton(o))) {
			if (Match.running() || Match.frozen()) {
				return InteractionResult.PASS;
			}
			boolean now = READY.add(p.getUUID()) || !READY.remove(p.getUUID());
			Notices.send(p, Component.translatable(now ? "halloween.lobby.you_ready" : "halloween.lobby.you_not_ready")
				.withStyle(now ? ChatFormatting.GREEN : ChatFormatting.GRAY));
			announce(p.level().getServer(), Component.translatable(now ? "halloween.lobby.is_ready" : "halloween.lobby.is_not_ready", p.getDisplayName())
				.withStyle(ChatFormatting.GRAY));
		} else if (pos.equals(practiceButton(o))) {
			if (!Match.running() && !Match.frozen()) {
				Notices.send(p, Tutorial.start(p.level().getServer(), p));
			}
		} else if (!Match.running() && !Match.frozen()) {
			ShootingRange.onUse(p, o, pos);
		}
		return InteractionResult.PASS;
	}

	private static void announce(MinecraftServer server, Component message) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!Bots.isBot(p)) {
				Notices.send(p, message);
			}
		}
	}

	/** For tests: a player's best parkour time (ticks), or null. */
	public static Integer record(MinecraftServer server, String name) {
		return get(server).records.get(name);
	}

	Integer rangeRecord(String name) {
		return this.rangeRecords.get(name);
	}

	void setRangeRecord(String name, int points) {
		this.rangeRecords.put(name, points);
		this.setDirty();
	}

	/** For tests: a player's best range score, or null. */
	public static Integer rangeRecord(MinecraftServer server, String name) {
		return get(server).rangeRecords.get(name);
	}

	/** For tests: where a stand is (its mat). */
	public static BlockPos station(BlockPos o, String key) {
		for (Station s : STATIONS) {
			if (s.key().equals(key)) {
				return o.offset(s.x(), 0, s.z());
			}
		}
		return null;
	}

	/** For tests. */
	public static boolean ready(ServerPlayer p) {
		return READY.contains(p.getUUID());
	}

	public static BlockPos readyButton(BlockPos o) {
		return o.offset(30, 1, 56);
	}

	public static BlockPos practiceButton(BlockPos o) {
		BlockPos s = station(o, "practice");
		return s.above();
	}

	/** For tests: the range's buttons. */
	public static BlockPos rangeGunButton(BlockPos o, dev.halloween.game.HwItems.Kind kind) {
		return ShootingRange.gunButton(o, kind);
	}

	public static BlockPos rangeStartButton(BlockPos o) {
		return ShootingRange.startButton(o);
	}

	/** The course's pads, start pad first, finish last (world positions). */
	public static List<BlockPos> path(BlockPos o) {
		List<BlockPos> out = new ArrayList<>();
		for (ParkourCourse.Pad p : ParkourCourse.get().pads) {
			out.add(o.offset(p.pos()));
		}
		return out;
	}

	/** The checkpoints' centres (world positions), in order. */
	public static List<BlockPos> checkpoints(BlockPos o) {
		List<BlockPos> out = new ArrayList<>();
		ParkourCourse c = ParkourCourse.get();
		for (int i : c.checkpoints) {
			out.add(o.offset(c.pads.get(i).pos()));
		}
		return out;
	}

	/** For tests: how many jumps the course has, its highest pad, and what is wrong with it. */
	public static int jumps() {
		return ParkourCourse.get().jumps();
	}

	public static int courseTop() {
		return ParkourCourse.get().top();
	}

	public static List<String> courseProblems() {
		return ParkourCourse.get().problems(GYM_H);
	}

	// ---------------------------------------------------------------- building

	public static void build(ServerLevel level, BlockPos o) {
		Lobby data = get(level.getServer());
		BlockPos old = data.origin;
		data.origin = o.immutable();
		if (data.layout < LAYOUT || old == null) {
			// A new course: the old times don't compare.
			data.records.clear();
		}
		data.layout = LAYOUT;
		data.setDirty();
		ShootingRange.stop(level);
		// Halloween night.
		level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack().withSuppressedOutput(), "time set midnight");
		fill(level, o, -1, -1, -1, W, H + 2, D, MapPlan.AIR);
		fill(level, o, -1, -4, -1, W, -2, D, MapPlan.DIRT);
		yard(level, o);
		shell(level, o);
		hallway(level, o);
		gym(level, o);
		ShootingRange.build(level, o);
		stations(level, o);
		course(level, o);
	}

	/** The schoolyard: grass, the path to the doors, the fair. */
	private static void yard(ServerLevel level, BlockPos o) {
		fill(level, o, -1, -1, -1, W, -1, 21, MapPlan.GRASS);
		// The path from the gate to the doors, lanterns on posts either side.
		fill(level, o, 47, -1, 0, 52, -1, 21, MapPlan.b("dirt_path"));
		for (int z = 2; z <= 20; z += 4) {
			for (int x : new int[]{45, 54}) {
				set(level, o, x, 0, z, MapPlan.b("dark_oak_fence"));
				set(level, o, x, 1, z, MapPlan.b("dark_oak_fence"));
				set(level, o, x, 2, z, MapPlan.b("lantern"));
			}
		}
		// A fence all round, a gate gap in the middle.
		for (int x = 0; x < W; x++) {
			if (x < 46 || x > 53) {
				set(level, o, x, 0, 0, MapPlan.b("dark_oak_fence"));
			}
		}
		for (int z = 0; z <= 21; z++) {
			set(level, o, 0, 0, z, MapPlan.b("dark_oak_fence"));
			set(level, o, W - 1, 0, z, MapPlan.b("dark_oak_fence"));
		}
		// The pumpkin patch.
		for (int x = 4; x <= 22; x++) {
			for (int z = 4; z <= 16; z++) {
				set(level, o, x, -1, z, MapPlan.b("coarse_dirt"));
				int h = (x * 73 + z * 37) % 11;
				if (h == 0) {
					set(level, o, x, 0, z, MapPlan.b("pumpkin"));
				} else if (h == 4) {
					set(level, o, x, 0, z, MapPlan.b("carved_pumpkin").setValue(HorizontalDirectionalBlock.FACING, Direction.SOUTH));
				} else if (h == 7 && (x + z) % 3 == 0) {
					set(level, o, x, 0, z, MapPlan.b("jack_o_lantern").setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
				} else if (h == 9) {
					set(level, o, x, 0, z, MapPlan.b("dead_bush"));
				}
			}
		}
		// The bonfire with log benches round it.
		set(level, o, 32, 0, 11, MapPlan.b("campfire"));
		for (int[] b : new int[][]{{29, 11, 0}, {35, 11, 0}, {32, 8, 1}, {32, 14, 1}}) {
			BlockState log = MapPlan.b("stripped_oak_log").setValue(RotatedPillarBlock.AXIS, b[2] == 0 ? Direction.Axis.Z : Direction.Axis.X);
			for (int d = -1; d <= 1; d++) {
				set(level, o, b[0] + (b[2] == 1 ? d : 0), 0, b[1] + (b[2] == 0 ? d : 0), log);
			}
		}
		// The candy stall: a counter, posts, a striped roof.
		fill(level, o, 64, 0, 8, 70, 0, 8, MapPlan.b("spruce_planks"));
		for (int x : new int[]{64, 70}) {
			for (int z : new int[]{6, 8}) {
				fill(level, o, x, 1, z, x, 2, z, MapPlan.b("spruce_fence"));
			}
		}
		for (int x = 63; x <= 71; x++) {
			fill(level, o, x, 3, 5, x, 3, 9, MapPlan.b(x % 2 == 0 ? "orange_wool" : "black_wool"));
		}
		set(level, o, 67, 1, 8, MapPlan.b("cake"));
		set(level, o, 65, 1, 8, MapPlan.b("orange_candle").setValue(BlockStateProperties.CANDLES, 4).setValue(BlockStateProperties.LIT, true));
		set(level, o, 69, 1, 8, MapPlan.b("black_candle").setValue(BlockStateProperties.CANDLES, 3).setValue(BlockStateProperties.LIT, true));
		BlockPos stall = o.offset(67, 2, 9);
		level.setBlock(stall, MapPlan.b("dark_oak_wall_sign").setValue(WallSignBlock.FACING, Direction.NORTH), Block.UPDATE_CLIENTS);
		text(level, stall, Component.translatable("halloween.lobby.stall").withStyle(ChatFormatting.BOLD), Component.translatable("halloween.lobby.stall2"));
		// Hay bales and the scarecrow.
		for (int[] h : new int[][]{{80, 6}, {81, 6}, {82, 6}, {80, 7}, {81, 7}, {86, 12}, {87, 12}, {86, 13}, {76, 15}, {77, 15}}) {
			set(level, o, h[0], 0, h[1], MapPlan.b("hay_block"));
		}
		set(level, o, 81, 1, 6, MapPlan.b("hay_block"));
		set(level, o, 81, 2, 6, MapPlan.b("jack_o_lantern").setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
		set(level, o, 90, 0, 10, MapPlan.b("oak_fence"));
		set(level, o, 90, 1, 10, MapPlan.b("hay_block"));
		set(level, o, 89, 1, 10, MapPlan.b("oak_fence"));
		set(level, o, 91, 1, 10, MapPlan.b("oak_fence"));
		set(level, o, 90, 2, 10, MapPlan.b("carved_pumpkin").setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
		// Dead trees, cobwebs in them.
		for (int[] t : new int[][]{{8, 19}, {26, 3}, {40, 18}, {60, 17}, {95, 4}, {94, 18}}) {
			int h = 4 + (t[0] + t[1]) % 3;
			fill(level, o, t[0], 0, t[1], t[0], h, t[1], MapPlan.b("dark_oak_log"));
			set(level, o, t[0] + 1, h - 1, t[1], MapPlan.b("dark_oak_log").setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
			set(level, o, t[0] - 1, h, t[1], MapPlan.b("dark_oak_log").setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
			set(level, o, t[0], h, t[1] + 1, MapPlan.b("dark_oak_log").setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z));
			set(level, o, t[0] + 1, h, t[1], MapPlan.b("cobweb"));
		}
	}

	/** The building: brick walls, the front with windows and the doors, flat roofs. */
	private static void shell(ServerLevel level, BlockPos o) {
		// Floors.
		fill(level, o, 0, -1, 22, W - 1, -1, D - 1, MapPlan.b("stone_bricks"));
		// The hallway block (8 high), the gym (to y 22), the range (to y 10).
		walls(level, o, 0, 22, W - 1, 32, 8);
		walls(level, o, 0, 32, 59, D - 1, GYM_H + 1);
		walls(level, o, 59, 32, W - 1, D - 1, ShootingRange.HEIGHT + 1);
		fill(level, o, 0, 8, 22, W - 1, 8, 32, MapPlan.b("dark_oak_planks"));
		fill(level, o, 0, 7, 23, W - 1, 7, 31, MapPlan.b("smooth_stone"));
		fill(level, o, 0, GYM_H + 1, 32, 59, GYM_H + 1, D - 1, MapPlan.b("dark_oak_planks"));
		fill(level, o, 59, ShootingRange.HEIGHT + 1, 32, W - 1, ShootingRange.HEIGHT + 1, D - 1, MapPlan.b("dark_oak_planks"));
		// Parapets.
		for (int x = 0; x < W; x++) {
			set(level, o, x, 9, 22, MapPlan.b("stone_brick_wall"));
		}
		// The front: windows in pairs, the doors in the middle under a lit sign.
		for (int x = 3; x < W - 3; x += 5) {
			if (x >= 44 && x <= 55) {
				continue;
			}
			fill(level, o, x, 2, 22, x + 1, 4, 22, MapPlan.b("glass_pane"));
		}
		fill(level, o, 47, 0, 22, 52, 3, 22, MapPlan.AIR);
		fill(level, o, 46, 4, 21, 53, 4, 21, MapPlan.b("dark_oak_slab"));
		wallSign(level, o.offset(49, 5, 21), Direction.NORTH, Component.literal("HADDONFIELD").withStyle(ChatFormatting.BOLD),
			Component.literal("HIGH SCHOOL"));
		wallSign(level, o.offset(50, 5, 21), Direction.NORTH, Component.translatable("halloween.lobby.dance").withStyle(ChatFormatting.BOLD),
			Component.translatable("halloween.lobby.dance2"));
		for (int x : new int[]{46, 53}) {
			set(level, o, x, 2, 21, MapPlan.b("jack_o_lantern").setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
		}
		// The gym's tall wall over the hallway roof: big windows onto the yard.
		for (int x = 4; x < 56; x += 6) {
			fill(level, o, x, 12, 32, x + 2, 16, 32, MapPlan.b("glass_pane"));
		}
	}

	private static void walls(ServerLevel level, BlockPos o, int x0, int z0, int x1, int z1, int top) {
		for (int y = 0; y <= top; y++) {
			BlockState wall = MapPlan.b(y == 0 || y == top ? "stone_bricks" : "bricks");
			fill(level, o, x0, y, z0, x1, y, z0, wall);
			fill(level, o, x0, y, z1, x1, y, z1, wall);
			fill(level, o, x0, y, z0, x0, y, z1, wall);
			fill(level, o, x1, y, z0, x1, y, z1, wall);
		}
	}

	/** The hallway: terrazzo, lockers along the back, doors to the gym and the range, a trophy case, lamps. */
	private static void hallway(ServerLevel level, BlockPos o) {
		for (int x = 1; x < W - 1; x++) {
			for (int z = 23; z <= 31; z++) {
				set(level, o, x, -1, z, MapPlan.b(x % 8 == 0 || z == 23 || z == 31 ? "polished_andesite" : "polished_diorite"));
			}
		}
		// Painted walls inside.
		for (int y = 1; y <= 6; y++) {
			BlockState paint = MapPlan.b(y <= 2 ? "light_blue_terracotta" : "white_terracotta");
			for (int x = 1; x < W - 1; x++) {
				boolean frontDoor = x >= 47 && x <= 52 && y <= 3;
				if (!frontDoor && !level.getBlockState(o.offset(x, y, 22)).is(net.minecraft.world.level.block.Blocks.GLASS_PANE)) {
					set(level, o, x, y, 22, paint);
				}
				set(level, o, x, y, 32, paint);
			}
		}
		// Doors through to the gym (two) and the range.
		for (int[] d : new int[][]{{18, 22}, {33, 37}, {74, 78}}) {
			fill(level, o, d[0], 0, 32, d[1], 3, 32, MapPlan.AIR);
			fill(level, o, d[0] - 1, 0, 32, d[0] - 1, 4, 32, MapPlan.b("stripped_dark_oak_log"));
			fill(level, o, d[1] + 1, 0, 32, d[1] + 1, 4, 32, MapPlan.b("stripped_dark_oak_log"));
			fill(level, o, d[0], 4, 32, d[1], 4, 32, MapPlan.b("stripped_dark_oak_log").setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
		}
		wallSign(level, o.offset(20, 5, 31), Direction.NORTH, Component.translatable("halloween.lobby.gym").withStyle(ChatFormatting.BOLD),
			Component.translatable("halloween.lobby.gym2"));
		wallSign(level, o.offset(35, 5, 31), Direction.NORTH, Component.translatable("halloween.lobby.gym").withStyle(ChatFormatting.BOLD),
			Component.translatable("halloween.lobby.gym2"));
		wallSign(level, o.offset(76, 5, 31), Direction.NORTH, Component.translatable("halloween.lobby.range").withStyle(ChatFormatting.BOLD),
			Component.translatable("halloween.lobby.range2"));
		// Lockers: teal, doors shut, rows of them between the doors.
		for (int x = 1; x < W - 1; x++) {
			boolean door = x >= 17 && x <= 23 || x >= 32 && x <= 38 || x >= 73 && x <= 79 || x >= 25 && x <= 30;
			if (door) {
				continue;
			}
			fill(level, o, x, 0, 32, x, 2, 32, MapPlan.b(x % 2 == 0 ? "cyan_terracotta" : "light_blue_terracotta"));
			for (int y = 0; y <= 1; y++) {
				set(level, o, x, y, 31, MapPlan.b("iron_trapdoor").setValue(TrapDoorBlock.OPEN, true).setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
			}
		}
		// The trophy case.
		fill(level, o, 25, 0, 30, 30, 0, 31, MapPlan.b("dark_oak_planks"));
		fill(level, o, 25, 1, 30, 30, 2, 30, MapPlan.b("glass"));
		fill(level, o, 25, 3, 30, 30, 3, 31, MapPlan.b("dark_oak_slab"));
		set(level, o, 26, 1, 31, MapPlan.b("gold_block"));
		set(level, o, 28, 1, 31, MapPlan.b("skeleton_skull").setValue(BlockStateProperties.ROTATION_16, 8));
		set(level, o, 29, 1, 31, MapPlan.b("candle").setValue(BlockStateProperties.CANDLES, 2).setValue(BlockStateProperties.LIT, true));
		set(level, o, 27, 1, 31, MapPlan.b("carved_pumpkin").setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
		// Lamps hanging down the hall, banners and cobwebs.
		for (int x = 4; x < W - 2; x += 7) {
			set(level, o, x, 6, 27, MapPlan.b("lantern").setValue(LanternBlock.HANGING, true));
		}
		for (int x = 10; x < W - 4; x += 8) {
			if (x >= 44 && x <= 55) {
				continue;
			}
			set(level, o, x, 4, 31, MapPlan.b(x % 16 == 10 ? "orange_wall_banner" : "black_wall_banner").setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
		}
		for (int[] c : new int[][]{{1, 23}, {1, 31}, {W - 2, 23}, {W - 2, 31}}) {
			set(level, o, c[0], 6, c[1], MapPlan.b("cobweb"));
		}
		for (int x : new int[]{1, W - 2}) {
			set(level, o, x, 0, 27, MapPlan.b("hay_block"));
			set(level, o, x, 1, 27, MapPlan.b("jack_o_lantern").setValue(HorizontalDirectionalBlock.FACING, x == 1 ? Direction.EAST : Direction.WEST));
		}
	}

	/** The gym: the court, the bleachers, hoops and the scoreboard, high windows, rafters with lamps, decorations. */
	private static void gym(ServerLevel level, BlockPos o) {
		fill(level, o, GYM_X0, -1, GYM_Z0, GYM_X1, -1, GYM_Z1, MapPlan.b("birch_planks"));
		// The court's lines and the centre circle.
		for (int x = 6; x <= 54; x++) {
			set(level, o, x, -1, 38, MapPlan.b("white_concrete"));
			set(level, o, x, -1, 74, MapPlan.b("white_concrete"));
		}
		for (int z = 38; z <= 74; z++) {
			set(level, o, 6, -1, z, MapPlan.b("white_concrete"));
			set(level, o, 54, -1, z, MapPlan.b("white_concrete"));
		}
		for (int x = 6; x <= 54; x++) {
			set(level, o, x, -1, 56, MapPlan.b("white_concrete"));
		}
		for (int a = 0; a < 40; a++) {
			double r = Math.PI * 2 * a / 40;
			set(level, o, 30 + (int) Math.round(Math.cos(r) * 4), -1, 56 + (int) Math.round(Math.sin(r) * 4), MapPlan.b("orange_terracotta"));
		}
		// Inside the walls: brick below, painted above, high windows west and north.
		for (int y = 4; y <= GYM_H; y++) {
			BlockState paint = MapPlan.b(y <= 6 ? "orange_terracotta" : "light_gray_concrete");
			fill(level, o, 0, y, 33, 0, y, GYM_Z1, paint);
			fill(level, o, 1, y, D - 1, 58, y, D - 1, paint);
			fill(level, o, 59, y, 33, 59, y, GYM_Z1, paint);
			if (y >= 9) {
				fill(level, o, 1, y, 32, 58, y, 32, paint);
			}
		}
		for (int z = 36; z < GYM_Z1; z += 6) {
			fill(level, o, 0, 12, z, 0, 16, z + 1, MapPlan.b("glass_pane"));
		}
		for (int x = 4; x < 56; x += 6) {
			if (x >= 22 && x <= 38) {
				continue;
			}
			fill(level, o, x, 12, D - 1, x + 1, 16, D - 1, MapPlan.b("glass_pane"));
		}
		for (int x = 4; x < 56; x += 6) {
			fill(level, o, x, 12, 32, x + 2, 16, 32, MapPlan.b("glass_pane"));
		}
		// Bleachers along the east wall.
		for (int k = 0; k < 4; k++) {
			fill(level, o, 58 - k, 0, 40, 58 - k, 3 - k, 72, MapPlan.b(k % 2 == 0 ? "spruce_planks" : "oak_planks"));
		}
		for (int z = 41; z <= 71; z += 5) {
			set(level, o, 57, 3, z, MapPlan.b("carved_pumpkin").setValue(HorizontalDirectionalBlock.FACING, Direction.WEST));
		}
		// A hoop at the north end, the scoreboard over it.
		fill(level, o, 28, 8, D - 2, 32, 10, D - 2, MapPlan.b("white_concrete"));
		set(level, o, 30, 7, D - 3, MapPlan.b("orange_concrete"));
		fill(level, o, 23, 13, D - 2, 37, 16, D - 2, MapPlan.b("black_concrete"));
		for (int x = 24; x <= 36; x += 3) {
			set(level, o, x, 16, D - 2, MapPlan.b("shroomlight"));
		}
		wallSign(level, o.offset(27, 14, D - 3), Direction.NORTH, Component.literal("HOME").withStyle(ChatFormatting.BOLD), Component.literal("31"));
		wallSign(level, o.offset(33, 14, D - 3), Direction.NORTH, Component.literal("GUESTS").withStyle(ChatFormatting.BOLD), Component.literal("10"));
		// Rafters with lamps (over the middle of the court, clear of the parkour by the walls).
		for (int z = 40; z <= 72; z += 8) {
			fill(level, o, GYM_X0, GYM_H, z, GYM_X1, GYM_H, z, MapPlan.b("spruce_planks"));
		}
		for (int x = 16; x <= 44; x += 7) {
			for (int z = 48; z <= 64; z += 8) {
				set(level, o, x, GYM_H - 1, z, MapPlan.b("lantern").setValue(LanternBlock.HANGING, true));
			}
		}
		// Jack-o'-lanterns set into the walls all round, low and high: a warm, patchy light.
		for (int y : new int[]{5, 12}) {
			for (int z = 35; z < GYM_Z1; z += 6) {
				set(level, o, 0, y, z, MapPlan.b("jack_o_lantern").setValue(HorizontalDirectionalBlock.FACING, Direction.EAST));
				set(level, o, 59, y + 1, z + 3, MapPlan.b("jack_o_lantern").setValue(HorizontalDirectionalBlock.FACING, Direction.WEST));
			}
			for (int x = 3; x < GYM_X1; x += 6) {
				set(level, o, x, y, D - 1, MapPlan.b("jack_o_lantern").setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
				if (y > 8) {
					set(level, o, x + 3, y, 32, MapPlan.b("jack_o_lantern").setValue(HorizontalDirectionalBlock.FACING, Direction.SOUTH));
				}
			}
		}
		// Halloween: pumpkins on hay in the corners, banners, cobwebs, a candle table by "Ready".
		for (int[] c : new int[][]{{1, 33}, {54, 33}, {1, GYM_Z1}, {54, GYM_Z1}}) {
			set(level, o, c[0], 0, c[1], MapPlan.b("hay_block"));
			set(level, o, c[0], 1, c[1], MapPlan.b("jack_o_lantern").setValue(HorizontalDirectionalBlock.FACING, c[1] == 33 ? Direction.SOUTH : Direction.NORTH));
			set(level, o, c[0], GYM_H, c[1], MapPlan.b("cobweb"));
		}
		for (int z = 36; z < GYM_Z1; z += 6) {
			set(level, o, 1, 9, z + 3, MapPlan.b(z % 12 == 0 ? "orange_wall_banner" : "black_wall_banner").setValue(HorizontalDirectionalBlock.FACING, Direction.EAST));
		}
		// "Ready" in the middle of the court.
		set(level, o, 30, 0, 56, MapPlan.b("polished_blackstone"));
		level.setBlock(readyButton(o), MapPlan.b("stone_button").setValue(ButtonBlock.FACE, AttachFace.FLOOR).setValue(ButtonBlock.FACING, Direction.NORTH), Block.UPDATE_CLIENTS);
		BlockPos sign = o.offset(30, 0, 55);
		level.setBlock(sign, MapPlan.b("dark_oak_sign").setValue(StandingSignBlock.ROTATION, 8), Block.UPDATE_CLIENTS);
		text(level, sign, Component.translatable("halloween.lobby.ready_sign").withStyle(ChatFormatting.BOLD), Component.translatable("halloween.lobby.ready_sign2"));
		fill(level, o, 28, 0, 58, 32, 0, 58, MapPlan.b("dark_oak_planks"));
		set(level, o, 29, 1, 58, MapPlan.b("orange_candle").setValue(BlockStateProperties.CANDLES, 3).setValue(BlockStateProperties.LIT, true));
		set(level, o, 30, 1, 58, MapPlan.b("jack_o_lantern").setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
		set(level, o, 31, 1, 58, MapPlan.b("black_candle").setValue(BlockStateProperties.CANDLES, 2).setValue(BlockStateProperties.LIT, true));
	}

	/** The stands: a board, its name on a sign, a mat in front to stand on; the practice button on its board. */
	private static void stations(ServerLevel level, BlockPos o) {
		for (Station s : STATIONS) {
			Direction faces = s.faces();
			Direction along = faces.getClockWise();
			BlockPos mat = o.offset(s.x(), 0, s.z());
			BlockPos board = mat.relative(faces.getOpposite());
			for (int d = -1; d <= 1; d++) {
				BlockPos b = board.relative(along, d);
				for (int y = 0; y <= 2; y++) {
					level.setBlock(b.above(y), MapPlan.b("dark_oak_planks"), Block.UPDATE_CLIENTS);
				}
				level.setBlock(mat.relative(along, d), MapPlan.b("red_carpet"), Block.UPDATE_CLIENTS);
			}
			wallSign(level, mat.above(2), faces, Component.translatable("halloween.lobby.st." + s.key()).withStyle(ChatFormatting.BOLD),
				Component.translatable("halloween.lobby.sign_hint"));
		}
		level.setBlock(practiceButton(o), MapPlan.b("stone_button").setValue(ButtonBlock.FACE, AttachFace.WALL).setValue(ButtonBlock.FACING, Direction.SOUTH),
			Block.UPDATE_CLIENTS);
	}

	/** The parkour, its start sign. */
	private static void course(ServerLevel level, BlockPos o) {
		ParkourCourse c = ParkourCourse.get();
		for (Map.Entry<BlockPos, BlockState> e : c.blocks.entrySet()) {
			level.setBlock(o.offset(e.getKey()), e.getValue(), Block.UPDATE_CLIENTS);
		}
		BlockPos start = o.offset(c.pads.getFirst().pos());
		BlockPos sign = start.offset(-1, 1, -1);
		level.setBlock(sign, MapPlan.b("dark_oak_sign").setValue(StandingSignBlock.ROTATION, 0), Block.UPDATE_CLIENTS);
		text(level, sign, Component.translatable("halloween.lobby.parkour_sign").withStyle(ChatFormatting.BOLD), Component.translatable("halloween.lobby.parkour_sign2"));
	}

	static void wallSign(ServerLevel level, BlockPos at, Direction faces, Component line1, Component line2) {
		level.setBlock(at, MapPlan.b("dark_oak_wall_sign").setValue(WallSignBlock.FACING, faces), Block.UPDATE_CLIENTS);
		text(level, at, line1, line2);
	}

	private static void text(ServerLevel level, BlockPos at, Component line1, Component line2) {
		if (level.getBlockEntity(at) instanceof SignBlockEntity sign) {
			SignText text = new SignText().setMessage(1, line1).setMessage(2, line2).setHasGlowingText(true);
			sign.setText(text, true);
			sign.setChanged();
			level.sendBlockUpdated(at, level.getBlockState(at), level.getBlockState(at), 3);
		}
	}

	static void set(ServerLevel level, BlockPos o, int x, int y, int z, BlockState state) {
		level.setBlock(o.offset(x, y, z), state, Block.UPDATE_CLIENTS);
	}

	static void fill(ServerLevel level, BlockPos o, int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
		for (BlockPos p : BlockPos.betweenClosed(o.offset(x0, y0, z0), o.offset(x1, y1, z1))) {
			level.setBlock(p, state, Block.UPDATE_CLIENTS);
		}
	}
}
