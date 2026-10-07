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
 * <li>Cellars (two a match): bolt cutters or the padlock key open one; the Shape can slam it shut again.</li>
 * <li>Car (the sedan): repair the engine with a kit, pour in the gas can, start it with the sedan keys. The
 * Shape can drain the tank (the can drops by the car).</li>
 * <li>Police: call from a phone, the van comes after a while and waits. The Shape cuts phone lines; a kit fixes them.</li>
 * <li>Gate: a fuse brings its keypad to life, then the gate key opens the chain.</li>
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
	static final int CAR_FUEL = 1;
	static final int CAR_REPAIRED = 2;
	static final int CAR_GONE = 4;
	/** Started: the engine runs and the car waits a moment for a second survivor and for townsfolk. */
	static final int CAR_RUNNING = 8;
	// Police exit as shown to players: can call now, on the way, van waiting, van gone, van used, find a body first.
	public static final int POLICE_IDLE = 0;
	public static final int POLICE_COMING = 1;
	public static final int POLICE_HERE = 2;
	public static final int POLICE_GONE = 3;
	public static final int POLICE_USED = 4;
	public static final int POLICE_NEED_BODY = 5;

	/** A storm cellar of this match and its state. */
	private static final class Cellar {
		final BlockPos pos;
		int state = CLOSED;

		Cellar(BlockPos pos) {
			this.pos = pos;
		}
	}

	private static final List<Cellar> CELLARS = new ArrayList<>();
	private static BlockPos car;
	private static int carFlags;
	/**
	 * Who sits in the running car: the driver (the first in) and the passengers, the game mode each had before
	 * (they sit as spectators held in their seat); the engine's ticks; the blocks it stands on (it drives off).
	 */
	private static final List<java.util.UUID> SEATED = new ArrayList<>();
	private static final Map<java.util.UUID, net.minecraft.world.level.GameType> SEATED_MODE = new java.util.HashMap<>();
	private static int carTimer;
	/** How long a bot at the wheel has waited for the others. */
	private static int botWait;
	private static final List<BlockPos> CAR_BLOCKS = new ArrayList<>();
	private static BlockPos police;
	/** Survivors the waiting van can still take (levels 1 and 3 each bring one seat), and how long it waits. */
	private static int vanSeats;
	private static int vanTimer;
	/** How the last van ended: {@link #POLICE_GONE} or {@link #POLICE_USED}, 0 if none came yet. */
	private static int vanOutcome;
	private static BlockPos gate;
	private static int gateState;
	/** A fuse is in the gate's keypad. */
	private static boolean gateFuse;
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
		List<BlockPos> cellarSpots = new ArrayList<>(points.list(MapPoints.Type.CELLAR));
		for (int i = 0; i < HwConfig.get().cellarsPerMatch && !cellarSpots.isEmpty(); i++) {
			CELLARS.add(new Cellar(cellarSpots.remove(random.nextInt(cellarSpots.size()))));
		}
		car = pick(points.list(MapPoints.Type.CAR), random);
		police = pick(points.list(MapPoints.Type.POLICE), random);
		gate = pick(points.list(MapPoints.Type.GATE), random);
		for (Cellar c : CELLARS) {
			buildCellar(level, c.pos);
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
		CELLARS.clear();
		car = police = gate = null;
		gateState = CLOSED;
		gateFuse = false;
		carFlags = 0;
		// Anyone still sitting in the car gets their game mode back (the match is over or starting afresh).
		for (java.util.UUID id : SEATED) {
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
			net.minecraft.world.level.GameType mode = SEATED_MODE.get(id);
			if (p != null && mode != null && p.isSpectator()) {
				p.setGameMode(mode);
			}
		}
		SEATED.clear();
		SEATED_MODE.clear();
		carTimer = 0;
		botWait = 0;
		CAR_BLOCKS.clear();
		vanSeats = 0;
		vanTimer = 0;
		vanOutcome = 0;
		CUT_PHONES.clear();
	}

	// ---------------------------------------------------------------- tick

	public static void tick(ServerLevel level) {
		HwConfig cfg = HwConfig.get();
		if ((carFlags & CAR_RUNNING) != 0 && (carFlags & CAR_GONE) == 0) {
			if (carTimer++ % 20 == 0) {
				level.playSound(null, car, SoundEvents.MINECART_RIDING, SoundSource.BLOCKS, 1.2f, 0.5f);
			}
			tickSeats(level);
		}
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

	/** The car isn't fixed yet, and this player carries a repair kit. */
	public static boolean canRepairCar(ServerPlayer player) {
		return car != null && (carFlags & CAR_REPAIRED) == 0 && (carFlags & CAR_GONE) == 0
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
		for (Cellar c : CELLARS) {
			if (near(c.pos, pos, 2.5)) {
				return cellar(player, pos, c, role, civilian);
			}
		}
		return resolveOthers(player, pos, role, civilian);
	}

	private static Holds.Hold cellar(ServerPlayer player, BlockPos pos, Cellar c, Role role, boolean civilian) {
		HwConfig cfg = HwConfig.get();
		ServerLevel level = player.level();
		BlockPos cellar = c.pos;
		{
			if (role == Role.SHAPE) {
				return c.state == OPEN ? new Holds.Hold(pos, "halloween.hud.progress.close_cellar", true, cfg.closeCellarTicks,
					p -> c.state == OPEN, p -> {
						c.state = CLOSED;
						setNearby(level, cellar, false);
						level.playSound(null, cellar, SoundEvents.WOODEN_TRAPDOOR_CLOSE, SoundSource.BLOCKS, 2f, 0.6f);
					}) : null;
			}
			if (c.state == CLOSED) {
				HwItems.Kind tool = Inventory.has(player, HwItems.Kind.PADLOCK_KEY) ? HwItems.Kind.PADLOCK_KEY
					: Inventory.has(player, HwItems.Kind.BOLT_CUTTERS) ? HwItems.Kind.BOLT_CUTTERS : null;
				if (tool == null) {
					hint(player, "halloween.hint.cellar_locked");
					return null;
				}
				int ticks = tool == HwItems.Kind.PADLOCK_KEY ? cfg.padlockKeyTicks : cfg.boltCuttersTicks;
				return new Holds.Hold(pos, "halloween.hud.progress.open_cellar", false, ticks,
					p -> c.state == CLOSED && Inventory.has(p, tool), p -> {
						Inventory.consume(p, tool);
						c.state = OPEN;
						setNearby(level, cellar, true);
						level.playSound(null, cellar, SoundEvents.CHAIN_BREAK, SoundSource.BLOCKS, 1.5f, 0.8f);
						Match.score(p, cfg.scoreExitProgress, Component.translatable("halloween.score.cellar_open"));
						Match.chronicle(level.getServer(), Component.translatable("halloween.chronicle.cellar_open", p.getDisplayName()).withStyle(ChatFormatting.GREEN));
					}, true);
			}
			if (c.state == OPEN && civilian) {
				return escapeHold(pos, "halloween.hud.progress.escape_cellar", p -> c.state == OPEN, p -> {
					c.state = USED;
					setNearby(level, cellar, false);
					Match.escape(p, Component.translatable("halloween.exit.cellar"));
				});
			}
			if (c.state == OPEN) {
				hint(player, "halloween.hint.deputy_no_escape");
			}
			return null;
		}
	}

	private static Holds.Hold resolveOthers(ServerPlayer player, BlockPos pos, Role role, boolean civilian) {
		HwConfig cfg = HwConfig.get();
		ServerLevel level = player.level();
		if (near(car, pos, 3.2)) {
			if ((carFlags & CAR_GONE) != 0) {
				return null;
			}
			if (role == Role.SHAPE) {
				// He drains the tank: the can is thrown down by the car, to be poured in again.
				return (carFlags & CAR_FUEL) != 0 && (carFlags & CAR_RUNNING) == 0 ? new Holds.Hold(pos, "halloween.hud.progress.drain_fuel", true, cfg.removePartTicks,
					p -> (carFlags & CAR_FUEL) != 0 && (carFlags & (CAR_GONE | CAR_RUNNING)) == 0, p -> {
						carFlags &= ~CAR_FUEL;
						Vec3 c = Vec3.atCenterOf(car).add(0, 1, 0);
						level.addFreshEntity(new ItemEntity(level, c.x, c.y, c.z, HwItems.create(HwItems.Kind.GAS_CAN)));
						level.playSound(null, car, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 1f, 0.7f);
					}) : null;
			}
			if ((carFlags & CAR_RUNNING) != 0) {
				// The engine runs: survivors get in (the first is the driver) while there are seats.
				if (civilian && SEATED.size() < cfg.carSeats && !SEATED.contains(player.getUUID())) {
					return escapeHold(pos, "halloween.hud.progress.escape_car",
						p -> SEATED.size() < HwConfig.get().carSeats && (carFlags & CAR_GONE) == 0 && !SEATED.contains(p.getUUID()), p -> board(level, p));
				}
				if (!civilian) {
					hint(player, "halloween.hint.deputy_no_escape");
				}
				return null;
			}
			boolean repaired = (carFlags & CAR_REPAIRED) != 0;
			boolean fuelled = (carFlags & CAR_FUEL) != 0;
			// The engine first if we can, else the tank; either order works.
			if (!repaired && Inventory.has(player, HwItems.Kind.REPAIR_KIT)) {
				if (!Bots.isBot(player)) {
					// People do it by hand on the panel; bots just take their time.
					Minigames.open(player, HwNet.Minigame.CURVE, car);
					return null;
				}
				return new Holds.Hold(pos, "halloween.hud.progress.repair_car", false, cfg.repairCarTicks, Exits::canRepairCar, Exits::repairCar);
			}
			if (!fuelled && Inventory.has(player, HwItems.Kind.GAS_CAN)) {
				return new Holds.Hold(pos, "halloween.hud.progress.refuel", false, cfg.refuelTicks,
					p -> (carFlags & CAR_FUEL) == 0 && (carFlags & CAR_GONE) == 0 && Inventory.has(p, HwItems.Kind.GAS_CAN), p -> {
						Inventory.consume(p, HwItems.Kind.GAS_CAN);
						carFlags |= CAR_FUEL;
						level.playSound(null, car, SoundEvents.BUCKET_FILL, SoundSource.BLOCKS, 1f, 0.8f);
						Match.score(p, cfg.scoreExitProgress, Component.translatable("halloween.score.car_fuel"));
					});
			}
			if (!repaired) {
				hint(player, "halloween.hint.car_repair");
				return null;
			}
			if (!fuelled) {
				hint(player, "halloween.hint.car_fuel");
				return null;
			}
			if (!civilian) {
				hint(player, "halloween.hint.deputy_no_escape");
				return null;
			}
			if (!Inventory.has(player, HwItems.Kind.SEDAN_KEYS)) {
				hint(player, "halloween.hint.car_keys");
				return null;
			}
			return new Holds.Hold(pos, "halloween.hud.progress.start_car", true, cfg.startCarTicks,
				p -> (carFlags & CAR_REPAIRED) != 0 && (carFlags & CAR_FUEL) != 0 && (carFlags & (CAR_GONE | CAR_RUNNING)) == 0
					&& Inventory.has(p, HwItems.Kind.SEDAN_KEYS), p -> {
					// The engine starts and runs: nobody is in yet. It waits until the driver (the first to get in)
					// says go, or both seats are taken; townsfolk brought up to it get in meanwhile.
					Inventory.consume(p, HwItems.Kind.SEDAN_KEYS);
					carFlags |= CAR_RUNNING;
					carTimer = 0;
					botWait = 0;
					level.playSound(null, car, SoundEvents.MINECART_RIDING, SoundSource.BLOCKS, 3f, 0.6f);
					Match.announce(level.getServer(), Component.translatable("halloween.match.car_running").withStyle(net.minecraft.ChatFormatting.GREEN));
					Match.markDirty();
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
				if (cfg.gateOpenLastSeconds > 0 && Match.ticksLeft() > cfg.gateOpenLastSeconds * 20) {
					hint(player, "halloween.hint.gate_later");
					return null;
				}
				if (!gateFuse) {
					// The keypad is dead: a fuse brings it to life.
					if (!Inventory.has(player, HwItems.Kind.FUSE)) {
						hint(player, "halloween.hint.gate_fuse");
						return null;
					}
					return new Holds.Hold(pos, "halloween.hud.progress.install_fuse", false, cfg.installFuseTicks,
						p -> !gateFuse && gateState == CLOSED && Inventory.has(p, HwItems.Kind.FUSE), p -> {
							Inventory.consume(p, HwItems.Kind.FUSE);
							gateFuse = true;
							level.playSound(null, gate, SoundEvents.COPPER_BULB_TURN_ON, SoundSource.BLOCKS, 1.5f, 0.8f);
							level.playSound(null, gate, SoundEvents.NOTE_BLOCK_BIT.value(), SoundSource.BLOCKS, 1f, 1.6f);
							Match.score(p, cfg.scoreExitProgress, Component.translatable("halloween.score.gate_fuse"));
							Match.markDirty();
						});
				}
				if (!Inventory.has(player, HwItems.Kind.GATE_KEY)) {
					hint(player, "halloween.hint.gate_key");
					return null;
				}
				return new Holds.Hold(pos, "halloween.hud.progress.open_gate", true, cfg.openGateTicks,
					p -> gateState == CLOSED && gateFuse && Inventory.has(p, HwItems.Kind.GATE_KEY), p -> {
					Inventory.consume(p, HwItems.Kind.GATE_KEY);
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

	/** Someone gets into the running car: they sit (held in their seat as a spectator, out of reach). */
	private static void board(ServerLevel level, ServerPlayer p) {
		SEATED.add(p.getUUID());
		SEATED_MODE.put(p.getUUID(), p.gameMode.getGameModeForPlayer());
		p.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
		level.playSound(null, car, SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 1f, 1.2f);
		AiStats.add(p, SEATED.size() == 1 ? "car.driver" : "car.passenger");
		Match.markDirty();
		if (SEATED.size() >= HwConfig.get().carSeats) {
			// Every seat taken: off we go.
			driveOff(level);
		}
	}

	/** Out of the car again (to fetch someone): on the side of it where there is room. */
	private static void getOut(ServerLevel level, ServerPlayer p) {
		SEATED.remove(p.getUUID());
		net.minecraft.world.level.GameType mode = SEATED_MODE.remove(p.getUUID());
		p.setGameMode(mode == null ? net.minecraft.world.level.GameType.ADVENTURE : mode);
		Vec3 c = Vec3.atBottomCenterOf(car);
		Vec3 out = c.add(0, 0, 3);
		for (int i = 0; i < 8; i++) {
			double a = i * Math.PI / 4;
			Vec3 at = c.add(Math.cos(a) * 3, 0, Math.sin(a) * 3);
			if (level.noCollision(new net.minecraft.world.phys.AABB(at.x - 0.3, at.y, at.z - 0.3, at.x + 0.3, at.y + 1.8, at.z + 0.3))) {
				out = at;
				break;
			}
		}
		p.teleportTo(level, out.x, out.y, out.z, Set.of(net.minecraft.world.entity.Relative.X_ROT, net.minecraft.world.entity.Relative.Y_ROT), 0f, 0f, false);
		level.playSound(null, car, SoundEvents.IRON_DOOR_OPEN, SoundSource.BLOCKS, 1f, 1.2f);
		AiStats.add(p, "car.out");
		Match.markDirty();
	}

	/**
	 * The people in the car stay in their seats. The driver (a person) jumps to drive off, anyone sneaks to get
	 * out; a bot at the wheel waits a while for the others and then goes.
	 */
	private static void tickSeats(ServerLevel level) {
		SEATED.removeIf(id -> {
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
			return p == null || !MatchManager.role(p).survivor();
		});
		if (SEATED.isEmpty()) {
			botWait = 0;
			return;
		}
		Vec3 seat = Vec3.atBottomCenterOf(car).add(0, 0.3, 0);
		for (int i = 0; i < SEATED.size(); i++) {
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(SEATED.get(i));
			if (p.position().distanceToSqr(seat) > 0.01) {
				p.teleportTo(level, seat.x, seat.y, seat.z, Set.of(net.minecraft.world.entity.Relative.X_ROT, net.minecraft.world.entity.Relative.Y_ROT), 0f, 0f, false);
			}
			p.setDeltaMovement(Vec3.ZERO);
			if (Bots.isBot(p)) {
				continue;
			}
			net.minecraft.world.entity.player.Input input = p.getLastClientInput();
			if (input.shift()) {
				getOut(level, p);
				return;
			}
			if (i == 0 && input.jump()) {
				driveOff(level);
				return;
			}
			if (level.getGameTime() % 20 == 0) {
				p.sendOverlayMessage(Component.translatable(i == 0 ? "halloween.hint.car_driver" : "halloween.hint.car_passenger").withStyle(ChatFormatting.GREEN));
			}
		}
		ServerPlayer driver = level.getServer().getPlayerList().getPlayer(SEATED.getFirst());
		if (driver != null && Bots.isBot(driver) && ++botWait >= HwConfig.get().carWaitTicks) {
			driveOff(level);
		}
	}

	/** Sitting in the running car (out of the match's reach until it goes). */
	public static boolean isSeated(net.minecraft.world.entity.Entity entity) {
		return SEATED.contains(entity.getUUID());
	}

	/** For tests: who sits in the car, the driver first. */
	public static List<java.util.UUID> seated() {
		return new ArrayList<>(SEATED);
	}

	/** The car drives off with everyone in it: horn, engine, and it is gone from where it stood. */
	private static void driveOff(ServerLevel level) {
		carFlags |= CAR_GONE;
		List<java.util.UUID> riders = new ArrayList<>(SEATED);
		SEATED.clear();
		for (java.util.UUID id : riders) {
			SEATED_MODE.remove(id);
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
			if (p != null) {
				Match.escape(p, Component.translatable("halloween.exit.car"));
			}
		}
		level.playSound(null, car, SoundEvents.GOAT_HORN_SOUND_VARIANTS.get(0).value(), SoundSource.BLOCKS, 2f, 1.4f);
		level.playSound(null, car, SoundEvents.MINECART_RIDING, SoundSource.BLOCKS, 3f, 0.8f);
		for (BlockPos p : CAR_BLOCKS) {
			BlockState old = PROPS.remove(p);
			if (old != null) {
				level.setBlock(p, old, Block.UPDATE_ALL);
			}
		}
		CAR_BLOCKS.clear();
		Vec3 c = Vec3.atCenterOf(car);
		level.sendParticles(net.minecraft.core.particles.ParticleTypes.LARGE_SMOKE, c.x, c.y, c.z, 30, 1.5, 0.5, 1.0, 0.02);
		Match.markDirty();
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
		for (Cellar c : CELLARS) {
			if (c.state == OPEN && Vec3.atCenterOf(c.pos).distanceTo(at) <= radius) {
				c.state = USED;
				setNearby(level, c.pos, false);
				Match.markDirty();
				return Component.translatable("halloween.exit.cellar");
			}
		}
		if (police != null && vanSeats > 0 && Vec3.atCenterOf(police).distanceTo(at) <= radius + 1) {
			takeSeat(level);
			Match.markDirty();
			return Component.translatable("halloween.exit.police");
		}
		if (carWaiting() && Vec3.atCenterOf(car).distanceTo(at) <= radius + 2) {
			return Component.translatable("halloween.exit.car");
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
		return CELLARS.stream().anyMatch(c -> c.state == OPEN && Vec3.atCenterOf(c.pos).distanceTo(at) <= radius)
			|| police != null && vanSeats > 0 && Vec3.atCenterOf(police).distanceTo(at) <= radius + 1
			|| carWaiting() && Vec3.atCenterOf(car).distanceTo(at) <= radius + 2
			|| gate != null && gateState == OPEN && Vec3.atCenterOf(gate).distanceTo(at) <= radius;
	}

	/** An exit to draw on the town map boards: a storm cellar, or the gate. */
	public record Marker(BlockPos pos, boolean cellar) {
	}

	/** This match's storm cellars and gate (where they are, whatever their state). */
	public static List<Marker> markers() {
		List<Marker> list = new ArrayList<>();
		for (Cellar c : CELLARS) {
			list.add(new Marker(c.pos, true));
		}
		if (gate != null) {
			list.add(new Marker(gate, false));
		}
		return list;
	}

	/** The car's engine runs and it hasn't left yet. */
	public static boolean carWaiting() {
		return car != null && (carFlags & CAR_RUNNING) != 0 && (carFlags & CAR_GONE) == 0;
	}

	/** Opens an exit straight away (map testing: /hw exit open, and tests). */
	public static void forceOpen(ServerLevel level, int type) {
		switch (type) {
			case CELLAR -> {
				for (Cellar c : CELLARS) {
					c.state = OPEN;
					setNearby(level, c.pos, true);
				}
			}
			case GATE -> {
				if (gate != null) {
					gateFuse = true;
					gateState = OPEN;
					setNearby(level, gate, true);
				}
			}
			case POLICE -> {
				vanArrives(level);
			}
			case CAR -> carFlags = CAR_FUEL | CAR_REPAIRED;
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
	/**
	 * A 1970s sedan, seven long and three wide: copper-brown body, black wheels showing at the sides,
	 * chrome bumpers, headlights and tail lights, a low hood and trunk, dark windows (not glass: nobody
	 * climbs through a car) and a vinyl roof.
	 */
	private static void buildCar(ServerLevel level, BlockPos at) {
		BlockState body = block("waxed_cut_copper");
		BlockState low = block("waxed_cut_copper_slab");
		BlockState roof = block("polished_blackstone_slab");
		BlockState window = block("cyan_terracotta");
		BlockState wheel = block("black_concrete");
		BlockState chrome = block("light_gray_concrete");
		for (int u = -3; u <= 3; u++) {
			for (int w = -1; w <= 1; w++) {
				BlockPos p = at.offset(u, 0, w);
				boolean side = Math.abs(w) == 1;
				BlockState bottom;
				if (Math.abs(u) == 3) {
					// Bumpers, with headlights at the front and tail lights at the back.
					bottom = side ? block(u > 0 ? "white_concrete" : "red_concrete") : chrome;
				} else if (Math.abs(u) == 2 && side) {
					bottom = wheel;
				} else {
					bottom = body;
				}
				place(level, p, bottom, PROPS);
				CAR_BLOCKS.add(p.immutable());
				CAR_BLOCKS.add(p.above().immutable());
				CAR_BLOCKS.add(p.above(2).immutable());
				if (u >= 2 || u == -3) {
					// Hood and trunk, low.
					place(level, p.above(), low, PROPS);
				} else {
					place(level, p.above(), side || u == 1 || u == -2 ? window : body, PROPS);
					place(level, p.above(2), roof, PROPS);
				}
			}
		}
	}

	/** A double fence gate at {@code at}, set across the line that faces the middle of the map. */
	/**
	 * A gate out of town big enough that nobody mistakes it: stone pillars with lanterns on top, a beam
	 * across with an EXIT sign facing town, iron bars over a double row of gates five wide. It faces the
	 * centre and replaces whatever closed the way there (fence, barricade, invisible wall) until the match ends.
	 */
	private static void buildGate(ServerLevel level, BlockPos at, BlockPos center) {
		int dx = center == null ? 0 : center.getX() - at.getX();
		int dz = center == null ? 1 : center.getZ() - at.getZ();
		boolean alongX = Math.abs(dz) >= Math.abs(dx);
		Direction facing = alongX ? (dz < 0 ? Direction.NORTH : Direction.SOUTH) : (dx < 0 ? Direction.WEST : Direction.EAST);
		Direction along = alongX ? Direction.EAST : Direction.SOUTH;
		BlockState leaf = block("dark_oak_fence_gate").setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
		BlockState pillar = block("stone_bricks");
		BlockState beam = block("dark_oak_log").setValue(BlockStateProperties.AXIS, alongX ? Direction.Axis.X : Direction.Axis.Z);
		for (int u = -3; u <= 3; u++) {
			BlockPos column = at.relative(along, u);
			if (Math.abs(u) == 3) {
				for (int y = 0; y <= 4; y++) {
					place(level, column.above(y), pillar, PROPS);
				}
				place(level, column.above(5), block("stone_brick_wall"), PROPS);
				place(level, column.above(6), block("lantern"), PROPS);
			} else {
				place(level, column, leaf, PROPS);
				place(level, column.above(), leaf, PROPS);
				place(level, column.above(4), beam, PROPS);
			}
		}
		// Bars between the gates and the beam, joined up to each other and the pillars.
		for (int u = -2; u <= 2; u++) {
			for (int y = 2; y <= 3; y++) {
				BlockPos pos = at.relative(along, u).above(y);
				place(level, pos, block("iron_bars"), PROPS);
			}
		}
		for (int u = -2; u <= 2; u++) {
			for (int y = 2; y <= 3; y++) {
				BlockPos pos = at.relative(along, u).above(y);
				level.setBlock(pos, Block.updateFromNeighbourShapes(level.getBlockState(pos), level, pos), Block.UPDATE_CLIENTS);
			}
		}
		// EXIT, on the beam, towards town; red banners on the pillars.
		BlockPos signPos = at.above(4).relative(facing);
		place(level, signPos, block("dark_oak_wall_sign").setValue(BlockStateProperties.HORIZONTAL_FACING, facing), PROPS);
		if (level.getBlockEntity(signPos) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign) {
			net.minecraft.world.level.block.entity.SignText text = new net.minecraft.world.level.block.entity.SignText()
				.setMessage(1, Component.translatable("halloween.sign.exit"))
				.setMessage(2, Component.translatable("halloween.sign.exit_town"))
				.setColor(net.minecraft.world.item.DyeColor.WHITE)
				.setHasGlowingText(true);
			sign.setText(text, true);
			sign.setText(text, false);
			sign.setChanged();
			level.sendBlockUpdated(signPos, level.getBlockState(signPos), level.getBlockState(signPos), Block.UPDATE_CLIENTS);
		}
		for (int u : new int[]{-3, 3}) {
			BlockPos banner = at.relative(along, u).above(3).relative(facing);
			if (level.getBlockState(banner).isAir()) {
				place(level, banner, block("red_wall_banner").setValue(BlockStateProperties.HORIZONTAL_FACING, facing), PROPS);
			}
		}
	}

	private static void removeVan(ServerLevel level) {
		VAN.forEach((pos, old) -> level.setBlock(pos, old, Block.UPDATE_ALL));
		VAN.clear();
	}

	// ---------------------------------------------------------------- sync

	public static List<HwNet.ExitInfo> infos() {
		List<HwNet.ExitInfo> list = new ArrayList<>();
		for (Cellar c : CELLARS) {
			list.add(new HwNet.ExitInfo(CELLAR, c.pos, c.state, 0));
		}
		if (car != null) {
			list.add(new HwNet.ExitInfo(CAR, car, carFlags, carWaiting() ? Math.max(0, HwConfig.get().carSeats - SEATED.size()) : 0));
		}
		if (police != null) {
			list.add(policeInfo());
		}
		if (gate != null) {
			list.add(new HwNet.ExitInfo(GATE, gate, gateState, gateFuse ? 1 : 0));
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
		return CELLARS.stream().anyMatch(c -> c.state != USED) || car != null && (carFlags & CAR_GONE) == 0
			|| police != null && (vanSeats > 0 || Police.vanStillPossible()) || gate != null && gateState != USED;
	}
}
