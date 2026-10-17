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
 * Gulls about the town, as the crows in Dead by Daylight: little flocks on the ground in the open, pecking and
 * looking about. Anyone who comes too near (a survivor, a townsperson, an officer, the killer himself, even unseen
 * in Shape Jump) puts them up: they take off with a clatter of wings (heard some 8 blocks off, nothing more: no
 * marker, no message) and fly off away from whoever it was, climbing, and are gone. Running scares them from
 * further than walking; crouching, one gets right up to them. A while later a new flock settles somewhere else.
 * Drawn by client.BirdRenderer on item displays (the item says which bird and whether it flies).
 */
public final class Birds {
	static final String TAG = "hw_bird";

	private static final class Bird {
		final Display.ItemDisplay body;
		final boolean young;
		Vec3 pos;
		float yaw;
		/** Flying: which way, and ticks in the air (-1: on the ground). */
		Vec3 away;
		int flying = -1;
		int nextTurn;

		Bird(Display.ItemDisplay body, boolean young, Vec3 pos, float yaw) {
			this.body = body;
			this.young = young;
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
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new net.minecraft.world.phys.AABB(BlockPos.containing(at)).inflate(cfg.birdScareRun + 1),
			e -> e.isAlive() && !e.isSpectator())) {
			double d = e.position().distanceTo(at);
			double moving = e.getDeltaMovement().horizontalDistance();
			if (e instanceof ServerPlayer p) {
				moving = p.isSprinting() ? 0.3 : moving;
			}
			double reach = e.isCrouching() ? cfg.birdScareCrouch : moving > 0.2 ? cfg.birdScareRun : cfg.birdScareWalk;
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
		b.body.setItemStack(stack(b.young ? "gull_young_flying" : "gull_flying"));
		flap(level, b.pos, r);
		AiStats.add("*total", "bird.flushed");
	}

	/** Wings, heard about 8 blocks off (volume 0.5). */
	private static void flap(ServerLevel level, Vec3 at, RandomSource r) {
		level.playSound(null, at.x, at.y, at.z, SoundEvents.PARROT_FLY, SoundSource.AMBIENT, 0.5f, 0.6f + r.nextFloat() * 0.2f);
		level.playSound(null, at.x, at.y, at.z, SoundEvents.PHANTOM_FLAP, SoundSource.AMBIENT, 0.35f, 1.6f + r.nextFloat() * 0.3f);
	}

	/** A new flock of one to four on open ground, away from everyone. */
	private static void settle(ServerLevel level, RandomSource r, int now) {
		BoundingBox area = Nav.area();
		if (area == null) {
			return;
		}
		for (int tries = 0; tries < 30; tries++) {
			int x = area.minX() + r.nextInt(Math.max(1, area.getXSpan()));
			int z = area.minZ() + r.nextInt(Math.max(1, area.getZSpan()));
			BlockPos top = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
			if (!Nav.standable(level, top) || !level.canSeeSky(top) || !level.getFluidState(top.below()).isEmpty()) {
				continue;
			}
			Vec3 at = Vec3.atBottomCenterOf(top);
			boolean near = false;
			for (ServerPlayer p : level.players()) {
				near |= p.position().distanceTo(at) < 14;
			}
			for (Bird b : BIRDS) {
				near |= b.pos.distanceTo(at) < 10;
			}
			if (near) {
				continue;
			}
			int n = 1 + r.nextInt(4);
			for (int k = 0; k < n; k++) {
				Vec3 p = at.add((r.nextFloat() - 0.5) * 2.5, 0, (r.nextFloat() - 0.5) * 2.5);
				BlockPos feet = Nav.snap(level, BlockPos.containing(p));
				if (!Nav.standable(level, feet)) {
					continue;
				}
				spawn(level, new Vec3(p.x, feet.getY(), p.z), r.nextFloat() < 0.3f, r.nextFloat() * 360f, now, r);
			}
			return;
		}
	}

	private static void spawn(ServerLevel level, Vec3 at, boolean young, float yaw, int now, RandomSource r) {
		Display.ItemDisplay d = new Display.ItemDisplay(net.minecraft.world.entity.EntityTypes.ITEM_DISPLAY, level);
		d.setItemStack(stack(young ? "gull_young" : "gull"));
		d.setItemTransform(ItemDisplayContext.FIXED);
		d.setViewRange(1f);
		d.setPos(at.x, at.y, at.z);
		d.setYRot(yaw);
		d.addTag(TAG);
		d.addTag(Leftovers.stamp());
		level.addFreshEntity(d);
		Bird b = new Bird(d, young, at, yaw);
		b.nextTurn = now + r.nextInt(100);
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
