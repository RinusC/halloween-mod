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

	/** How far from their spawn a survivor's place for their scene may be. */
	private static final int STAGE_REACH = 28;

	/**
	 * Before their scene: a survivor put where what they do belongs (near where they would start, never near him):
	 * the student at a table indoors, the girl on the phone at a telephone, the reader at a bookcase, the babysitter at a
	 * window, the pumpkin carver by a pumpkin, the boys at a car, on a porch, in a yard. Where there is none, they stay.
	 */
	public static void stage(ServerLevel level, ServerPlayer p, BlockPos lair) {
		int hero = heroOf(p);
		if (hero < 0) {
			return;
		}
		String name = Skins.SURVIVORS.get(hero);
		java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> what;
		// Where they stand: indoors (1), outdoors (2), either (0); and whether they face the thing or turn their back on it.
		int side;
		boolean away = false;
		switch (name) {
			case "laurie" -> {
				// A table (with a candle on it, where the family eats): her books open on it.
				what = s -> s.is(net.minecraft.world.level.block.Blocks.DARK_OAK_FENCE);
				side = 1;
			}
			case "annie" -> {
				what = s -> s.is(dev.halloween.HwBlocks.PHONE);
				side = 1;
			}
			case "jennifer" -> {
				what = s -> s.is(net.minecraft.world.level.block.Blocks.BOOKSHELF);
				side = 1;
			}
			case "alexis" -> {
				what = Windows::isWindow;
				side = 1;
			}
			case "rachel" -> {
				what = s -> s.is(net.minecraft.world.level.block.Blocks.CARVED_PUMPKIN) || s.is(net.minecraft.world.level.block.Blocks.JACK_O_LANTERN);
				side = 2;
			}
			case "lynda", "richard" -> {
				// On the porch by a front door, looking out at the street.
				what = s -> s.getBlock() instanceof net.minecraft.world.level.block.DoorBlock;
				side = 2;
				away = true;
			}
			case "tanya" -> {
				// At somebody's front door, trick or treating.
				what = s -> s.getBlock() instanceof net.minecraft.world.level.block.DoorBlock;
				side = 2;
			}
			case "marcus", "thomas" -> {
				// Out in a yard.
				what = s -> s.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK);
				side = 2;
			}
			case "bob", "eric" -> {
				stageAtCar(level, p, lair, name.equals("eric"));
				return;
			}
			default -> {
				return;
			}
		}
		BlockPos from = p.blockPosition();
		BlockPos bestStand = null;
		BlockPos bestThing = null;
		double best = Double.MAX_VALUE;
		double far = dev.halloween.HwConfig.get().spawnAwayFromKiller;
		for (BlockPos t : BlockPos.betweenClosed(from.offset(-STAGE_REACH, -3, -STAGE_REACH), from.offset(STAGE_REACH, 6, STAGE_REACH))) {
			if (!what.test(level.getBlockState(t))) {
				continue;
			}
			for (Direction d : Direction.Plane.HORIZONTAL) {
				// A grass block is stood on, not beside.
				BlockPos stand = name.equals("marcus") || name.equals("thomas") ? t.above() : t.relative(d);
				if (!Nav.standable(level, stand) || level.getBlockState(stand.below()).getBlock() instanceof net.minecraft.world.level.block.DoorBlock) {
					if (!Nav.standable(level, stand.below())) {
						continue;
					}
					stand = stand.below();
				}
				boolean open = level.canSeeSky(stand.above());
				if (side == 1 && open || side == 2 && !open || lair != null && stand.distSqr(lair) < far * far) {
					continue;
				}
				double dist = stand.distSqr(from);
				if (dist < best) {
					best = dist;
					bestStand = stand.immutable();
					bestThing = t.immutable();
				}
			}
		}
		if (bestStand == null) {
			return;
		}
		Vec3 at = Vec3.atBottomCenterOf(bestStand);
		Vec3 to = Vec3.atBottomCenterOf(bestThing).subtract(at);
		if (name.equals("marcus") || name.equals("thomas")) {
			to = new Vec3(level.getRandom().nextDouble() - 0.5, 0, level.getRandom().nextDouble() - 0.5);
		}
		if (away) {
			to = to.scale(-1);
		}
		float yaw = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
		p.teleportTo(level, at.x, at.y, at.z, java.util.Set.of(), yaw, 0f, false);
	}

	/** Bob and Eric: by a car (one of the cars on the map), Eric at its front, Bob leaning by its side. */
	private static void stageAtCar(ServerLevel level, ServerPlayer p, BlockPos lair, boolean front) {
		BlockPos best = null;
		double bestDist = 60 * 60;
		double far = dev.halloween.HwConfig.get().spawnAwayFromKiller;
		for (BlockPos car : MapPoints.get(level).list(MapPoints.Type.CAR)) {
			double d = car.distSqr(p.blockPosition());
			if (d < bestDist && (lair == null || car.distSqr(lair) >= far * far)) {
				bestDist = d;
				best = car;
			}
		}
		if (best == null) {
			return;
		}
		// The free spot nearest the car (its front is along X, see Exits), a step off its body.
		BlockPos stand = null;
		double bestScore = Double.MAX_VALUE;
		for (BlockPos s : BlockPos.betweenClosed(best.offset(-5, -1, -3), best.offset(5, 2, 3))) {
			if (!Nav.standable(level, s)) {
				continue;
			}
			double along = s.getX() - best.getX();
			double score = s.distSqr(best) + (front ? Math.abs(along - 4.5) * 3 : Math.abs(along) * 3);
			if (score < bestScore) {
				bestScore = score;
				stand = s.immutable();
			}
		}
		if (stand == null) {
			return;
		}
		Vec3 at = Vec3.atBottomCenterOf(stand);
		Vec3 to = Vec3.atBottomCenterOf(best).subtract(at);
		float yaw = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
		p.teleportTo(level, at.x, at.y, at.z, java.util.Set.of(), yaw, 0f, false);
	}

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
		// The camera before their face if there is room (a little to one side, nearer in a small room), else off to a
		// side, behind them only at worst.
		Vec3 face = front;
		search:
		for (double dist : new double[]{3.2, 2.2}) {
			for (double deg : new double[]{0, 30, -30, 60, -60, 95, -95, 140, -140, 180}) {
				double a = Math.toRadians(deg);
				Vec3 dir = new Vec3(face.x * Math.cos(a) - face.z * Math.sin(a), 0, face.x * Math.sin(a) + face.z * Math.cos(a));
				Vec3 cam = eye.add(dir.scale(dist)).add(0, 0.2, 0);
				if (level.clip(new net.minecraft.world.level.ClipContext(eye, cam, net.minecraft.world.level.ClipContext.Block.COLLIDER,
					net.minecraft.world.level.ClipContext.Fluid.NONE, p)).getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
					front = dir;
					break search;
				}
			}
		}
		// Him: behind them, as far off as he can be seen (in the open, not inside a wall).
		Vec3 ghost = null;
		for (double d : new double[]{9, 7, 5.5, 4}) {
			// (Off to one side of them in the shot, not hidden right behind them.)
			Vec3 at = feet.subtract(front.scale(d)).add(new Vec3(-front.z, 0, front.x).scale(-d * 0.3));
			BlockPos b = BlockPos.containing(at);
			for (int dy : new int[]{0, 1, -1}) {
				// (Through a window will do: him outside, looking in.)
				if (Nav.standable(level, b.above(dy)) && Sight.canSee(level, eye, Vec3.atBottomCenterOf(b.above(dy)).add(0, 1.5, 0), p)) {
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
			// (Through a window will do, from out in the dark.)
			if (Sight.canSee(level, eye, start, t) && Sight.canSee(level, eye, end, t)
				&& (level.canSeeSky(BlockPos.containing(start)) || turn >= angles.length - 1 || level.canSeeSky(BlockPos.containing(eye)))) {
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
