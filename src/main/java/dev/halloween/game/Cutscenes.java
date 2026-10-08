package dev.halloween.game;

import dev.halloween.Role;
import dev.halloween.net.HwNet;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * The opening scenes, played while the match counts down (client.Cutscene draws them). Survivors fly over the
 * night town towards their own house and drop into their eyes. Michael looks out through the mask, walking up
 * to a lit window with one of them behind it: the survivor nearest to him, or else any window near him.
 */
public final class Cutscenes {
	public static final int SURVIVOR = 0;
	public static final int SHAPE = 1;
	/** How far from Michael a window may be (its chunks have to be loaded around him). */
	private static final int WINDOW_REACH = 90;

	private Cutscenes() {
	}

	public static void send(MinecraftServer server, int ticks, ServerPlayer shape, List<ServerPlayer> players, BlockPos center) {
		ServerLevel level = server.overworld();
		Vec3 c = center == null ? Vec3.ZERO : Vec3.atBottomCenterOf(center);
		for (ServerPlayer p : players) {
			if (Bots.isBot(p)) {
				continue;
			}
			if (p == shape) {
				HwNet.Cutscene scene = shapeScene(level, p, players, ticks);
				if (scene != null) {
					ServerPlayNetworking.send(p, scene);
				}
			} else if (MatchManager.role(p) != Role.NONE) {
				ServerPlayNetworking.send(p, new HwNet.Cutscene(SURVIVOR, ticks, c, Vec3.ZERO, Vec3.ZERO));
			}
		}
	}

	private static HwNet.Cutscene shapeScene(ServerLevel level, ServerPlayer shape, List<ServerPlayer> players, int ticks) {
		ServerPlayer nearest = null;
		double best = Double.MAX_VALUE;
		for (ServerPlayer p : players) {
			if (p != shape && MatchManager.role(p).survivor()) {
				double d = p.distanceTo(shape);
				if (d < best) {
					best = d;
					nearest = p;
				}
			}
		}
		// A lit window with somebody behind it: a townsperson at home, the nearest one to him; else by the nearest
		// survivor, else any lit window near him.
		BlockPos window = null;
		Vec3 inside = null;
		net.minecraft.world.entity.decoration.Mannequin home = null;
		double homeDist = WINDOW_REACH;
		for (net.minecraft.world.entity.decoration.Mannequin npc : Townsfolk.bodies()) {
			double d = npc.distanceTo(shape);
			if (d < homeDist) {
				BlockPos w = windowNear(level, npc.blockPosition(), 7, true);
				if (w != null) {
					homeDist = d;
					home = npc;
					window = w;
				}
			}
		}
		if (home != null) {
			inside = home.getEyePosition();
		}
		if (window == null && nearest != null && best < WINDOW_REACH) {
			window = windowNear(level, nearest.blockPosition(), 14, true);
		}
		if (window == null) {
			window = windowNear(level, shape.blockPosition(), 60, true);
		}
		if (window == null) {
			window = windowNear(level, shape.blockPosition(), 40, false);
		}
		if (window == null) {
			dev.halloween.Halloween.LOGGER.info("Cutscene: no window near Michael at {}", shape.blockPosition());
			return null;
		}
		Direction out = outside(level, window);
		if (out == null) {
			return null;
		}
		// He stops a step from the glass, at eye height (the lowest pane sits a block above the floor).
		Vec3 o = new Vec3(out.getStepX(), 0, out.getStepZ());
		Vec3 pane = Vec3.atCenterOf(window);
		Vec3 end = pane.add(o.scale(2.6)).add(0, 0.12, 0);
		Vec3 start = end.add(o.scale(11));
		Vec3 look = inside != null ? inside : pane.subtract(o.scale(3)).add(0, -0.1, 0);
		return new HwNet.Cutscene(SHAPE, ticks, start, end, look);
	}

	/** The nearest window column to {@code from} (its lowest pane), lit from inside if asked, or null. */
	private static BlockPos windowNear(ServerLevel level, BlockPos from, int radius, boolean lit) {
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos p : BlockPos.betweenClosed(from.offset(-radius, -3, -radius), from.offset(radius, 4, radius))) {
			if (!Windows.isWindow(level.getBlockState(p)) || Windows.isWindow(level.getBlockState(p.below())) || outside(level, p) == null) {
				continue;
			}
			if (lit && level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, p.relative(outside(level, p).getOpposite())) < 9) {
				continue;
			}
			double d = p.distSqr(from);
			if (d < bestDist) {
				bestDist = d;
				best = p.immutable();
			}
		}
		return best;
	}

	/** Which side of this pane is outdoors (open to the sky, room to stand back), or null. */
	private static Direction outside(ServerLevel level, BlockPos pane) {
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos a = pane.relative(d);
			BlockPos b = pane.relative(d, 6);
			// Open sky a little way out (the eaves overhang the wall itself).
			if ((level.canSeeSky(pane.relative(d, 2)) || level.canSeeSky(pane.relative(d, 3))) && level.getBlockState(a).getCollisionShape(level, a).isEmpty()
				&& level.getBlockState(b).getCollisionShape(level, b).isEmpty() && !level.canSeeSky(pane.relative(d.getOpposite()))) {
				return d;
			}
		}
		return null;
	}
}
