package dev.halloween.game;

import dev.halloween.Halloween;
import dev.halloween.HwConfig;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

/**
 * Crows about the town, as in Dead by Daylight: little flocks up on roofs, posts, fences and lamps (now and then
 * on the ground), pecking and looking about. Anyone who comes too near (a survivor, a townsperson, an officer, the killer himself, even unseen
 * in Shape Jump) puts them up: they take off with a clatter of wings (heard some 8 blocks off, nothing more: no
 * marker, no message) and fly off away from whoever it was, climbing, and are gone. Running scares them from
 * further than walking; crouching, one gets right up to them. A while later a new flock settles somewhere else.
 * Drawn by client.BirdRenderer on item displays (the item says which bird and whether it flies).
 */
public final class Birds {
	static final String TAG = "hw_bird";

	private static final class Bird {
		final Display.ItemDisplay body;
		Vec3 pos;
		float yaw;
		/** Flying: which way, and ticks in the air (-1: on the ground). */
		Vec3 away;
		int flying = -1;
		int nextTurn;
		/** When it next caws (now and then, sitting). */
		int nextCaw;

		Bird(Display.ItemDisplay body, Vec3 pos, float yaw) {
			this.body = body;
			this.pos = pos;
			this.yaw = yaw;
		}
	}

	private static final List<Bird> BIRDS = new ArrayList<>();
	/** When to settle the next flock (one at a time, while there are fewer than wanted). */
	private static int nextFlock;

	private Birds() {
	}

	public static void tick(MinecraftServer server) {
		if (!Match.running()) {
			if (!BIRDS.isEmpty()) {
				clear(server.overworld());
			}
			return;
		}
		ServerLevel level = server.overworld();
		HwConfig cfg = HwConfig.get();
		int now = server.getTickCount();
		RandomSource r = level.getRandom();
		if (now >= nextFlock && flocks() < cfg.birdFlocks) {
			settle(level, r, now);
			nextFlock = now + (flocks() < cfg.birdFlocks / 2 ? 20 : cfg.birdFlockEveryTicks);
		}
		Iterator<Bird> it = BIRDS.iterator();
		while (it.hasNext()) {
			Bird b = it.next();
			if (b.body.isRemoved()) {
				it.remove();
				continue;
			}
			if (b.flying < 0) {
				LivingEntity scare = scarer(level, b.pos, cfg);
				if (scare != null) {
					takeOff(level, b, scare.position(), r);
					// The rest of its flock goes with it.
					for (Bird o : BIRDS) {
						if (o != b && o.flying < 0 && o.pos.distanceTo(b.pos) < 4) {
							takeOff(level, o, scare.position(), r);
						}
					}
				} else if (now >= b.nextCaw) {
					// A caw now and then, heard down the street (about twelve blocks).
					b.nextCaw = now + 400 + r.nextInt(900);
					level.playSound(null, b.pos.x, b.pos.y, b.pos.z, dev.halloween.HwSounds.CROW_CAW, SoundSource.AMBIENT, 0.75f, 0.9f + r.nextFloat() * 0.2f);
					AiStats.add("*total", "bird.caw");
				} else if (now >= b.nextTurn) {
					// Hopping round to look another way now and then.
					b.yaw += (r.nextFloat() - 0.5f) * 120f;
					b.body.setYRot(b.yaw);
					b.nextTurn = now + 40 + r.nextInt(100);
				}
				continue;
			}
			b.flying++;
			// Off and up: steep at first, then away level, wings beating.
			double climb = b.flying < 12 ? 0.32 : 0.16;
			double speed = Math.min(0.55, 0.15 + b.flying * 0.03);
			b.pos = b.pos.add(b.away.x * speed, climb, b.away.z * speed);
			b.body.setPosRotInterpolationDuration(2);
			b.body.setPos(b.pos.x, b.pos.y, b.pos.z);
			b.body.setYRot(b.yaw);
			if (b.flying % 6 == 0 && b.flying < 30) {
				flap(level, b.pos, r);
			}
			if (b.flying > 90) {
				b.body.discard();
				it.remove();
			}
		}
	}

	private static int flocks() {
		int n = 0;
		List<Vec3> seen = new ArrayList<>();
		for (Bird b : BIRDS) {
			if (b.flying >= 0) {
				continue;
			}
			boolean counted = false;
			for (Vec3 s : seen) {
				counted |= s.distanceTo(b.pos) < 4;
			}
			if (!counted) {
				seen.add(b.pos);
				n++;
			}
		}
		return n;
	}

	/** Who is too near this bird: running further off, crouching hardly at all. */
	private static LivingEntity scarer(ServerLevel level, Vec3 at, HwConfig cfg) {
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new net.minecraft.world.phys.AABB(BlockPos.containing(at)).inflate(cfg.birdScareRun + 1, 17, cfg.birdScareRun + 1),
			e -> e.isAlive() && !e.isSpectator())) {
			// Along the ground: someone passing under the roof or the lamp it sits on puts it up too.
			double d = Math.hypot(e.getX() - at.x, e.getZ() - at.z);
			if (Math.abs(e.getY() - at.y) > 16) {
				continue;
			}
			double moving = e.getDeltaMovement().horizontalDistance();
			if (e instanceof ServerPlayer p) {
				moving = p.isSprinting() ? 0.3 : moving;
			}
			if (e.isCrouching() || e.isShiftKeyDown()) {
				// Creeping up crouched: they let anyone by, however near.
				continue;
			}
			double reach = moving > 0.2 ? cfg.birdScareRun : cfg.birdScareWalk;
			if (d <= reach) {
				return e;
			}
		}
		return null;
	}

	private static void takeOff(ServerLevel level, Bird b, Vec3 from, RandomSource r) {
		Vec3 away = b.pos.subtract(from).multiply(1, 0, 1);
		away = away.lengthSqr() < 1.0E-4 ? new Vec3(r.nextGaussian(), 0, r.nextGaussian()) : away;
		// Off at an angle, each its own way a little.
		double turn = (r.nextFloat() - 0.5) * 1.2;
		away = new Vec3(away.x * Math.cos(turn) - away.z * Math.sin(turn), 0, away.x * Math.sin(turn) + away.z * Math.cos(turn)).normalize();
		b.away = away;
		b.flying = 0;
		b.yaw = (float) (Mth.atan2(-away.x, away.z) * Mth.RAD_TO_DEG);
		b.body.setItemStack(stack("crow_flying"));
		flap(level, b.pos, r);
		// A caw as it goes up, heard some thirty blocks off.
		level.playSound(null, b.pos.x, b.pos.y, b.pos.z, dev.halloween.HwSounds.CROW_CAW, SoundSource.AMBIENT, HwConfig.get().birdCawRange / 16f, 0.9f + r.nextFloat() * 0.2f);
		AiStats.add("*total", "bird.flushed");
	}

	/** Wings, heard about 8 blocks off (volume 0.5). */
	private static void flap(ServerLevel level, Vec3 at, RandomSource r) {
		level.playSound(null, at.x, at.y, at.z, SoundEvents.PARROT_FLY, SoundSource.AMBIENT, 0.5f, 0.6f + r.nextFloat() * 0.2f);
		level.playSound(null, at.x, at.y, at.z, SoundEvents.PHANTOM_FLAP, SoundSource.AMBIENT, 0.35f, 1.6f + r.nextFloat() * 0.3f);
	}

	/**
	 * A new flock, away from everyone: mostly up somewhere (a roof's ridge, a post, a fence, a lamp, a wall top: a
	 * top higher than the ground round it), now and then down on open ground. One to three birds, along the same
	 * perch (a ridge, a fence) where there is room for them.
	 */
	private static void settle(ServerLevel level, RandomSource r, int now) {
		BoundingBox area = Nav.area();
		if (area == null) {
			return;
		}
		boolean ground = r.nextFloat() < 0.3f;
		// Round someone (where the town is loaded: a column far from everyone has no blocks to sit on yet), 16 to 70
		// blocks off, inside the town.
		List<ServerPlayer> people = new ArrayList<>();
		for (ServerPlayer p : level.players()) {
			if (!p.isSpectator()) {
				people.add(p);
			}
		}
		if (people.isEmpty()) {
			return;
		}
		for (int tries = 0; tries < 60; tries++) {
			ServerPlayer round = people.get(r.nextInt(people.size()));
			double a = r.nextDouble() * Math.PI * 2;
			double d = 14 + r.nextDouble() * 30;
			int x = (int) Math.floor(round.getX() + Math.cos(a) * d);
			int z = (int) Math.floor(round.getZ() + Math.sin(a) * d);
			if (!area.isInside(x, area.minY(), z) || !level.hasChunkAt(new BlockPos(x, round.getBlockY(), z))) {
				continue;
			}
			Vec3 at = perch(level, x, z, ground);
			if (at == null || !ground && tries < 40 && at.y - round.getY() > 5) {
				// (Low ones first: fences, lamps, porches, sheds; a high ridge only if nothing lower turns up.)
				continue;
			}
			boolean near = false;
			for (ServerPlayer p : level.players()) {
				near |= p.position().distanceTo(at) < 13;
			}
			for (Bird b : BIRDS) {
				near |= b.pos.distanceTo(at) < 10;
			}
			if (near) {
				continue;
			}
			float yaw = r.nextFloat() * 360f;
			spawn(level, at, yaw, now, r);
			int more = r.nextInt(3);
			for (int k = 0; k < 12 && more > 0; k++) {
				Vec3 o = perch(level, x + r.nextInt(5) - 2, z + r.nextInt(5) - 2, ground);
				if (o != null && Math.abs(o.y - at.y) < 0.6 && o.distanceTo(at) > 0.7) {
					spawn(level, o, yaw + (r.nextFloat() - 0.5f) * 60f, now, r);
					more--;
				}
			}
			return;
		}
	}

	/** Where a bird can sit at this column: the top of what stands there, high up (or on the ground), open to the sky. */
	private static Vec3 perch(ServerLevel level, int x, int z, boolean ground) {
		BlockPos top = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z)).below();
		net.minecraft.world.level.block.state.BlockState state = level.getBlockState(top);
		if (state.isAir() || !level.getFluidState(top).isEmpty() || !level.getBlockState(top.above()).isAir()) {
			return null;
		}
		net.minecraft.world.phys.shapes.VoxelShape shape = state.getCollisionShape(level, top);
		if (shape.isEmpty()) {
			return null;
		}
		double y = top.getY() + shape.max(net.minecraft.core.Direction.Axis.Y);
		// How high it stands over the ground round it.
		int low = Integer.MAX_VALUE;
		for (int[] d : new int[][]{{2, 0}, {-2, 0}, {0, 2}, {0, -2}, {3, 3}, {-3, -3}}) {
			low = Math.min(low, level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x + d[0], 0, z + d[1])).getY());
		}
		double over = y - low;
		if (ground ? over > 0.6 || !Nav.standable(level, top.above()) : over < 1.4) {
			return null;
		}
		return new Vec3(x + 0.5, y, z + 0.5);
	}

	private static void spawn(ServerLevel level, Vec3 at, float yaw, int now, RandomSource r) {
		Display.ItemDisplay d = new Display.ItemDisplay(net.minecraft.world.entity.EntityTypes.ITEM_DISPLAY, level);
		d.setItemStack(stack("crow"));
		d.setItemTransform(ItemDisplayContext.FIXED);
		d.setViewRange(1f);
		d.setPos(at.x, at.y, at.z);
		d.setYRot(yaw);
		d.addTag(TAG);
		d.addTag(Leftovers.stamp());
		level.addFreshEntity(d);
		Bird b = new Bird(d, at, yaw);
		b.nextTurn = now + r.nextInt(100);
		b.nextCaw = now + 100 + r.nextInt(900);
		BIRDS.add(b);
	}

	private static ItemStack stack(String model) {
		ItemStack s = new ItemStack(Items.PAPER);
		s.set(DataComponents.ITEM_MODEL, Halloween.id(model));
		return s;
	}

	public static void clear(ServerLevel level) {
		for (Bird b : BIRDS) {
			b.body.discard();
		}
		BIRDS.clear();
		nextFlock = 0;
		level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack().withSuppressedOutput(), "kill @e[tag=" + TAG + "]");
	}

	/** For tests: birds on the ground, and in the air. */
	public static int grounded() {
		return (int) BIRDS.stream().filter(b -> b.flying < 0).count();
	}

	public static int flying() {
		return (int) BIRDS.stream().filter(b -> b.flying >= 0).count();
	}

	public static List<Vec3> positions() {
		return BIRDS.stream().filter(b -> b.flying < 0).map(b -> b.pos).toList();
	}
}
