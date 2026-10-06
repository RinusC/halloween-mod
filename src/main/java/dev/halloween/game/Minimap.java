package dev.halloween.game;

import dev.halloween.net.HwNet;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * The town as a plan for the minimap, baked once when a match starts: each column of the play area is
 * ground, road, trees or building. The client draws it in its own style; nobody's position is in it.
 */
public final class Minimap {
	public static final byte GROUND = 0;
	public static final byte ROAD = 1;
	public static final byte TREES = 2;
	public static final byte BUILDING = 3;
	/** Extra blocks around the map points, so the woods at the edge show. */
	private static final int MARGIN = 16;

	private static HwNet.MinimapSync current = HwNet.MinimapSync.NONE;

	private Minimap() {
	}

	/** Works out the plan of the area the map points span, at the height of the map centre, and sends it. */
	public static void bake(ServerLevel level, BoundingBox area, BlockPos center) {
		int x0 = area.minX() - MARGIN;
		int z0 = area.minZ() - MARGIN;
		int w = area.maxX() + MARGIN - x0 + 1;
		int h = area.maxZ() + MARGIN - z0 + 1;
		byte[] plan = new byte[w * h];
		int base = center.getY();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int dz = 0; dz < h; dz++) {
			for (int dx = 0; dx < w; dx++) {
				int x = x0 + dx;
				int z = z0 + dz;
				byte kind = road(level.getBlockState(pos.set(x, base - 1, z))) ? ROAD : GROUND;
				boolean trees = false;
				for (int dy = 0; dy <= 10; dy++) {
					BlockState s = level.getBlockState(pos.set(x, base + dy, z));
					if (s.isAir()) {
						continue;
					}
					if (s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS)) {
						trees = true;
					} else if (dy >= 1 && s.isSolidRender()) {
						kind = BUILDING;
						break;
					}
				}
				if (kind != BUILDING && trees) {
					kind = TREES;
				}
				plan[dz * w + dx] = kind;
			}
		}
		current = new HwNet.MinimapSync(x0, z0, w, h, plan);
		for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
			if (!Bots.isBot(player)) {
				ServerPlayNetworking.send(player, current);
			}
		}
	}

	private static boolean road(BlockState ground) {
		String id = ground.getBlock().getDescriptionId();
		return id.contains("concrete") && !id.contains("powder") || id.contains("terracotta") || id.contains("smooth_stone")
			|| id.contains("stone_brick") || id.contains("andesite") || id.contains("coarse_dirt");
	}

	public static void clear(ServerLevel level) {
		current = HwNet.MinimapSync.NONE;
		for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
			if (!Bots.isBot(player)) {
				ServerPlayNetworking.send(player, current);
			}
		}
	}

	/** Someone joined mid-match: they get the plan too. */
	public static void syncTo(ServerPlayer player) {
		ServerPlayNetworking.send(player, current);
	}
}
