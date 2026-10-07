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
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.Vec3;

/**
 * Where players wait between matches: the Haddonfield High gym, decked out for Halloween, far out of town (out of
 * sight from any match). Stands along the walls explain the game (step up to one and it tells you), tips come up
 * on screen, a parkour course runs over the court with best times kept, and two buttons in the middle: "Ready"
 * (once everyone in is ready, two at least, the match starts in 10 s, Michael picked at random) and the practice.
 * Players come here when they join and when a match ends. Built once (with the town, or by /hw lobby).
 */
public final class Lobby extends SavedData {
	static final int W = 40;
	static final int D = 30;
	static final int H = 13;
	/** How far north of the town centre the gym stands. */
	private static final int AWAY = 420;
	private static final int READY_TICKS = 200;
	private static final int PARKOUR_LIMIT = 20 * 120;

	private record Station(String key, int x, int z) {
	}

	/** Stands: west wall, east wall, south wall (relative to the gym's corner). */
	private static final List<Station> STATIONS = List.of(
		new Station("goal", 2, 7), new Station("search", 2, 12), new Station("doors", 2, 17), new Station("lockers", 2, 22),
		new Station("grab", W - 3, 7), new Station("stamina", W - 3, 12), new Station("shape", W - 3, 17), new Station("perks", W - 3, 22),
		new Station("records", 8, 2), new Station("practice", W - 9, 2));
	private static final String[] PARKOUR_BLOCKS = {"spruce_planks", "hay_block", "jack_o_lantern", "stripped_oak_log", "carved_pumpkin", "dark_oak_planks"};

	private record Saved(Optional<BlockPos> origin, Map<String, Integer> records) {
		static final Codec<Saved> CODEC = RecordCodecBuilder.create(i -> i.group(
			BlockPos.CODEC.optionalFieldOf("origin").forGetter(Saved::origin),
			Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("records", Map.of()).forGetter(Saved::records)).apply(i, Saved::new));
	}

	public static final SavedDataType<Lobby> TYPE = new SavedDataType<>(Halloween.id("lobby"), Lobby::new,
		Saved.CODEC.xmap(Lobby::fromSaved, Lobby::toSaved), DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

	private BlockPos origin;
	private final Map<String, Integer> records = new HashMap<>();

	private static final class Run {
		boolean armed;
		int startTick = -1;
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
		return l;
	}

	private Saved toSaved() {
		return new Saved(Optional.ofNullable(this.origin), Map.copyOf(this.records));
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

	/** Builds the gym if there is a map to put it beside and none yet. */
	public static void ensure(MinecraftServer server) {
		BlockPos center = MapPoints.get(server.overworld()).first(MapPoints.Type.CENTER);
		if (!exists(server) && center != null) {
			build(server.overworld(), center.offset(-W / 2, 0, -AWAY));
		}
	}

	private static Vec3 spawn(BlockPos o) {
		return new Vec3(o.getX() + W / 2.0 + 0.5, o.getY(), o.getZ() + 3.5);
	}

	public static boolean inside(ServerPlayer p) {
		BlockPos o = origin(p.level().getServer());
		if (o == null) {
			return false;
		}
		double x = p.getX() - o.getX();
		double y = p.getY() - o.getY();
		double z = p.getZ() - o.getZ();
		return x >= -1 && x <= W + 1 && z >= -1 && z <= D + 1 && y >= -2 && y <= H + 2;
	}

	/** Into the gym, on their feet, nothing in hand. */
	public static void sendTo(ServerPlayer p) {
		BlockPos o = origin(p.level().getServer());
		if (o == null || Bots.isBot(p)) {
			return;
		}
		if (p.gameMode.getGameModeForPlayer() != GameType.CREATIVE) {
			p.setGameMode(GameType.ADVENTURE);
		}
		Vec3 at = spawn(o);
		p.teleportTo(p.level().getServer().overworld(), at.x, at.y, at.z, Set.of(), 0f, 0f, false);
		p.setHealth(p.getMaxHealth());
		p.getFoodData().setFoodLevel(20);
		RUNS.remove(p.getUUID());
	}

	/** The match is over: everybody back to the gym. */
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
		} else if (humans.size() >= 2 && READY.size() >= humans.size()) {
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
		for (ServerPlayer p : humans) {
			boolean in = idle && MatchManager.role(p) == Role.NONE && inside(p);
			if (!in) {
				RUNS.remove(p.getUUID());
				if (now % 10 == 0) {
					ServerPlayNetworking.send(p, HwNet.LobbyInfo.NONE);
				}
				continue;
			}
			// A safe place: no falls hurt, nobody goes hungry.
			p.resetFallDistance();
			p.getFoodData().setFoodLevel(20);
			Component status = parkour(server, p, o, now);
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
			if (s.key().equals("records")) {
				Lobby data = get(server);
				List<Map.Entry<String, Integer>> best = new ArrayList<>(data.records.entrySet());
				best.sort(Map.Entry.comparingByValue());
				for (int i = 0; i < best.size() && i < 5; i++) {
					lines.add(Component.literal((i + 1) + ". " + best.get(i).getKey() + " — " + seconds(best.get(i).getValue())));
				}
				if (best.isEmpty()) {
					lines.add(Component.translatable("halloween.lobby.no_records").withStyle(ChatFormatting.GRAY));
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

	/** The course: armed on the start pad, the clock runs from stepping off it; the floor ends a run. */
	private static Component parkour(MinecraftServer server, ServerPlayer p, BlockPos o, int now) {
		List<BlockPos> path = path(o);
		BlockPos under = BlockPos.containing(p.getX(), p.getY() - 0.2, p.getZ());
		Run run = RUNS.computeIfAbsent(p.getUUID(), k -> new Run());
		BlockPos start = path.getFirst();
		BlockPos finish = path.getLast();
		if (under.equals(start)) {
			run.armed = true;
			run.startTick = -1;
			return Component.translatable("halloween.lobby.parkour_ready").withStyle(ChatFormatting.GREEN);
		}
		if (run.armed && run.startTick < 0 && p.onGround() == false) {
			run.startTick = now;
			run.armed = false;
		}
		if (run.startTick < 0) {
			return null;
		}
		int time = now - run.startTick;
		if (under.equals(finish)) {
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
		if (p.onGround() && under.getY() <= o.getY() - 1 || time > PARKOUR_LIMIT) {
			run.startTick = -1;
			Notices.send(p, Component.translatable("halloween.lobby.parkour_fell").withStyle(ChatFormatting.GRAY));
			return null;
		}
		return Component.translatable("halloween.lobby.parkour_time", seconds(time)).withStyle(ChatFormatting.AQUA);
	}

	/** Use on a button: "Ready" in the middle of the court, the practice on its stand. */
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
		return o.offset(W / 2, 1, D / 2);
	}

	public static BlockPos practiceButton(BlockPos o) {
		return o.offset(W - 9, 1, 1);
	}

	/** The course's blocks, start pad first, finish last (world positions). */
	public static List<BlockPos> path(BlockPos o) {
		List<BlockPos> out = new ArrayList<>();
		int x = 3;
		int y = -1;
		out.add(o.offset(x, y, D - 8));
		for (int i = 1; i <= 13; i++) {
			boolean up = i % 2 == 1;
			x += up ? 2 : 3;
			y += up ? 1 : 0;
			int z = D - 8 + (i % 3 == 0 ? 0 : i % 3 == 1 ? 1 : -1);
			out.add(o.offset(x, y, z));
		}
		return out;
	}

	// ---------------------------------------------------------------- building

	public static void build(ServerLevel level, BlockPos o) {
		Lobby data = get(level.getServer());
		data.origin = o.immutable();
		data.setDirty();
		BlockState air = Blocks.AIR.defaultBlockState();
		fill(level, o, -1, -2, -1, W, H + 1, D, air);
		fill(level, o, -1, -2, -1, W, -2, D, MapPlan.b("stone_bricks"));
		fill(level, o, 0, -1, 0, W - 1, -1, D - 1, MapPlan.b("birch_planks"));
		// The court's lines and the centre circle.
		for (int x = 3; x <= W - 4; x++) {
			set(level, o, x, -1, 3, MapPlan.b("white_concrete"));
			set(level, o, x, -1, D - 11, MapPlan.b("white_concrete"));
		}
		for (int z = 3; z <= D - 11; z++) {
			set(level, o, 3, -1, z, MapPlan.b("white_concrete"));
			set(level, o, W - 4, -1, z, MapPlan.b("white_concrete"));
			set(level, o, W / 2, -1, z, MapPlan.b("white_concrete"));
		}
		for (int a = 0; a < 32; a++) {
			double r = Math.PI * 2 * a / 32;
			set(level, o, W / 2 + (int) Math.round(Math.cos(r) * 3), -1, (D - 8) / 2 + 2 + (int) Math.round(Math.sin(r) * 3), MapPlan.b("orange_terracotta"));
		}
		// Walls: brick below, painted above, high windows; the roof with beams and lights.
		for (int y = 0; y <= H; y++) {
			BlockState wall = MapPlan.b(y <= 3 ? "bricks" : "light_gray_concrete");
			fill(level, o, -1, y, -1, W, y, -1, wall);
			fill(level, o, -1, y, D, W, y, D, wall);
			fill(level, o, -1, y, -1, -1, y, D, wall);
			fill(level, o, W, y, -1, W, y, D, wall);
		}
		for (int x = 3; x < W - 2; x += 4) {
			fill(level, o, x, 8, -1, x + 1, 10, -1, MapPlan.b("glass"));
			fill(level, o, x, 8, D, x + 1, 10, D, MapPlan.b("glass"));
		}
		fill(level, o, -1, H + 1, -1, W, H + 1, D, MapPlan.b("dark_oak_planks"));
		for (int z = 4; z < D; z += 7) {
			fill(level, o, 0, H, z, W - 1, H, z, MapPlan.b("spruce_planks"));
			for (int x = 4; x < W; x += 8) {
				set(level, o, x, H, z, MapPlan.b("sea_lantern"));
			}
		}
		// Bleachers along the north wall.
		for (int k = 0; k < 4; k++) {
			fill(level, o, 4, 0, D - 1 - k, W - 5, 3 - k, D - 1 - k, MapPlan.b(k % 2 == 0 ? "spruce_planks" : "oak_planks"));
		}
		// Hoops at both ends.
		for (int x : new int[]{0, W - 1}) {
			int step = x == 0 ? 1 : -1;
			fill(level, o, x, 6, D / 2 - 6, x, 7, D / 2 - 4, MapPlan.b("white_concrete"));
			set(level, o, x + step, 5, D / 2 - 5, MapPlan.b("orange_concrete"));
		}
		// Halloween: pumpkins and hay, banners, cobwebs up in the corners, a table of candles.
		for (int[] c : new int[][]{{0, 0}, {W - 1, 0}, {0, D - 6}, {W - 1, D - 6}, {W / 2 - 3, 0}, {W / 2 + 3, 0}}) {
			set(level, o, c[0], 0, c[1], MapPlan.b("hay_block"));
			set(level, o, c[0], 1, c[1], MapPlan.b("jack_o_lantern").setValue(HorizontalDirectionalBlock.FACING, c[1] == 0 ? Direction.SOUTH : Direction.NORTH));
		}
		for (int z = 4; z < D - 5; z += 5) {
			set(level, o, 0, 5, z, MapPlan.b(z % 10 == 4 ? "orange_wall_banner" : "black_wall_banner").setValue(HorizontalDirectionalBlock.FACING, Direction.EAST));
			set(level, o, W - 1, 5, z, MapPlan.b(z % 10 == 4 ? "black_wall_banner" : "orange_wall_banner").setValue(HorizontalDirectionalBlock.FACING, Direction.WEST));
		}
		for (int[] c : new int[][]{{0, 0}, {W - 1, 0}, {0, D - 1}, {W - 1, D - 1}}) {
			set(level, o, c[0], H - 1, c[1], MapPlan.b("cobweb"));
		}
		fill(level, o, W / 2 - 1, 0, D - 6, W / 2 + 1, 0, D - 6, MapPlan.b("dark_oak_planks"));
		set(level, o, W / 2, 1, D - 6, MapPlan.b("candle").setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.CANDLES, 3));
		set(level, o, W / 2 - 1, 1, D - 6, MapPlan.b("carved_pumpkin"));
		// The stands: a board on the wall, its name on a sign, a mat in front to stand on.
		for (Station s : STATIONS) {
			Direction faces = s.x() == 2 ? Direction.EAST : s.x() == W - 3 ? Direction.WEST : Direction.SOUTH;
			int bx = faces == Direction.EAST ? 0 : faces == Direction.WEST ? W - 1 : s.x();
			int bz = faces == Direction.SOUTH ? 0 : s.z();
			for (int d = -1; d <= 1; d++) {
				int cx = faces == Direction.SOUTH ? bx + d : bx;
				int cz = faces == Direction.SOUTH ? bz : bz + d;
				fill(level, o, cx, 0, cz, cx, 2, cz, MapPlan.b("dark_oak_planks"));
				set(level, o, cx + faces.getStepX(), 0, cz + faces.getStepZ(), MapPlan.b("red_carpet"));
			}
			wallSign(level, o.offset(bx + faces.getStepX(), 2, bz + faces.getStepZ()), faces,
				Component.translatable("halloween.lobby.st." + s.key()).withStyle(ChatFormatting.BOLD),
				Component.translatable("halloween.lobby.sign_hint"));
		}
		BlockPos practice = practiceButton(o);
		level.setBlock(practice, MapPlan.b("stone_button").setValue(ButtonBlock.FACE, AttachFace.WALL).setValue(ButtonBlock.FACING, Direction.SOUTH), Block.UPDATE_CLIENTS);
		// "Ready" in the middle of the court.
		set(level, o, W / 2, 0, D / 2, MapPlan.b("polished_blackstone"));
		level.setBlock(readyButton(o), MapPlan.b("stone_button").setValue(ButtonBlock.FACE, AttachFace.FLOOR).setValue(ButtonBlock.FACING, Direction.NORTH), Block.UPDATE_CLIENTS);
		BlockPos sign = o.offset(W / 2, 0, D / 2 - 1);
		level.setBlock(sign, MapPlan.b("dark_oak_sign").setValue(StandingSignBlock.ROTATION, 8), Block.UPDATE_CLIENTS);
		text(level, sign, Component.translatable("halloween.lobby.ready_sign").withStyle(ChatFormatting.BOLD), Component.translatable("halloween.lobby.ready_sign2"));
		// The parkour: a lime start pad on the court, blocks rising over it, a gold finish.
		List<BlockPos> path = path(o);
		for (int i = 0; i < path.size(); i++) {
			BlockState block = i == 0 ? MapPlan.b("lime_concrete") : i == path.size() - 1 ? MapPlan.b("gold_block")
				: MapPlan.b(PARKOUR_BLOCKS[i % PARKOUR_BLOCKS.length]);
			level.setBlock(path.get(i), block, Block.UPDATE_CLIENTS);
		}
		BlockPos parkourSign = path.getFirst().offset(-1, 1, 0);
		level.setBlock(parkourSign, MapPlan.b("dark_oak_sign").setValue(StandingSignBlock.ROTATION, 12), Block.UPDATE_CLIENTS);
		text(level, parkourSign, Component.translatable("halloween.lobby.parkour_sign").withStyle(ChatFormatting.BOLD), Component.translatable("halloween.lobby.parkour_sign2"));
		// Over the entrance.
		BlockPos title = o.offset(W / 2, 4, 0);
		wallSign(level, title, Direction.SOUTH, Component.literal("HADDONFIELD HIGH").withStyle(ChatFormatting.BOLD), Component.translatable("halloween.lobby.gym"));
	}

	private static void wallSign(ServerLevel level, BlockPos at, Direction faces, Component line1, Component line2) {
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

	private static void set(ServerLevel level, BlockPos o, int x, int y, int z, BlockState state) {
		level.setBlock(o.offset(x, y, z), state, Block.UPDATE_CLIENTS);
	}

	private static void fill(ServerLevel level, BlockPos o, int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
		for (BlockPos p : BlockPos.betweenClosed(o.offset(x0, y0, z0), o.offset(x1, y1, z1))) {
			level.setBlock(p, state, Block.UPDATE_CLIENTS);
		}
	}
}
