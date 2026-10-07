package dev.halloween.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Builds the slice-1 test ground: a dark street with three small houses (bulb lights, breaker
 * box, windows), street lamps, bushes and three victim mannequins. Not a real map.
 */
public final class ArenaBuilder {
	private static final int HALF = 36;
	private static final int HEIGHT = 14;

	private final ServerLevel level;
	private final int ox;
	private final int oy;
	private final int oz;
	private final List<BlockPos> shapeFix = new ArrayList<>();

	private ArenaBuilder(ServerLevel level, BlockPos origin) {
		this.level = level;
		this.ox = origin.getX();
		this.oy = origin.getY();
		this.oz = origin.getZ();
	}

	private static BlockState b(String id) {
		return BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(id)).defaultBlockState();
	}

	private void set(int x, int y, int z, BlockState state) {
		BlockPos pos = new BlockPos(this.ox + x, this.oy + y, this.oz + z);
		this.level.setBlock(pos, state, Block.UPDATE_CLIENTS);
		if (state.hasProperty(BlockStateProperties.NORTH)) {
			this.shapeFix.add(pos);
		}
	}

	private void fill(int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
		for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
			for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
				for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
					this.set(x, y, z, state);
				}
			}
		}
	}

	public static void build(CommandSourceStack source, ServerLevel level, BlockPos origin) {
		ArenaBuilder builder = new ArenaBuilder(level, origin);
		builder.terrain();
		builder.house(-26, 8, false);
		builder.house(-4, 8, false);
		builder.house(14, -16, true);
		builder.lamps();
		builder.greenery();
		builder.matchProps();
		builder.fixShapes();
		builder.setupWorld(source);
	}

	/** Exits, phones, containers and every map point a match needs. */
	private void matchProps() {
		MapPoints points = MapPoints.get(this.level);
		points.clearAll();
		points.add(MapPoints.Type.CENTER, this.at(0, 0, 0));
		for (int[] s : new int[][]{{-22, 5}, {2, 5}, {18, -5}, {-8, -5}}) {
			points.add(MapPoints.Type.SPAWN_CIVILIAN, this.at(s[0], 0, s[1]));
		}
		points.add(MapPoints.Type.SPAWN_SHAPE, this.at(30, 0, -30));
		points.add(MapPoints.Type.SPAWN_DEPUTY, this.at(-33, 0, 0));

		// Exit spots, two of each kind. Exits builds the cellar hatch, the car and the gate only at the spot
		// picked for the match, so the map never shows an exit that doesn't work.
		for (int[] c : new int[][]{{-29, 12}, {25, -12}}) {
			points.add(MapPoints.Type.CELLAR, this.at(c[0], 0, c[1]));
		}
		for (int[] c : new int[][]{{-14, -1}, {12, 1}}) {
			points.add(MapPoints.Type.CAR, this.at(c[0], 0, c[1]));
		}
		points.add(MapPoints.Type.POLICE, this.at(31, 0, 0));
		points.add(MapPoints.Type.POLICE, this.at(-6, 0, -20));
		// Gates go into the boundary fence (barriers above stay; leaving is by interaction).
		for (int[] g : new int[][]{{0, -HALF}, {-10, HALF}}) {
			points.add(MapPoints.Type.GATE, this.at(g[0], 0, g[1]));
		}

		// A phone on a small table in every house.
		for (int[] p : new int[][]{{-20, 14}, {3, 14}, {20, -14}}) {
			this.set(p[0], 0, p[1], b("spruce_planks"));
			this.set(p[0], 1, p[1], dev.halloween.HwBlocks.PHONE.defaultBlockState());
			points.add(MapPoints.Type.PHONE, this.at(p[0], 1, p[1]));
		}

		// A locker to hide in, in a back corner of every house.
		LockerBlock.place(this.level, this.at(-19, 0, 15), Direction.NORTH, dev.halloween.HwBlocks.LOCKER);
		LockerBlock.place(this.level, this.at(2, 0, 15), Direction.NORTH, dev.halloween.HwBlocks.LOCKER);
		LockerBlock.place(this.level, this.at(21, 0, -15), Direction.SOUTH, dev.halloween.HwBlocks.LOCKER);

		// Townsfolk: two at home in every house.
		for (int[] p : new int[][]{{-23, 11}, {-21, 13}, {-1, 11}, {1, 12}, {17, -12}, {19, -10}}) {
			points.add(MapPoints.Type.NPC, this.at(p[0], 0, p[1]));
		}

		// The locked gun case stands in one of these (picked each match); the code note is in a container.
		for (int[] c : new int[][]{{-25, 13}, {15, -12}}) {
			points.add(MapPoints.Type.GUN_CASE, this.at(c[0], 0, c[1]));
		}

		// Containers: dressers, crates and boxes indoors and around the yards.
		for (int[] c : new int[][]{{-22, 15}, {1, 15}, {18, -15}, {-14, 20}, {24, 12}, {-26, -20}, {6, -24}}) {
			this.set(c[0], 0, c[1], dev.halloween.HwBlocks.DRESSER.defaultBlockState());
		}
		int n = 0;
		for (int[] c : new int[][]{{-25, 10}, {-1, 15}, {15, -10}, {-28, 23}, {26, -24}, {12, 25}, {-18, -28}, {30, 20},
			{-8, 12}, {8, 14}, {-20, -12}, {22, 8}, {-2, -12}, {10, -22}}) {
			this.set(c[0], 0, c[1], (n++ % 2 == 0 ? dev.halloween.HwBlocks.CRATE : dev.halloween.HwBlocks.CARDBOARD_BOX).defaultBlockState());
		}
	}

	private BlockPos at(int x, int y, int z) {
		return new BlockPos(this.ox + x, this.oy + y, this.oz + z);
	}

	private void terrain() {
		BlockState air = b("air");
		this.fill(-HALF, 0, -HALF, HALF, HEIGHT, HALF, air);
		this.fill(-HALF, -2, -HALF, HALF, -2, HALF, b("dirt"));
		this.fill(-HALF, -1, -HALF, HALF, -1, HALF, b("grass_block"));
		// Road along X with sidewalks.
		this.fill(-HALF, -1, -3, HALF, -1, 3, b("gray_concrete"));
		this.fill(-HALF, -1, -5, HALF, -1, -4, b("smooth_stone"));
		this.fill(-HALF, -1, 4, HALF, -1, 5, b("smooth_stone"));
		// Boundary: a low fence topped by invisible barriers.
		BlockState fence = b("spruce_fence");
		BlockState barrier = b("barrier");
		for (int i = -HALF; i <= HALF; i++) {
			for (int[] p : new int[][]{{i, -HALF}, {i, HALF}, {-HALF, i}, {HALF, i}}) {
				this.set(p[0], 0, p[1], fence);
				this.fill(p[0], 1, p[1], p[0], 4, p[1], barrier);
			}
		}
	}

	/**
	 * A 9x9 one-room house. The front wall faces the road: north (towards -Z) unless {@code facesSouth}.
	 * (x0, z0) is the north-west corner.
	 */
	private void house(int x0, int z0, boolean facesSouth) {
		int x1 = x0 + 8;
		int z1 = z0 + 8;
		int xc = x0 + 4;
		int zc = z0 + 4;
		int front = facesSouth ? z1 : z0;
		int back = facesSouth ? z0 : z1;

		this.fill(x0, -1, z0, x1, -1, z1, b("oak_planks"));
		this.fill(x0, 0, z0, x1, 3, z1, b("birch_planks"));
		this.fill(x0 + 1, 0, z0 + 1, x1 - 1, 3, z1 - 1, b("air"));
		BlockState log = b("stripped_dark_oak_log");
		for (int[] c : new int[][]{{x0, z0}, {x1, z0}, {x0, z1}, {x1, z1}}) {
			this.fill(c[0], 0, c[1], c[0], 3, c[1], log);
		}
		this.fill(x0 - 1, 4, z0 - 1, x1 + 1, 4, z1 + 1, b("spruce_planks"));

		// Door in the middle of the front wall.
		Direction doorFacing = facesSouth ? Direction.NORTH : Direction.SOUTH;
		BlockState door = b("oak_door").setValue(BlockStateProperties.HORIZONTAL_FACING, doorFacing);
		this.set(xc, 0, front, door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER));
		this.set(xc, 1, front, door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));

		// Windows: two on the front, one on the back and one on each side.
		BlockState pane = b("glass_pane");
		for (int x : new int[]{xc - 2, xc - 3, xc + 2, xc + 3}) {
			this.fill(x, 1, front, x, 2, front, pane);
		}
		this.fill(xc - 1, 1, back, xc + 1, 2, back, pane);
		this.fill(x0, 1, zc - 1, x0, 2, zc + 1, pane);
		this.fill(x1, 1, zc + 1, x1, 2, zc + 2, pane);

		// Ceiling lights.
		BlockState bulb = b("waxed_copper_bulb").setValue(BlockStateProperties.LIT, true);
		this.set(xc - 2, 4, zc, bulb);
		this.set(xc + 2, 4, zc, bulb);

		// Breaker box on the east wall outside: iron block in the wall, lever on it.
		int bz = facesSouth ? zc - 3 : zc + 3;
		this.set(x1, 1, bz, b("iron_block"));
		this.set(x1 + 1, 1, bz, b("lever")
			.setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
			.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
			.setValue(BlockStateProperties.POWERED, true));

		// A bit of furniture to hide behind.
		this.fill(x0 + 1, 0, back + (facesSouth ? 1 : -1), x0 + 2, 1, back + (facesSouth ? 1 : -1), b("bookshelf"));
		this.set(x1 - 1, 0, zc, b("oak_stairs"));
	}

	private void lamps() {
		BlockState post = b("dark_oak_fence");
		BlockState lantern = b("lantern");
		int[][] spots = {{-30, -5}, {-12, 5}, {6, -5}, {24, 5}};
		for (int[] s : spots) {
			this.fill(s[0], 0, s[1], s[0], 2, s[1], post);
			this.set(s[0], 3, s[1], lantern);
		}
	}

	private void greenery() {
		BlockState leaves = b("oak_leaves").setValue(BlockStateProperties.PERSISTENT, true);
		int[][] bushes = {{-30, 20}, {-12, 22}, {2, 24}, {16, 22}, {-20, -20}, {0, -24}, {28, -26}, {30, 14}};
		for (int[] p : bushes) {
			this.fill(p[0], 0, p[1], p[0] + 1, 1, p[1] + 1, leaves);
		}
		BlockState trunk = b("spruce_log");
		BlockState needles = b("spruce_leaves").setValue(BlockStateProperties.PERSISTENT, true);
		int[][] trees = {{-8, 28}, {10, 28}, {-28, -28}, {26, 26}};
		for (int[] p : trees) {
			this.fill(p[0] - 2, 3, p[1] - 2, p[0] + 2, 5, p[1] + 2, needles);
			this.fill(p[0] - 1, 6, p[1] - 1, p[0] + 1, 7, p[1] + 1, needles);
			this.fill(p[0], 0, p[1], p[0], 6, p[1], trunk);
		}
	}

	/** Panes and fences only connect once their own shape is recomputed from neighbours. */
	private void fixShapes() {
		for (BlockPos pos : this.shapeFix) {
			BlockState state = this.level.getBlockState(pos);
			BlockState updated = Block.updateFromNeighbourShapes(state, this.level, pos);
			if (updated != state) {
				this.level.setBlock(pos, updated, Block.UPDATE_CLIENTS);
			}
		}
	}

	private void setupWorld(CommandSourceStack source) {
		MinecraftServer server = this.level.getServer();
		CommandSourceStack console = server.createCommandSourceStack().withLevel(this.level).withSuppressedOutput();
		String[] commands = {
			"gamerule advance_time false",
			"gamerule advance_weather false",
			"gamerule spawn_mobs false",
			"gamerule natural_health_regeneration false",
			"gamerule show_death_messages false",
			// The vanilla locator bar (dots for other players over the XP bar) would give everyone away.
			"gamerule locator_bar false",
			"gamerule immediate_respawn true",
			"time set midnight",
			"weather clear",
		};
		for (String command : commands) {
			server.getCommands().performPrefixedCommand(console, command);
		}
		// Victims: one by a front window inside the middle house, one under a street lamp, one in a dark backyard.
		this.mannequin(console, 2.5, 9.5, 180);
		this.mannequin(console, 7.5, -3.5, 0);
		this.mannequin(console, -21.5, 21.5, 90);
		if (source.getPlayer() != null) {
			source.getPlayer().teleportTo(this.ox + 0.5, this.oy, this.oz + 0.5);
		}
	}

	private void mannequin(CommandSourceStack console, double x, double z, float yaw) {
		summonMannequin(console, this.ox + x, this.oy, this.oz + z, yaw);
	}

	public static void summonMannequin(CommandSourceStack console, double x, double y, double z, float yaw) {
		String command = String.format(Locale.ROOT,
			"summon minecraft:mannequin %.2f %.2f %.2f {Tags:[\"%s\"],hide_description:1b,Rotation:[%.1ff,0f]}",
			x, y, z, MatchManager.VICTIM_TAG, yaw);
		console.getServer().getCommands().performPrefixedCommand(console, command);
	}
}
