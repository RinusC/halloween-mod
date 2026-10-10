package dev.halloween.game;

import com.mojang.math.Transformation;
import dev.halloween.HwConfig;
import dev.halloween.Role;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Brackett's patrol (an event, see Events): a black-and-white car with its lights going drives the streets on
 * the right-hand side, turning at crossings now and then and round at the end of a street. Its headlights light
 * the road ahead; Michael caught in them (not as a shadow) puts the wanted level up, once. A survivor can wave it
 * down: it stops and an officer gets out to look after them. It waits for anyone in its way.
 */
final class PatrolCar {
	static final String TAG = "hw_patrol";
	/** Off the middle of the street, on the right-hand side. */
	private static final double LANE = 1.6;

	/** The streets of the map: east-west ones by their z, north-south ones by their x, and the road's height. */
	record Roads(int y, List<Integer> eastWest, List<Integer> northSouth) {
	}

	private final ServerLevel level;
	private final Roads roads;
	private final List<Display.BlockDisplay> parts = new ArrayList<>();
	private Interaction hitbox;
	private Display.BlockDisplay redLamp;
	private Display.BlockDisplay blueLamp;
	/** Driving along x (an east-west street) or along z; which street; how far along it; which way (+1 / -1). */
	private boolean alongX;
	private int line;
	private double along;
	private int dir;
	private double x;
	private double z;
	private float yaw;
	/** Pulled up for someone who waved it down. */
	private int waitTicks;
	private int ticks;
	private final Set<BlockPos> lights = new HashSet<>();
	/** It has seen Michael already (the wanted level goes up only once). */
	boolean sawMichael;

	private PatrolCar(ServerLevel level, Roads roads) {
		this.level = level;
		this.roads = roads;
	}

	// ---------------------------------------------------------------- the streets

	private static BlockState block(String id) {
		return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.withDefaultNamespace(id)).defaultBlockState();
	}

	private static final net.minecraft.world.level.block.Block GRAY = block("gray_concrete").getBlock();
	private static final net.minecraft.world.level.block.Block YELLOW = block("yellow_terracotta").getBlock();

	private static boolean asphalt(BlockState s) {
		return s.is(GRAY) || s.is(YELLOW) || s.is(Blocks.CHISELED_STONE_BRICKS);
	}

	private boolean road(double px, double pz) {
		BlockPos at = BlockPos.containing(px, this.roads.y, pz);
		return Nav.inArea(at) && asphalt(this.level.getBlockState(at)) && this.level.getBlockState(at.above()).isAir();
	}

	/** The streets: rows (and columns) of asphalt running across most of the town, 5 to 11 wide. Null if none. */
	static Roads find(ServerLevel level) {
		BoundingBox box = Nav.area();
		BlockPos center = MapPoints.get(level).first(MapPoints.Type.CENTER);
		if (box == null || center == null) {
			return null;
		}
		// The road's height: where most of the asphalt is round the middle.
		int bestY = 0;
		int best = -1;
		for (int y = center.getY() - 4; y <= center.getY() + 2; y++) {
			int n = 0;
			for (int dx = -48; dx <= 48; dx += 2) {
				for (int dz = -48; dz <= 48; dz += 2) {
					n += asphalt(level.getBlockState(new BlockPos(center.getX() + dx, y, center.getZ() + dz))) ? 1 : 0;
				}
			}
			if (n > best) {
				best = n;
				bestY = y;
			}
		}
		if (best < 30) {
			return null;
		}
		int w = box.maxX() - box.minX() + 1;
		int d = box.maxZ() - box.minZ() + 1;
		boolean[][] road = new boolean[w][d];
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		for (int i = 0; i < w; i++) {
			for (int j = 0; j < d; j++) {
				at.set(box.minX() + i, bestY, box.minZ() + j);
				road[i][j] = asphalt(level.getBlockState(at)) && level.getBlockState(at.move(0, 1, 0)).isAir();
			}
		}
		List<Integer> eastWest = new ArrayList<>();
		List<Integer> northSouth = new ArrayList<>();
		lines(road, w, d, true, box.minZ(), eastWest);
		lines(road, w, d, false, box.minX(), northSouth);
		return eastWest.isEmpty() && northSouth.isEmpty() ? null : new Roads(bestY, eastWest, northSouth);
	}

	/** Rows (rowsAlongX: each z) or columns that are mostly asphalt, grouped into streets by their middle. */
	private static void lines(boolean[][] road, int w, int d, boolean rowsAlongX, int origin, List<Integer> out) {
		int count = rowsAlongX ? d : w;
		int length = rowsAlongX ? w : d;
		int start = -1;
		for (int k = 0; k <= count; k++) {
			boolean full = false;
			if (k < count) {
				int n = 0;
				for (int t = 0; t < length; t++) {
					n += (rowsAlongX ? road[t][k] : road[k][t]) ? 1 : 0;
				}
				full = n >= length * 0.45;
			}
			if (full && start < 0) {
				start = k;
			} else if (!full && start >= 0) {
				int width = k - start;
				if (width >= 5 && width <= 11) {
					out.add(origin + (start + k - 1) / 2);
				}
				start = -1;
			}
		}
	}

	// ---------------------------------------------------------------- setting off

	/** A car at the crossing farthest from everyone, setting off down a street. Null if there is nowhere to drive. */
	static PatrolCar spawn(ServerLevel level, Roads roads) {
		PatrolCar car = new PatrolCar(level, roads);
		double bestScore = -1;
		int bx = 0;
		int bz = 0;
		for (int cx : roads.northSouth()) {
			for (int cz : roads.eastWest()) {
				if (!car.road(cx + 0.5, cz + 0.5)) {
					continue;
				}
				double nearest = 1000;
				for (ServerPlayer p : level.players()) {
					if (MatchManager.role(p) != Role.NONE && !p.isSpectator()) {
						nearest = Math.min(nearest, Math.hypot(p.getX() - cx, p.getZ() - cz));
					}
				}
				double score = nearest + level.getRandom().nextDouble() * 20;
				if (score > bestScore) {
					bestScore = score;
					bx = cx;
					bz = cz;
				}
			}
		}
		if (bestScore < 0) {
			// No crossings: the first street, from its middle.
			if (!roads.eastWest().isEmpty()) {
				car.alongX = true;
				car.line = roads.eastWest().getFirst();
				BlockPos c = MapPoints.get(level).first(MapPoints.Type.CENTER);
				car.along = c == null ? 0 : c.getX();
			} else {
				car.alongX = false;
				car.line = roads.northSouth().getFirst();
				BlockPos c = MapPoints.get(level).first(MapPoints.Type.CENTER);
				car.along = c == null ? 0 : c.getZ();
			}
			car.dir = level.getRandom().nextBoolean() ? 1 : -1;
		} else {
			List<int[]> ways = new ArrayList<>();
			for (int s : new int[]{1, -1}) {
				if (car.road(bx + 0.5 + s * 8, bz + 0.5)) {
					ways.add(new int[]{1, s});
				}
				if (car.road(bx + 0.5, bz + 0.5 + s * 8)) {
					ways.add(new int[]{0, s});
				}
			}
			if (ways.isEmpty()) {
				return null;
			}
			int[] way = ways.get(level.getRandom().nextInt(ways.size()));
			car.alongX = way[0] == 1;
			car.dir = way[1];
			car.line = car.alongX ? bz : bx;
			car.along = (car.alongX ? bx : bz) + 0.5 + car.dir * 3;
		}
		car.place();
		car.build();
		return car;
	}

	private void place() {
		if (this.alongX) {
			this.x = this.along;
			this.z = this.line + 0.5 + LANE * this.dir;
		} else {
			this.z = this.along;
			this.x = this.line + 0.5 - LANE * this.dir;
		}
		Vec3 h = this.heading();
		this.yaw = (float) Math.toDegrees(Math.atan2(-h.x, h.z));
	}

	Vec3 heading() {
		return this.alongX ? new Vec3(this.dir, 0, 0) : new Vec3(0, 0, this.dir);
	}

	Vec3 position() {
		return new Vec3(this.x, this.roads.y + 1, this.z);
	}

	/** The car, block by block: +z is the front. */
	private void build() {
		BlockState white = block("white_concrete");
		BlockState black = block("black_concrete");
		// Body: white doors, black hood and trunk, black underneath.
		this.part(black, 0f, 0.35f, 0f, 2.1f, 0.45f, 4.9f, false);
		this.part(white, 0f, 0.8f, 0f, 2.2f, 0.55f, 4.8f, false);
		this.part(black, 0f, 1.1f, 1.75f, 2.22f, 0.08f, 1.3f, false);
		this.part(black, 0f, 1.1f, -1.85f, 2.22f, 0.08f, 1.1f, false);
		// The cabin: dark glass all round under a black roof.
		this.part(Blocks.TINTED_GLASS.defaultBlockState(), 0f, 1.4f, -0.25f, 1.95f, 0.55f, 2.2f, false);
		this.part(black, 0f, 1.71f, -0.25f, 2.0f, 0.08f, 2.3f, false);
		// Wheels.
		for (float sx : new float[]{-1.08f, 1.08f}) {
			for (float sz : new float[]{-1.55f, 1.6f}) {
				this.part(block("black_wool"), sx, 0.35f, sz, 0.3f, 0.7f, 0.7f, false);
			}
		}
		// Lights: the bar on the roof, headlights, tail lights.
		this.redLamp = this.part(block("red_concrete"), -0.42f, 1.85f, -0.25f, 0.7f, 0.2f, 0.42f, true);
		this.blueLamp = this.part(block("blue_concrete"), 0.42f, 1.85f, -0.25f, 0.7f, 0.2f, 0.42f, false);
		for (float sx : new float[]{-0.7f, 0.7f}) {
			this.part(block("white_concrete"), sx, 0.85f, 2.42f, 0.42f, 0.22f, 0.06f, true);
			this.part(block("red_concrete"), sx, 0.85f, -2.42f, 0.36f, 0.18f, 0.06f, true);
		}
		this.hitbox = new Interaction(EntityTypes.INTERACTION, this.level);
		this.hitbox.setWidth(3.0f);
		this.hitbox.setHeight(2.0f);
		this.hitbox.setResponse(true);
		this.hitbox.snapTo(this.x, this.roads.y + 1, this.z, 0f, 0f);
		this.hitbox.addTag(TAG);
		this.hitbox.addTag(Leftovers.stamp());
		this.level.addFreshEntity(this.hitbox);
	}

	private Display.BlockDisplay part(BlockState state, float cx, float cy, float cz, float sx, float sy, float sz, boolean lit) {
		Display.BlockDisplay d = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, this.level);
		d.setBlockState(state);
		d.setTransformation(new Transformation(new Vector3f(cx - sx / 2, cy - sy / 2, cz - sz / 2), null, new Vector3f(sx, sy, sz), null));
		d.setPosRotInterpolationDuration(3);
		d.setViewRange(3f);
		if (lit) {
			d.setBrightnessOverride(new Brightness(15, 15));
		}
		d.snapTo(this.x, this.roads.y + 1, this.z, this.yaw, 0f);
		d.addTag(TAG);
		d.addTag(Leftovers.stamp());
		this.level.addFreshEntity(d);
		this.parts.add(d);
		return d;
	}

	// ---------------------------------------------------------------- driving

	void tick() {
		HwConfig cfg = HwConfig.get();
		this.ticks++;
		if (this.waitTicks > 0) {
			this.waitTicks--;
		} else if (!this.blocked()) {
			this.drive(cfg.patrolSpeed);
		}
		this.place();
		double y = this.roads.y + 1;
		for (Display.BlockDisplay d : this.parts) {
			d.snapTo(this.x, y, this.z, this.yaw, 0f);
		}
		this.hitbox.snapTo(this.x, y, this.z, 0f, 0f);
		// The lights on the roof take turns.
		if (this.ticks % 6 == 0) {
			boolean red = this.ticks % 12 == 0;
			this.redLamp.setBrightnessOverride(new Brightness(red ? 15 : 2, red ? 15 : 0));
			this.blueLamp.setBrightnessOverride(new Brightness(red ? 2 : 15, red ? 0 : 15));
		}
		if (this.ticks % 20 == 0) {
			this.level.playSound(null, this.x, y, this.z, SoundEvents.MINECART_RIDING, SoundSource.BLOCKS, 0.7f, 0.45f);
		}
		this.headlights();
	}

	private void drive(double speed) {
		double before = this.along;
		this.along += speed * this.dir;
		List<Integer> crossings = this.alongX ? this.roads.northSouth() : this.roads.eastWest();
		for (int c : crossings) {
			double at = c + 0.5;
			if ((before < at && this.along >= at) || (before > at && this.along <= at)) {
				this.atCrossing(c);
				return;
			}
		}
		// The end of the street: round it goes.
		Vec3 ahead = this.position().add(this.heading().scale(5));
		if (!this.road(ahead.x, ahead.z)) {
			this.dir = -this.dir;
		}
	}

	/** At a crossing: straight on, or left or right where the other street goes on. */
	private void atCrossing(int c) {
		double cx = this.alongX ? c + 0.5 : this.line + 0.5;
		double cz = this.alongX ? this.line + 0.5 : c + 0.5;
		List<int[]> turns = new ArrayList<>();
		for (int s : new int[]{1, -1}) {
			boolean goes = this.alongX ? this.road(cx, cz + s * 8) : this.road(cx + s * 8, cz);
			if (goes) {
				turns.add(new int[]{s});
			}
		}
		boolean straight = this.alongX ? this.road(cx + this.dir * 8, cz) : this.road(cx, cz + this.dir * 8);
		var random = this.level.getRandom();
		if (!turns.isEmpty() && (!straight || random.nextFloat() < 0.5f)) {
			int s = turns.get(random.nextInt(turns.size()))[0];
			int oldLine = this.line;
			this.alongX = !this.alongX;
			this.line = c;
			this.dir = s;
			this.along = oldLine + 0.5 + s * 0.6;
		} else if (!straight) {
			this.dir = -this.dir;
		}
	}

	/** Someone on the road just ahead: it waits for them. */
	private boolean blocked() {
		Vec3 h = this.heading();
		Vec3 c = this.position().add(h.scale(3.6));
		double lx = this.alongX ? 2.0 : 1.4;
		double lz = this.alongX ? 1.4 : 2.0;
		AABB box = new AABB(c.x - lx, c.y, c.z - lz, c.x + lx, c.y + 2, c.z + lz);
		return !this.level.getEntitiesOfClass(LivingEntity.class, box, e -> e.isAlive() && !e.isSpectator()).isEmpty();
	}

	/** Light on the road ahead, moving with the car. */
	private void headlights() {
		Set<BlockPos> want = new HashSet<>();
		for (double ahead : new double[]{4.5, 8.5}) {
			Vec3 p = this.position().add(this.heading().scale(ahead));
			BlockPos at = BlockPos.containing(p.x, this.roads.y + 2, p.z);
			if (this.lights.contains(at) || this.level.getBlockState(at).isAir()) {
				want.add(at);
			}
		}
		for (BlockPos old : this.lights) {
			if (!want.contains(old) && this.level.getBlockState(old).is(Blocks.LIGHT)) {
				this.level.setBlock(old, Blocks.AIR.defaultBlockState(), 3);
			}
		}
		for (BlockPos at : want) {
			if (!this.lights.contains(at)) {
				this.level.setBlock(at, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 14), 3);
			}
		}
		this.lights.clear();
		this.lights.addAll(want);
	}

	/** Michael (not a shadow) in the headlights: in front, close, nothing in between. */
	ServerPlayer michaelInLights() {
		HwConfig cfg = HwConfig.get();
		Vec3 front = this.position().add(this.heading().scale(2.5)).add(0, 0.8, 0);
		for (ServerPlayer p : this.level.players()) {
			ShapeState st = MatchManager.role(p) == Role.SHAPE ? MatchManager.shapeState(p) : null;
			if (st == null || p.isSpectator() || st.shadowTicks > 0) {
				continue;
			}
			Vec3 body = FocusPoints.bodyCenter(p);
			if (body.distanceTo(front) <= cfg.patrolSightRange && Sight.angleTo(front, this.heading(), body) <= cfg.patrolSightAngle
				&& Sight.canSee(this.level, front, body, p)) {
				return p;
			}
		}
		return null;
	}

	/** Waved down: it pulls up for a few seconds. Where an officer steps out (the kerb side), or null if nowhere. */
	Vec3 pullUp() {
		this.waitTicks = 80;
		Vec3 right = this.alongX ? new Vec3(0, 0, this.dir) : new Vec3(-this.dir, 0, 0);
		for (double out : new double[]{2.6, 3.6, -2.6}) {
			BlockPos p = Nav.snap(this.level, BlockPos.containing(this.position().add(right.scale(out))));
			if (Nav.standable(this.level, p)) {
				return Vec3.atBottomCenterOf(p);
			}
		}
		return null;
	}

	boolean isMine(Entity e) {
		return e == this.hitbox;
	}

	void remove() {
		for (Display.BlockDisplay d : this.parts) {
			d.discard();
		}
		this.parts.clear();
		if (this.hitbox != null) {
			this.hitbox.discard();
		}
		for (BlockPos old : this.lights) {
			if (this.level.getBlockState(old).is(Blocks.LIGHT)) {
				this.level.setBlock(old, Blocks.AIR.defaultBlockState(), 3);
			}
		}
		this.lights.clear();
	}
}
