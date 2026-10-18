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
	/** Not an opening scene: a kill seen from the side for a few seconds (a = the camera, b = what it looks at; see GrabSession). */
	public static final int KILLCAM = 2;
	/** How far from Michael a window may be (its chunks have to be loaded around him). */
	private static final int WINDOW_REACH = 90;

	private Cutscenes() {
	}

	/** A survivor's own scene: {@code HERO + i} for the i-th of Skins.SURVIVORS (a = their feet, b = towards the camera, c = where he shows). */
	public static final int HERO = 10;
	/** Michael's scene: {@code SHAPE_HERO + i}, the i-th survivor's scene through his mask (a = start, b = end, c = their eyes). */
	public static final int SHAPE_HERO = 30;
	/** Share of a survivor's scene when he shows behind them, and for how long (ticks). */
	public static final float GHOST_AT = 0.55f;
	private static final int GHOST_TICKS = 40;

	/** Him, seen for a moment behind a survivor in their scene (a mannequin in his clothes), and when he goes. */
	private record Ghost(net.minecraft.world.entity.decoration.Mannequin body, int until) {
	}

	private static final java.util.List<Ghost> GHOSTS = new java.util.ArrayList<>();
	/** Ghosts still to show: where, facing which way, from which tick. */
	private record Pending(Vec3 at, float yaw, int from) {
	}

	private static final java.util.List<Pending> PENDING = new java.util.ArrayList<>();

	/** Which of the survivors' scenes this player gets (their look), or -1. */
	private static int heroOf(ServerPlayer p) {
		String skin = Skins.skinOf(p);
		return skin == null ? -1 : Skins.SURVIVORS.indexOf(skin);
	}

	/**
	 * A survivor's scene: the camera a few steps in front of them, a little to the side, where nothing is in the way;
	 * him standing in the dark some way behind them, in the shot, for a moment.
	 */
	private static HwNet.Cutscene heroScene(ServerLevel level, ServerPlayer p, int hero, int ticks, int now) {
		Vec3 feet = p.position();
		Vec3 eye = p.getEyePosition();
		Vec3 front = p.getLookAngle().multiply(1, 0, 1);
		front = front.lengthSqr() < 1e-4 ? new Vec3(0, 0, 1) : front.normalize();
		// Turned round if the camera would sit in a wall that way.
		for (int turn = 0; turn < 4; turn++) {
			Vec3 cam = eye.add(front.scale(3.2)).add(0, 0.2, 0);
			if (level.clip(new net.minecraft.world.level.ClipContext(eye, cam, net.minecraft.world.level.ClipContext.Block.COLLIDER,
				net.minecraft.world.level.ClipContext.Fluid.NONE, p)).getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
				break;
			}
			front = new Vec3(-front.z, 0, front.x);
		}
		// Him: behind them, as far off as he can be seen (in the open, not inside a wall).
		Vec3 ghost = null;
		for (double d : new double[]{9, 7, 5.5, 4}) {
			// (Off to one side of them in the shot, not hidden right behind them.)
			Vec3 at = feet.subtract(front.scale(d)).add(new Vec3(-front.z, 0, front.x).scale(-d * 0.3));
			BlockPos b = BlockPos.containing(at);
			for (int dy : new int[]{0, 1, -1}) {
				if (Nav.standable(level, b.above(dy)) && level.clip(new net.minecraft.world.level.ClipContext(eye, Vec3.atBottomCenterOf(b.above(dy)).add(0, 1.5, 0),
					net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, p)).getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
					ghost = Vec3.atBottomCenterOf(b.above(dy));
					break;
				}
			}
			if (ghost != null) {
				break;
			}
		}
		if (ghost != null) {
			Vec3 to = feet.subtract(ghost);
			PENDING.add(new Pending(ghost, (float) Math.toDegrees(Math.atan2(-to.x, to.z)), now + Math.round(ticks * GHOST_AT)));
		}
		return new HwNet.Cutscene(HERO + hero, ticks, feet, front, ghost == null ? Vec3.ZERO : ghost);
	}

	/**
	 * Michael's scene: one of the survivors (at random) at what they are doing, watched through the mask from the dark
	 * a dozen steps off, coming a few steps nearer.
	 */
	private static HwNet.Cutscene shapeHeroScene(ServerLevel level, List<ServerPlayer> players, ServerPlayer shape, int ticks) {
		List<ServerPlayer> survivors = new java.util.ArrayList<>();
		for (ServerPlayer p : players) {
			if (p != shape && MatchManager.role(p).survivor() && heroOf(p) >= 0) {
				survivors.add(p);
			}
		}
		if (survivors.isEmpty()) {
			return null;
		}
		ServerPlayer t = survivors.get(level.getRandom().nextInt(survivors.size()));
		Vec3 eye = t.getEyePosition();
		Vec3 front = t.getLookAngle().multiply(1, 0, 1);
		front = front.lengthSqr() < 1e-4 ? new Vec3(0, 0, 1) : front.normalize();
		// From somewhere ahead of them and off to a side (what they do in view), with a clear view of them.
		double[] angles = {0.3, -0.3, 0.55, -0.55, 0.0, 0.8, -0.8, 1.0};
		for (int turn = 0; turn < angles.length; turn++) {
			double a = Math.PI * angles[turn];
			Vec3 dir = new Vec3(front.x * Math.cos(a) - front.z * Math.sin(a), 0, front.x * Math.sin(a) + front.z * Math.cos(a));
			Vec3 start = eye.add(dir.scale(11)).add(0, 0.1, 0);
			Vec3 end = eye.add(dir.scale(6)).add(0, 0.1, 0);
			if (level.clip(new net.minecraft.world.level.ClipContext(eye, start, net.minecraft.world.level.ClipContext.Block.COLLIDER,
				net.minecraft.world.level.ClipContext.Fluid.NONE, t)).getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
				if (shape.distanceTo(t) > 64) {
					// Too far for his screen to have that part of town: he waits out the countdown there himself, unseen, and is
					// back in his lair when it starts.
					shapeHome = new Home(shape, shape.position(), shape.getYRot());
					shape.teleportTo(level, start.x, start.y - shape.getEyeHeight(), start.z, java.util.Set.of(), shape.getYRot(), 0f, false);
					shape.setInvisible(true);
				}
				return new HwNet.Cutscene(SHAPE_HERO + heroOf(t), ticks, start, end, eye);
			}
		}
		return null;
	}

	/** Michael moved out to watch a scene far from his lair: where he goes back to when the match starts. */
	private record Home(ServerPlayer who, Vec3 at, float yaw) {
	}

	private static Home shapeHome;

	/** The ghosts of the survivors' scenes: shown for a moment, then gone. */
	public static void tick(MinecraftServer server) {
		if (shapeHome != null && !Match.frozen()) {
			Home h = shapeHome;
			shapeHome = null;
			if (!h.who().isRemoved()) {
				h.who().teleportTo(h.who().level(), h.at().x, h.at().y, h.at().z, java.util.Set.of(), h.yaw(), 0f, false);
				h.who().setInvisible(false);
			}
		}
		if (PENDING.isEmpty() && GHOSTS.isEmpty()) {
			return;
		}
		int now = server.getTickCount();
		ServerLevel level = server.overworld();
		java.util.Iterator<Pending> it = PENDING.iterator();
		while (it.hasNext()) {
			Pending p = it.next();
			if (now < p.from()) {
				continue;
			}
			it.remove();
			net.minecraft.world.entity.decoration.Mannequin m = ghost(level, p.at(), p.yaw());
			if (m != null) {
				GHOSTS.add(new Ghost(m, now + GHOST_TICKS));
			}
		}
		GHOSTS.removeIf(g -> {
			if (now < g.until() && !g.body().isRemoved()) {
				return false;
			}
			g.body().discard();
			return true;
		});
	}

	private static net.minecraft.world.entity.decoration.Mannequin ghost(ServerLevel level, Vec3 at, float yaw) {
		String snbt = String.format(java.util.Locale.ROOT,
			"{Tags:[\"%s\",\"hw_ghost\"],hide_description:1b,immovable:1b,profile:{texture:\"halloween:entity/skin/michael\",model:\"wide\"},Rotation:[%.1ff,0f]}",
			Leftovers.stamp(), yaw);
		try {
			net.minecraft.world.entity.Entity e = net.minecraft.server.commands.SummonCommand.createEntity(level.getServer().createCommandSourceStack().withLevel(level).withSuppressedOutput(),
				net.minecraft.world.entity.EntityTypes.MANNEQUIN.builtInRegistryHolder(), at, net.minecraft.nbt.TagParser.parseCompoundFully(snbt), false);
			return e instanceof net.minecraft.world.entity.decoration.Mannequin m ? m : null;
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
			dev.halloween.Halloween.LOGGER.error("Could not show him in a scene", e);
			return null;
		}
	}

	public static void send(MinecraftServer server, int ticks, ServerPlayer shape, List<ServerPlayer> players, BlockPos center) {
		ServerLevel level = server.overworld();
		Vec3 c = center == null ? Vec3.ZERO : Vec3.atBottomCenterOf(center);
		for (ServerPlayer p : players) {
			if (Bots.isBot(p)) {
				continue;
			}
			if (p == shape) {
				// One of them at what they do, through his mask (else the lit window).
				HwNet.Cutscene scene = shapeHeroScene(level, players, p, ticks);
				if (scene == null) {
					scene = shapeScene(level, p, players, ticks);
				}
				if (scene != null) {
					ServerPlayNetworking.send(p, scene);
				}
			} else if (MatchManager.role(p).survivor() && heroOf(p) >= 0) {
				// Their own scene, by who they are.
				ServerPlayNetworking.send(p, heroScene(level, p, heroOf(p), ticks, server.getTickCount()));
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
