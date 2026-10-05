package dev.halloween.game;

import dev.halloween.HwBlocks;
import dev.halloween.HwConfig;
import dev.halloween.HwSounds;
import dev.halloween.Role;
import dev.halloween.net.HwNet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.Vec3;

/**
 * The four ways out, one of each in a random spot of its kind. Each lets exactly one civilian escape.
 * <ul>
 * <li>Cellar: bolt cutters or the padlock key open it; the Shape can slam it shut again.</li>
 * <li>Car: install the part, repair it with a kit, start it with the keys. The Shape can pull the part out.</li>
 * <li>Police: call from a phone, the van comes after a while and waits. The Shape cuts phone lines; a kit fixes them.</li>
 * <li>Gate: can be opened only near the end of the match.</li>
 * </ul>
 */
public final class Exits {
	public static final int CELLAR = 0;
	public static final int CAR = 1;
	public static final int POLICE = 2;
	public static final int GATE = 3;

	// Cellar / gate states.
	static final int CLOSED = 0;
	static final int OPEN = 1;
	static final int USED = 2;
	// Car flags.
	static final int CAR_PART = 1;
	static final int CAR_REPAIRED = 2;
	static final int CAR_GONE = 4;
	// Police exit as shown to players: can call now, on the way, van waiting, van gone, van used, find a body first.
	public static final int POLICE_IDLE = 0;
	public static final int POLICE_COMING = 1;
	public static final int POLICE_HERE = 2;
	public static final int POLICE_GONE = 3;
	public static final int POLICE_USED = 4;
	public static final int POLICE_NEED_BODY = 5;

	private static BlockPos cellar;
	private static int cellarState;
	private static BlockPos car;
	private static int carFlags;
	private static BlockPos police;
	/** Survivors the waiting van can still take (levels 1 and 3 each bring one seat), and how long it waits. */
	private static int vanSeats;
	private static int vanTimer;
	/** How the last van ended: {@link #POLICE_GONE} or {@link #POLICE_USED}, 0 if none came yet. */
	private static int vanOutcome;
	private static BlockPos gate;
	private static int gateState;
	private static final Set<BlockPos> CUT_PHONES = new HashSet<>();
	/** Blocks replaced by the police van, to restore when it leaves. */
	private static final Map<BlockPos, BlockState> VAN = new LinkedHashMap<>();
	/**
	 * Blocks replaced by the cellar, car and gate. They are built only at the spots picked for the match,
	 * so no dead exit stands on the map; the old blocks come back when the match is over.
	 */
	private static final Map<BlockPos, BlockState> PROPS = new LinkedHashMap<>();

	private Exits() {
	}

	// ---------------------------------------------------------------- setup

	public static void setup(ServerLevel level, RandomSource random) {
		clear(level);
		MapPoints points = MapPoints.get(level);
		cellar = pick(points.list(MapPoints.Type.CELLAR), random);
		car = pick(points.list(MapPoints.Type.CAR), random);
		police = pick(points.list(MapPoints.Type.POLICE), random);
		gate = pick(points.list(MapPoints.Type.GATE), random);
		if (cellar != null) {
			buildCellar(level, cellar);
		}
		if (car != null) {
			buildCar(level, car);
		}
		if (gate != null) {
			buildGate(level, gate, points.first(MapPoints.Type.CENTER));
		}
	}

	private static BlockPos pick(List<BlockPos> list, RandomSource random) {
		return list.isEmpty() ? null : list.get(random.nextInt(list.size()));
	}

	public static void clear(ServerLevel level) {
		removeVan(level);
		PROPS.forEach((pos, old) -> level.setBlock(pos, old, Block.UPDATE_ALL));
		PROPS.clear();
		cellar = car = police = gate = null;
		cellarState = gateState = CLOSED;
		carFlags = 0;
		vanSeats = 0;
		vanTimer = 0;
		vanOutcome = 0;
		CUT_PHONES.clear();
	}

	// ---------------------------------------------------------------- tick

	public static void tick(ServerLevel level) {
		HwConfig cfg = HwConfig.get();
		if (vanSeats > 0 && --vanTimer <= 0) {
			vanSeats = 0;
			vanOutcome = POLICE_GONE;
			removeVan(level);
			Match.announce(level.getServer(), Component.translatable("halloween.match.police_gone").withStyle(ChatFormatting.GRAY));
		}
	}

	// ---------------------------------------------------------------- interactions

	private static boolean near(BlockPos point, BlockPos clicked, double radius) {
		return point != null && Vec3.atCenterOf(point).distanceTo(Vec3.atCenterOf(clicked)) <= radius;
	}

	public static boolean isPhone(ServerLevel level, BlockPos pos) {
		return level.getBlockState(pos).is(HwBlocks.PHONE);
	}

	/** The car has its part in, isn't fixed yet, and this player carries a repair kit. */
	public static boolean canRepairCar(ServerPlayer player) {
		return car != null && (carFlags & CAR_PART) != 0 && (carFlags & CAR_REPAIRED) == 0 && (carFlags & CAR_GONE) == 0
			&& Inventory.has(player, HwItems.Kind.REPAIR_KIT);
	}

	/** Uses up the repair kit: the car is ready to start. */
	public static void repairCar(ServerPlayer player) {
		Inventory.consume(player, HwItems.Kind.REPAIR_KIT);
		carFlags |= CAR_REPAIRED;
		player.level().playSound(null, car, SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 0.6f, 1.1f);
		Match.score(player, HwConfig.get().scoreExitProgress, Component.translatable("halloween.score.car_repair"));
	}

	/** What holding use on {@code pos} would do for this player, or null (a hint may have been sent). */
	public static Holds.Hold resolve(ServerPlayer player, BlockPos pos) {
		HwConfig cfg = HwConfig.get();
		ServerLevel level = player.level();
		Role role = MatchManager.role(player);
		boolean civilian = role == Role.CIVILIAN;

		if (isPhone(level, pos)) {
			return phone(player, pos, role);
		}
		if (near(cellar, pos, 2.5)) {
			if (role == Role.SHAPE) {
				return cellarState == OPEN ? new Holds.Hold(pos, "halloween.hud.progress.close_cellar", true, cfg.closeCellarTicks,
					p -> cellarState == OPEN, p -> {
						cellarState = CLOSED;
						setNearby(level, cellar, false);
						level.playSound(null, cellar, SoundEvents.WOODEN_TRAPDOOR_CLOSE, SoundSource.BLOCKS, 2f, 0.6f);
					}) : null;
			}
			if (cellarState == CLOSED) {
				HwItems.Kind tool = Inventory.has(player, HwItems.Kind.PADLOCK_KEY) ? HwItems.Kind.PADLOCK_KEY
					: Inventory.has(player, HwItems.Kind.BOLT_CUTTERS) ? HwItems.Kind.BOLT_CUTTERS : null;
				if (tool == null) {
					hint(player, "halloween.hint.cellar_locked");
					return null;
				}
				int ticks = tool == HwItems.Kind.PADLOCK_KEY ? cfg.padlockKeyTicks : cfg.boltCuttersTicks;
				return new Holds.Hold(pos, "halloween.hud.progress.open_cellar", false, ticks,
					p -> cellarState == CLOSED && Inventory.has(p, tool), p -> {
						Inventory.consume(p, tool);
						cellarState = OPEN;
						setNearby(level, cellar, true);
						level.playSound(null, cellar, SoundEvents.CHAIN_BREAK, SoundSource.BLOCKS, 1.5f, 0.8f);
						Match.score(p, cfg.scoreExitProgress, Component.translatable("halloween.score.cellar_open"));
					}, true);
			}
			if (cellarState == OPEN && civilian) {
				return escapeHold(pos, "halloween.hud.progress.escape_cellar", p -> cellarState == OPEN, p -> {
					cellarState = USED;
					setNearby(level, cellar, false);
					Match.escape(p, Component.translatable("halloween.exit.cellar"));
				});
			}
			if (cellarState == OPEN) {
				hint(player, "halloween.hint.deputy_no_escape");
			}
			return null;
		}
		if (near(car, pos, 3.2)) {
			if ((carFlags & CAR_GONE) != 0) {
				return null;
			}
			if (role == Role.SHAPE) {
				return (carFlags & CAR_PART) != 0 ? new Holds.Hold(pos, "halloween.hud.progress.remove_part", true, cfg.removePartTicks,
					p -> (carFlags & CAR_PART) != 0 && (carFlags & CAR_GONE) == 0, p -> {
						carFlags &= ~(CAR_PART | CAR_REPAIRED);
						Vec3 c = Vec3.atCenterOf(car).add(0, 1, 0);
						level.addFreshEntity(new ItemEntity(level, c.x, c.y, c.z, HwItems.create(HwItems.Kind.CAR_PART)));
						level.playSound(null, car, SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 0.7f, 1.4f);
					}) : null;
			}
			if ((carFlags & CAR_PART) == 0) {
				if (!Inventory.has(player, HwItems.Kind.CAR_PART)) {
					hint(player, "halloween.hint.car_part");
					return null;
				}
				return new Holds.Hold(pos, "halloween.hud.progress.install_part", false, cfg.installPartTicks,
					p -> (carFlags & CAR_PART) == 0 && Inventory.has(p, HwItems.Kind.CAR_PART), p -> {
						Inventory.consume(p, HwItems.Kind.CAR_PART);
						carFlags |= CAR_PART;
						level.playSound(null, car, SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 0.6f, 1.3f);
						Match.score(p, cfg.scoreExitProgress, Component.translatable("halloween.score.car_part"));
					});
			}
			if ((carFlags & CAR_REPAIRED) == 0) {
				if (!Inventory.has(player, HwItems.Kind.REPAIR_KIT)) {
					hint(player, "halloween.hint.car_repair");
					return null;
				}
				if (!Bots.isBot(player)) {
					// People do it by hand on the panel; bots just take their time.
					Minigames.open(player, HwNet.Minigame.CURVE, car);
					return null;
				}
				return new Holds.Hold(pos, "halloween.hud.progress.repair_car", false, cfg.repairCarTicks, Exits::canRepairCar, Exits::repairCar);
			}
			if (!civilian) {
				hint(player, "halloween.hint.deputy_no_escape");
				return null;
			}
			if (!Inventory.has(player, HwItems.Kind.CAR_KEYS)) {
				hint(player, "halloween.hint.car_keys");
				return null;
			}
			return new Holds.Hold(pos, "halloween.hud.progress.start_car", true, cfg.startCarTicks,
				p -> (carFlags & CAR_REPAIRED) != 0 && (carFlags & CAR_GONE) == 0 && Inventory.has(p, HwItems.Kind.CAR_KEYS), p -> {
					Inventory.consume(p, HwItems.Kind.CAR_KEYS);
					carFlags |= CAR_GONE;
					level.playSound(null, car, SoundEvents.MINECART_RIDING, SoundSource.BLOCKS, 3f, 0.6f);
					level.playSound(null, car, SoundEvents.GOAT_HORN_SOUND_VARIANTS.get(0).value(), SoundSource.BLOCKS, 2f, 1.4f);
					Match.escape(p, Component.translatable("halloween.exit.car"));
				});
		}
		if (vanSeats > 0 && near(police, pos, 3.5)) {
			if (civilian) {
				return escapeHold(pos, "halloween.hud.progress.escape_police", p -> vanSeats > 0, p -> {
					takeSeat(level);
					Match.escape(p, Component.translatable("halloween.exit.police"));
				});
			}
			if (role == Role.DEPUTY) {
				hint(player, "halloween.hint.deputy_no_escape");
			}
			return null;
		}
		if (near(gate, pos, 2.5)) {
			if (role == Role.SHAPE) {
				return null;
			}
			if (gateState == CLOSED) {
				if (Match.ticksLeft() > cfg.gateOpenLastSeconds * 20) {
					hint(player, "halloween.hint.gate_later");
					return null;
				}
				return new Holds.Hold(pos, "halloween.hud.progress.open_gate", true, cfg.openGateTicks, p -> gateState == CLOSED, p -> {
					gateState = OPEN;
					setNearby(level, gate, true);
					level.playSound(null, gate, SoundEvents.IRON_DOOR_OPEN, SoundSource.BLOCKS, 3f, 0.5f);
					Match.score(p, cfg.scoreExitProgress, Component.translatable("halloween.score.gate_open"));
				});
			}
			if (gateState == OPEN && civilian) {
				return escapeHold(pos, "halloween.hud.progress.escape_gate", p -> gateState == OPEN, p -> {
					gateState = USED;
					setNearby(level, gate, false);
					Match.escape(p, Component.translatable("halloween.exit.gate"));
				});
			}
			if (gateState == OPEN) {
				hint(player, "halloween.hint.deputy_no_escape");
			}
		}
		return null;
	}

	private static Holds.Hold phone(ServerPlayer player, BlockPos pos, Role role) {
		HwConfig cfg = HwConfig.get();
		ServerLevel level = player.level();
		BlockPos phone = pos.immutable();
		boolean cut = CUT_PHONES.contains(phone);
		if (role == Role.SHAPE) {
			if (cut || Police.wanted() >= cfg.policeMaxCalls) {
				return null;
			}
			return new Holds.Hold(phone, "halloween.hud.progress.cut_phone", true, cfg.cutPhoneTicks,
				p -> !CUT_PHONES.contains(phone), p -> {
					CUT_PHONES.add(phone);
					level.playSound(null, phone, SoundEvents.SHEEP_SHEAR, SoundSource.BLOCKS, 1f, 0.6f);
				});
		}
		if (police == null) {
			hint(player, "halloween.hint.no_police");
			return null;
		}
		if (Police.wanted() >= cfg.policeMaxCalls) {
			hint(player, "halloween.hint.police_max");
			return null;
		}
		if (cut) {
			if (!Inventory.has(player, HwItems.Kind.REPAIR_KIT)) {
				hint(player, "halloween.hint.phone_cut");
				return null;
			}
			return new Holds.Hold(phone, "halloween.hud.progress.repair_phone", false, cfg.repairPhoneTicks,
				p -> CUT_PHONES.contains(phone) && Inventory.has(p, HwItems.Kind.REPAIR_KIT), p -> {
					Inventory.consume(p, HwItems.Kind.REPAIR_KIT);
					CUT_PHONES.remove(phone);
					Match.score(p, cfg.scoreExitProgress, Component.translatable("halloween.score.phone_repair"));
				});
		}
		if (!Police.callable()) {
			// Nothing to tell them yet: somebody has to find a body first.
			hint(player, "halloween.hint.police_no_body");
			return null;
		}
		return new Holds.Hold(phone, "halloween.hud.progress.call_police", false, cfg.callPoliceTicks,
			p -> !CUT_PHONES.contains(phone) && Police.callable(), p -> {
				Match.score(p, cfg.scoreCallPolice, Component.translatable("halloween.score.police_called"));
				callPolice(level, Component.translatable("halloween.match.police_called"));
			}, true);
	}

	/**
	 * A townsperson brought to an open exit leaves through it and uses it up, like a civilian: cellar open,
	 * police van waiting, gates open. Returns the exit's name, or null if there is no open exit here.
	 */
	public static Component npcEscape(ServerLevel level, Vec3 at, double radius) {
		if (cellar != null && cellarState == OPEN && Vec3.atCenterOf(cellar).distanceTo(at) <= radius) {
			cellarState = USED;
			setNearby(level, cellar, false);
			Match.markDirty();
			return Component.translatable("halloween.exit.cellar");
		}
		if (police != null && vanSeats > 0 && Vec3.atCenterOf(police).distanceTo(at) <= radius + 1) {
			takeSeat(level);
			Match.markDirty();
			return Component.translatable("halloween.exit.police");
		}
		if (gate != null && gateState == OPEN && Vec3.atCenterOf(gate).distanceTo(at) <= radius) {
			gateState = USED;
			setNearby(level, gate, false);
			Match.markDirty();
			return Component.translatable("halloween.exit.gate");
		}
		return null;
	}

	/** Is there an exit a townsperson could leave through near {@code at} (without using it up)? */
	public static boolean openExitNear(Vec3 at, double radius) {
		return cellar != null && cellarState == OPEN && Vec3.atCenterOf(cellar).distanceTo(at) <= radius
			|| police != null && vanSeats > 0 && Vec3.atCenterOf(police).distanceTo(at) <= radius + 1
			|| gate != null && gateState == OPEN && Vec3.atCenterOf(gate).distanceTo(at) <= radius;
	}

	/** Opens an exit straight away (map testing: /hw exit open, and tests). */
	public static void forceOpen(ServerLevel level, int type) {
		switch (type) {
			case CELLAR -> {
				if (cellar != null) {
					cellarState = OPEN;
					setNearby(level, cellar, true);
				}
			}
			case GATE -> {
				if (gate != null) {
					gateState = OPEN;
					setNearby(level, gate, true);
				}
			}
			case POLICE -> {
				vanArrives(level);
			}
			case CAR -> carFlags = CAR_PART | CAR_REPAIRED;
			default -> {
			}
		}
		Match.markDirty();
	}

	/** The police can be called now: they exist on this map and there is a found body nobody reported. */
	public static boolean policeCallable() {
		return police != null && Police.callable();
	}

	/** Where the police van parks and officers step out (null if this map has no police). */
	public static BlockPos policePoint() {
		return police;
	}

	/** A van pulls up (or the one waiting gets another seat): one more survivor can leave in it. */
	public static void vanArrives(ServerLevel level) {
		if (police == null) {
			return;
		}
		vanSeats++;
		vanTimer = HwConfig.get().policeStaySeconds * 20;
		if (VAN.isEmpty()) {
			buildVan(level, police);
		}
		level.playSound(null, police, SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 3f, 0.7f);
		Match.announce(level.getServer(), Component.translatable("halloween.match.police_here").withStyle(ChatFormatting.BLUE));
		Match.markDirty();
	}

	/** How many survivors the waiting van can still take (tests, debugging). */
	public static int vanSeats() {
		return vanSeats;
	}

	private static void takeSeat(ServerLevel level) {
		vanSeats--;
		if (vanSeats <= 0) {
			vanSeats = 0;
			vanOutcome = POLICE_USED;
			removeVan(level);
		}
	}

	public static boolean phoneWorks(BlockPos phone) {
		return !CUT_PHONES.contains(phone);
	}

	/** Someone got through on the phone: the wanted level goes up and the police set off. */
	public static void callPolice(ServerLevel level, Component announcement) {
		if (policeCallable()) {
			Police.reportCall(level, announcement);
		}
	}

	private static Holds.Hold escapeHold(BlockPos pos, String label, Holds.Check valid, Holds.Done done) {
		return new Holds.Hold(pos, label, false, HwConfig.get().escapeTicks, valid, done);
	}

	private static void hint(ServerPlayer player, String key) {
		player.sendOverlayMessage(Component.translatable(key).withStyle(ChatFormatting.GRAY));
	}

	// ---------------------------------------------------------------- props

	/** Opens or closes the trapdoors / fence gates that make up an exit. */
	private static void setNearby(ServerLevel level, BlockPos center, boolean open) {
		if (center == null) {
			return;
		}
		for (BlockPos p : BlockPos.betweenClosed(center.offset(-2, -1, -2), center.offset(2, 1, 2))) {
			BlockState state = level.getBlockState(p);
			if ((state.getBlock() instanceof TrapDoorBlock || state.getBlock() instanceof FenceGateBlock) && state.getValue(BlockStateProperties.OPEN) != open) {
				level.setBlock(p, state.setValue(BlockStateProperties.OPEN, open), Block.UPDATE_CLIENTS);
			}
		}
	}

	private static BlockState block(String id) {
		return BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(id)).defaultBlockState();
	}

	/** A boxy police van parked on the spot, with a lit lamp on the roof. */
	private static void buildVan(ServerLevel level, BlockPos at) {
		removeVan(level);
		BlockState body = block("white_concrete");
		BlockState dark = block("black_concrete");
		BlockState window = block("black_terracotta");
		BlockState lamp = block("redstone_lamp").setValue(BlockStateProperties.LIT, true);
		BlockState blue = block("blue_concrete");
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				place(level, at.offset(dx, 0, dz), Math.abs(dz) == 2 && dx != 0 ? dark : body);
				place(level, at.offset(dx, 1, dz), dz == -2 ? window : (dx == 0 ? blue : body));
			}
		}
		place(level, at.offset(0, 2, -1), lamp);
		place(level, at.offset(0, 2, 0), block("blue_concrete"));
	}

	private static void place(ServerLevel level, BlockPos pos, BlockState state) {
		place(level, pos, state, VAN);
	}

	private static void place(ServerLevel level, BlockPos pos, BlockState state, Map<BlockPos, BlockState> undo) {
		undo.putIfAbsent(pos.immutable(), level.getBlockState(pos));
		level.setBlock(pos, state, Block.UPDATE_ALL);
	}

	/** Exit hatches and gates can't be opened by hand, only by the exit's own actions. */
	public static boolean isProp(BlockPos pos) {
		return PROPS.containsKey(pos);
	}

	/**
	 * A storm cellar: two pairs of trapdoors in the ground in front of {@code at} (where a player stands),
	 * framed in stone, over a solid dark floor, so an open hatch is a shallow hole and not a way under the map.
	 */
	private static void buildCellar(ServerLevel level, BlockPos at) {
		BlockPos ground = at.below();
		for (int dx = -1; dx <= 2; dx++) {
			for (int dz = -1; dz <= 2; dz++) {
				boolean hatch = dx >= 0 && dx <= 1 && dz >= 0 && dz <= 1;
				place(level, ground.offset(dx, -1, dz), block(hatch ? "black_concrete" : "stone_bricks"), PROPS);
				if (!hatch) {
					place(level, ground.offset(dx, 0, dz), block("stone_bricks"), PROPS);
				}
			}
		}
		BlockState door = block("spruce_trapdoor").setValue(BlockStateProperties.HALF, Half.TOP);
		place(level, ground, door.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH), PROPS);
		place(level, ground.east(), door.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH), PROPS);
		place(level, ground.south(), door.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH), PROPS);
		place(level, ground.south().east(), door.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH), PROPS);
	}

	/** A boxy car five blocks long along X, standing on the ground at {@code at}. */
	private static void buildCar(ServerLevel level, BlockPos at) {
		BlockState body = block("red_concrete");
		BlockState wheel = block("black_concrete");
		// Not real glass: car windows must not count as windows to climb through.
		BlockState glass = block("black_terracotta");
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = 0; dz <= 1; dz++) {
				place(level, at.offset(dx, 0, dz), Math.abs(dx) == 2 ? wheel : body, PROPS);
				place(level, at.offset(dx, 1, dz), Math.abs(dx) == 2 ? body : glass, PROPS);
				if (Math.abs(dx) <= 1) {
					place(level, at.offset(dx, 2, dz), body, PROPS);
				}
			}
		}
	}

	/** A double fence gate at {@code at}, set across the line that faces the middle of the map. */
	private static void buildGate(ServerLevel level, BlockPos at, BlockPos center) {
		int dx = center == null ? 0 : center.getX() - at.getX();
		int dz = center == null ? 1 : center.getZ() - at.getZ();
		boolean alongX = Math.abs(dz) >= Math.abs(dx);
		Direction facing = alongX ? (dz < 0 ? Direction.NORTH : Direction.SOUTH) : (dx < 0 ? Direction.WEST : Direction.EAST);
		BlockState state = block("spruce_fence_gate").setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
		place(level, at, state, PROPS);
		place(level, alongX ? at.east() : at.south(), state, PROPS);
	}

	private static void removeVan(ServerLevel level) {
		VAN.forEach((pos, old) -> level.setBlock(pos, old, Block.UPDATE_ALL));
		VAN.clear();
	}

	// ---------------------------------------------------------------- sync

	public static List<HwNet.ExitInfo> infos() {
		List<HwNet.ExitInfo> list = new ArrayList<>();
		if (cellar != null) {
			list.add(new HwNet.ExitInfo(CELLAR, cellar, cellarState, 0));
		}
		if (car != null) {
			list.add(new HwNet.ExitInfo(CAR, car, carFlags, 0));
		}
		if (police != null) {
			list.add(policeInfo());
		}
		if (gate != null) {
			list.add(new HwNet.ExitInfo(GATE, gate, gateState, 0));
		}
		return list;
	}

	/** The police line of the exits list: the van waiting beats police on the way beats whether you can call. */
	private static HwNet.ExitInfo policeInfo() {
		HwConfig cfg = HwConfig.get();
		int coming = Police.nextArrival();
		if (vanSeats > 0) {
			return new HwNet.ExitInfo(POLICE, police, POLICE_HERE, vanTimer);
		}
		if (coming >= 0) {
			return new HwNet.ExitInfo(POLICE, police, POLICE_COMING, coming);
		}
		if (Police.wanted() < cfg.policeMaxCalls) {
			return new HwNet.ExitInfo(POLICE, police, Police.callable() ? POLICE_IDLE : POLICE_NEED_BODY, 0);
		}
		return new HwNet.ExitInfo(POLICE, police, vanOutcome == 0 ? POLICE_GONE : vanOutcome, 0);
	}

	public static List<BlockPos> cutPhones() {
		return new ArrayList<>(CUT_PHONES);
	}

	/** Exits still able to take someone (for the end-of-match check). */
	public static boolean anyLeft() {
		return cellar != null && cellarState != USED || car != null && (carFlags & CAR_GONE) == 0
			|| police != null && (vanSeats > 0 || Police.vanStillPossible()) || gate != null && gateState != USED;
	}
}
