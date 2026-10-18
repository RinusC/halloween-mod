package dev.halloween.game;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

/**
 * The Upside Down: a dark copy of the town deep under it (or high over it, walled in, if the ground is too thin),
 * built a few columns a tick when a match with the Demogorgon starts. The same streets and houses, rotten: grass
 * gone to fungus, the earth to coarse dirt, black glass, no lamps, no doors, no leaves, vines hanging from the
 * ceilings, all of it shut in under a rock ceiling. Only the Demogorgon goes there, through his portals.
 */
public final class UpsideDown {
	/** The slice of the town copied: from a few blocks under the centre's ground to over the roofs. */
	private static final int BELOW = 6;
	private static final int ABOVE = 26;
	/** Blocks copied per tick (a column is a block wide and the slice tall). */
	private static final int PER_TICK = 6000;

	private static ServerLevel level;
	/** Copy y = y - offset (positive: under the town). */
	private static int offset;
	private static int baseY;
	private static int topY;
	private static int minX;
	private static int maxX;
	private static int minZ;
	private static int maxZ;
	private static int cursorX;
	private static int cursorZ;
	private static boolean building;
	private static boolean ready;
	/** Up in the sky (not under the ground): hidden from below by a storm cloud. */
	private static boolean sky;
	private static final String CLOUD_TAG = "hw_cloud";

	private UpsideDown() {
	}

	/** Starts building it under the match's town (nothing if there is no room for it above or below). */
	public static void start(ServerLevel at) {
		BoundingBox area = Nav.area();
		BlockPos center = MapPoints.get(at).first(MapPoints.Type.CENTER);
		ready = false;
		building = false;
		sky = false;
		if (area == null || center == null) {
			return;
		}
		level = at;
		baseY = center.getY() - BELOW;
		topY = center.getY() + ABOVE;
		int height = topY - baseY + 1;
		if (baseY - (at.getMinY() + 4) >= height + 8) {
			// Deep under the town, its floor just over the bedrock.
			offset = baseY - (at.getMinY() + 3);
		} else if (at.getMaxY() - topY >= height + 16) {
			// No room under it (a thin world): up in the sky, walled and roofed in, as high as it goes (out of sight
			// from the streets mostly), with a storm cloud hung under it (see clouds).
			offset = -(at.getMaxY() - 2 - topY);
			sky = true;
		} else {
			return;
		}
		minX = area.minX();
		maxX = area.maxX();
		minZ = area.minZ();
		maxZ = area.maxZ();
		cursorX = minX;
		cursorZ = minZ;
		building = true;
	}

	/** Copies the next columns; ready once the whole town is done. */
	public static void tick() {
		if (!building || level == null) {
			return;
		}
		int height = topY - baseY + 1;
		int budget = PER_TICK;
		BlockPos.MutableBlockPos from = new BlockPos.MutableBlockPos();
		BlockPos.MutableBlockPos to = new BlockPos.MutableBlockPos();
		while (budget > 0) {
			if (cursorX > maxX) {
				building = false;
				ready = true;
				if (sky) {
					clouds();
				}
				return;
			}
			boolean edge = cursorX == minX || cursorX == maxX || cursorZ == minZ || cursorZ == maxZ;
			for (int y = baseY; y <= topY; y++) {
				from.set(cursorX, y, cursorZ);
				to.set(cursorX, y - offset, cursorZ);
				BlockState state = edge ? Blocks.DEEPSLATE.defaultBlockState() : rot(level.getBlockState(from), level, from);
				level.setBlock(to, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
			}
			// Shut in: rock under the floor and over the roofs (no daylight up in the sky either).
			level.setBlock(to.set(cursorX, baseY - offset - 1, cursorZ), Blocks.DEEPSLATE.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
			level.setBlock(to.set(cursorX, topY - offset + 1, cursorZ), Blocks.DEEPSLATE.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
			budget -= height + 2;
			if (sky) {
				// An older copy hung low (right over the roofs, before it went up high): taken down.
				int old = height + 12;
				if (level.getBlockState(to.set(cursorX, baseY + old - 1, cursorZ)).is(Blocks.DEEPSLATE)) {
					for (int y = baseY + old - 1; y <= topY + old + 1; y++) {
						level.setBlock(to.set(cursorX, y, cursorZ), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
					}
					budget -= height + 2;
				}
			}
			if (++cursorZ > maxZ) {
				cursorZ = minZ;
				cursorX++;
			}
		}
	}

	/**
	 * A storm cloud under the floor: big soft patches (item displays) in two layers a few blocks down, overlapping
	 * and spilling past the edges, so from the streets it looks like a low heavy sky, not a rock ceiling.
	 */
	private static void clouds() {
		level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack().withSuppressedOutput(), "kill @e[type=item_display,tag=" + CLOUD_TAG + "]");
		net.minecraft.util.RandomSource r = net.minecraft.util.RandomSource.create(minX * 31L + minZ);
		int floor = baseY - offset - 1;
		int margin = 40;
		for (int layer = 0; layer < 2; layer++) {
			float size = layer == 0 ? 64 : 80;
			int step = layer == 0 ? 36 : 48;
			for (int x = minX - margin; x <= maxX + margin; x += step) {
				for (int z = minZ - margin; z <= maxZ + margin; z += step) {
					net.minecraft.world.entity.Display.ItemDisplay d = new net.minecraft.world.entity.Display.ItemDisplay(net.minecraft.world.entity.EntityTypes.ITEM_DISPLAY, level);
					net.minecraft.world.item.ItemStack s = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PAPER);
					s.set(net.minecraft.core.component.DataComponents.ITEM_MODEL, dev.halloween.Halloween.id("storm_cloud"));
					d.setItemStack(s);
					d.setItemTransform(net.minecraft.world.item.ItemDisplayContext.FIXED);
					float sz = size * (0.8f + r.nextFloat() * 0.4f);
					d.setTransformation(new com.mojang.math.Transformation(null, new org.joml.Quaternionf().rotateY(r.nextFloat() * 6.283f), new org.joml.Vector3f(sz, 1, sz), null));
					d.setBrightnessOverride(new net.minecraft.util.Brightness(0, layer == 0 ? 5 : 3));
					d.setViewRange(8f);
					d.setPos(x + r.nextInt(step / 2), floor - 2 - layer * 4 - r.nextFloat() * 2, z + r.nextInt(step / 2));
					d.addTag(CLOUD_TAG);
					level.addFreshEntity(d);
				}
			}
		}
	}

	/** What a block of the town is in the Upside Down. */
	private static BlockState rot(BlockState state, ServerLevel at, BlockPos pos) {
		int h = Math.floorMod(pos.getX() * 73856093 ^ pos.getY() * 19349663 ^ pos.getZ() * 83492791, 997);
		if (state.isAir()) {
			BlockState over = at.getBlockState(pos.above());
			BlockState under = at.getBlockState(pos.below());
			if (!over.isAir() && over.isSolidRender()) {
				// Under every ceiling: vines hanging thick, cobwebs in the corners.
				if (h % 6 == 0) {
					return Blocks.WEEPING_VINES.defaultBlockState();
				}
				if (h % 29 == 1) {
					return Blocks.COBWEB.defaultBlockState();
				}
			}
			String below = BuiltInRegistries.BLOCK.getKey(under.getBlock()).getPath();
			if ((below.equals("grass_block") || below.equals("moss_block")) && h % 7 == 2) {
				// Out in the streets: dead roots growing out of the fungus.
				return h % 2 == 0 ? Blocks.CRIMSON_ROOTS.defaultBlockState() : Blocks.WITHER_ROSE.defaultBlockState();
			}
			return state;
		}
		if (state.isSolidRender() && !state.hasBlockEntity()) {
			// The rot creeping over everything: flesh on the walls, sculk over the floors.
			boolean floor = at.getBlockState(pos.above()).isAir();
			if (floor && h % 6 == 0) {
				return Blocks.SCULK.defaultBlockState();
			}
			if (!floor && h % 13 == 0) {
				return Blocks.NETHER_WART_BLOCK.defaultBlockState();
			}
			if (h % 41 == 3) {
				return Blocks.GILDED_BLACKSTONE.defaultBlockState();
			}
		}
		if (state.is(dev.halloween.HwBlocks.PHONE) || state.getBlock() instanceof TrapBlock || state.is(Blocks.LEVER)) {
			return Blocks.AIR.defaultBlockState();
		}
		String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		if (id.equals("grass_block") || id.equals("moss_block")) {
			return Blocks.MYCELIUM.defaultBlockState();
		}
		if (id.equals("dirt") || id.equals("dirt_path") || id.equals("podzol") || id.equals("rooted_dirt") || id.equals("farmland")) {
			return Blocks.COARSE_DIRT.defaultBlockState();
		}
		if (id.contains("leaves") || id.contains("door") || id.contains("fence_gate") || id.contains("lantern") || id.contains("torch")
			|| id.contains("lamp") || id.equals("glowstone") || id.equals("sea_lantern") || id.equals("shroomlight") || id.equals("light")
			|| id.contains("grass") || id.contains("fern") || id.contains("flower") || id.contains("tulip") || id.contains("bush") || id.equals("water")
			|| id.contains("candle") || id.contains("sign")) {
			return Blocks.AIR.defaultBlockState();
		}
		if (id.contains("glass_pane") || id.equals("iron_bars")) {
			return Blocks.STAINED_GLASS_PANE.black().defaultBlockState();
		}
		if (id.contains("glass")) {
			return Blocks.TINTED_GLASS.defaultBlockState();
		}
		return state;
	}

	public static boolean ready() {
		return ready;
	}

	/** Is this entity down there? */
	public static boolean isBelow(Entity e) {
		if (!ready || e.level() != level) {
			return false;
		}
		double y = e.getY();
		return y >= baseY - offset - 1 && y <= topY - offset + 2 && e.getX() >= minX && e.getX() <= maxX + 1 && e.getZ() >= minZ && e.getZ() <= maxZ + 1;
	}

	/** The same place in the Upside Down. */
	public static Vec3 below(Vec3 p) {
		return p.subtract(0, offset, 0);
	}

	/** The same place up in the town. */
	public static Vec3 above(Vec3 p) {
		return p.add(0, offset, 0);
	}

	/** Stops building (a match over); what is built stays and is built over next time. */
	public static void clear() {
		building = false;
		ready = false;
		level = null;
	}
}
