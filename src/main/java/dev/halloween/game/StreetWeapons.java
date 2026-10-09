package dev.halloween.game;

import dev.halloween.HwConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Weapons for hand-to-hand are found out of doors, not in drawers: an axe left stuck in a tree, a cleaver
 * driven into a wall, a bat dropped on a lawn. At the start of a match they are scattered round the town,
 * well apart; a survivor takes one with use. Firearms and everything else still turn up in furniture.
 */
public final class StreetWeapons {
	public static final String TAG = "hw_street_weapon";

	/** How it was left: lying on the ground, or stuck in a trunk or a wall facing {@code out}. */
	public enum How {
		GROUND, TREE, WALL
	}

	/** One weapon: where it is, where you stand to take it, what it is, its two entities (the look and the hitbox). */
	public record Spot(BlockPos pos, BlockPos stand, How how, HwItems.Kind kind, UUID display, UUID hitbox) {
	}

	private static final Map<UUID, Spot> BY_HITBOX = new LinkedHashMap<>();

	private StreetWeapons() {
	}

	private record Candidate(BlockPos pos, BlockPos stand, How how, Direction out) {
	}

	/** Lays this match's weapons out within {@code radius} of the centre. */
	public static void setup(ServerLevel level, BlockPos center, int radius, RandomSource random) {
		clear(level.getServer());
		HwConfig cfg = HwConfig.get();
		java.util.Random rng = new java.util.Random(random.nextLong());
		Map<How, List<Candidate>> found = new HashMap<>();
		for (How how : How.values()) {
			found.put(how, new ArrayList<>());
		}
		// Not where anybody starts the match (the centre, the spawns): you find them, you don't start on them.
		List<BlockPos> starts = new ArrayList<>();
		starts.add(center);
		for (MapPoints.Type t : new MapPoints.Type[]{MapPoints.Type.SPAWN_CIVILIAN, MapPoints.Type.SPAWN_SHAPE, MapPoints.Type.SPAWN_DEPUTY}) {
			starts.addAll(MapPoints.get(level).list(t));
		}
		int y = center.getY();
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
			for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
				p.set(x, y, z);
				// Inside the match area only (not in the woods past the town fence).
				if (!level.hasChunkAt(p) || !Nav.inArea(p) || !Nav.inArea(p.offset(-3, 0, -3)) || !Nav.inArea(p.offset(3, 0, 3))) {
					continue;
				}
				if (outdoorStand(level, p) && ground(level.getBlockState(p.below()))) {
					found.get(How.GROUND).add(new Candidate(p.immutable(), p.immutable(), How.GROUND, Direction.UP));
				}
				BlockPos chest = p.above();
				BlockState there = level.getBlockState(chest);
				boolean tree = there.is(BlockTags.LOGS) && level.getBlockState(p).is(BlockTags.LOGS);
				boolean wall = !tree && there.isCollisionShapeFullBlock(level, chest) && level.getBlockState(chest.above()).isCollisionShapeFullBlock(level, chest.above())
					&& level.getBlockState(p).isCollisionShapeFullBlock(level, p) && !Sight.isGlass(there) && !there.is(BlockTags.LEAVES)
					&& !SearchBlock.is(there) && !LockerBlock.is(there);
				if (!tree && !wall) {
					continue;
				}
				for (Direction d : Direction.Plane.HORIZONTAL) {
					BlockPos stand = p.relative(d);
					if (outdoorStand(level, stand) && level.getBlockState(chest.relative(d)).isAir()) {
						found.get(tree ? How.TREE : How.WALL).add(new Candidate(chest.immutable(), stand.immutable(), tree ? How.TREE : How.WALL, d));
					}
				}
			}
		}
		Map<String, Integer> weights = new LinkedHashMap<>();
		cfg.lootWeights.forEach((key, w) -> {
			for (HwItems.Kind kind : HwItems.Kind.values()) {
				if (kind.key().equals(key) && kind.melee()) {
					weights.put(key, w);
				}
			}
		});
		List<BlockPos> taken = new ArrayList<>();
		int spacing = cfg.streetWeaponSpacing;
		for (int i = 0; i < cfg.streetWeapons; i++) {
			HwItems.Kind kind = Loot.draw(weights, rng);
			if (kind == null) {
				break;
			}
			// Stuck in something more often than lying about.
			float roll = rng.nextFloat();
			How how = roll < 0.4f ? How.TREE : roll < 0.75f ? How.WALL : How.GROUND;
			Candidate pick = null;
			for (int tries = 0; tries < 60 && pick == null; tries++) {
				List<Candidate> list = found.get(tries < 30 ? how : How.values()[rng.nextInt(How.values().length)]);
				if (list.isEmpty()) {
					continue;
				}
				Candidate c = list.get(rng.nextInt(list.size()));
				boolean apart = true;
				for (BlockPos t : taken) {
					apart &= t.distSqr(c.pos) >= (double) spacing * spacing;
				}
				for (BlockPos s : starts) {
					apart &= s.distSqr(c.pos) >= 8 * 8;
				}
				if (apart) {
					pick = c;
				}
			}
			if (pick == null) {
				continue;
			}
			taken.add(pick.pos);
			place(level, pick, kind, rng);
		}
		logLaid(found);
	}

	private static void logLaid(Map<How, List<Candidate>> found) {
		dev.halloween.Halloween.LOGGER.info("Street weapons: {} laid out ({} spots on the ground, {} in trees, {} in walls)", BY_HITBOX.size(),
			found.get(How.GROUND).size(), found.get(How.TREE).size(), found.get(How.WALL).size());
	}

	/** Open air you can stand in: room for a body, solid underfoot, the sky overhead. */
	private static boolean outdoorStand(ServerLevel level, BlockPos p) {
		return level.getBlockState(p).getCollisionShape(level, p).isEmpty() && level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()
			&& level.getBlockState(p.below()).isCollisionShapeFullBlock(level, p.below()) && level.canSeeSky(p.above());
	}

	/** Yards and verges, not the road or the pavement. */
	private static boolean ground(BlockState under) {
		return under.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK) || under.is(net.minecraft.world.level.block.Blocks.DIRT)
			|| under.is(net.minecraft.world.level.block.Blocks.COARSE_DIRT) || under.is(net.minecraft.world.level.block.Blocks.PODZOL)
			|| under.is(net.minecraft.world.level.block.Blocks.ROOTED_DIRT);
	}

	private static void place(ServerLevel level, Candidate c, HwItems.Kind kind, java.util.Random rng) {
		Display.ItemDisplay look = new Display.ItemDisplay(EntityTypes.ITEM_DISPLAY, level);
		look.setItemStack(HwItems.create(kind));
		look.setItemTransform(ItemDisplayContext.FIXED);
		Vec3 at;
		Quaternionf turn;
		float scale = 0.8f;
		if (c.how == How.GROUND) {
			// Flat in the grass, any which way.
			at = new Vec3(c.pos.getX() + 0.5, c.pos.getY() + 0.04, c.pos.getZ() + 0.5);
			turn = new Quaternionf().rotateY(rng.nextFloat() * (float) Math.PI * 2).rotateX((float) -Math.PI / 2);
		} else {
			// The blade in the wood or the wall, the handle sticking out and a little down.
			Vec3 face = Vec3.atCenterOf(c.pos).add(c.out.getStepX() * 0.5, (rng.nextFloat() - 0.5) * 0.3, c.out.getStepZ() * 0.5);
			at = face.add(c.out.getStepX() * 0.22, 0, c.out.getStepZ() * 0.22);
			float yaw = (float) Math.atan2(-c.out.getStepZ(), c.out.getStepX());
			// The item lies in the x-y plane with its tip up and to +x (45 degrees): turned on to 195, so it points
			// along -x, 15 degrees down, give or take; then -x is turned to point into the wall.
			turn = new Quaternionf().rotateY(yaw).rotateZ((float) Math.toRadians(150 + rng.nextInt(20) - 10));
		}
		look.setTransformation(new com.mojang.math.Transformation(new Vector3f(), turn, new Vector3f(scale, scale, scale), null));
		look.snapTo(at.x, at.y, at.z, 0f, 0f);
		look.addTag(TAG);
		look.addTag(Leftovers.stamp());
		level.addFreshEntity(look);
		Interaction hitbox = new Interaction(EntityTypes.INTERACTION, level);
		hitbox.setWidth(0.8f);
		hitbox.setHeight(c.how == How.GROUND ? 0.35f : 0.8f);
		hitbox.setResponse(true);
		hitbox.snapTo(at.x, c.how == How.GROUND ? at.y - 0.04 : at.y - 0.4, at.z, 0f, 0f);
		hitbox.addTag(TAG);
		hitbox.addTag(Leftovers.stamp());
		level.addFreshEntity(hitbox);
		BY_HITBOX.put(hitbox.getUUID(), new Spot(c.pos, c.stand, c.how, kind, look.getUUID(), hitbox.getUUID()));
	}

	/** Use on one: a survivor takes it. True if {@code target} was one of ours. */
	public static boolean take(ServerPlayer player, Entity target) {
		Spot spot = BY_HITBOX.get(target.getUUID());
		if (spot == null) {
			return target.entityTags().contains(TAG);
		}
		if (!Match.running() || !MatchManager.role(player).survivor() || player.isSpectator() || MatchManager.isGrabbed(player)) {
			return true;
		}
		give(player, spot);
		return true;
	}

	/** A bot that came for one: takes the nearest within reach. */
	public static boolean takeNear(ServerPlayer player) {
		Spot best = null;
		for (Spot s : BY_HITBOX.values()) {
			if (Vec3.atCenterOf(s.pos).distanceTo(player.position()) < 3.2 && (best == null
				|| best.pos.distSqr(player.blockPosition()) > s.pos.distSqr(player.blockPosition()))) {
				best = s;
			}
		}
		if (best == null) {
			return false;
		}
		give(player, best);
		return true;
	}

	private static void give(ServerPlayer player, Spot spot) {
		ServerLevel level = player.level();
		BY_HITBOX.remove(spot.hitbox);
		for (UUID id : new UUID[]{spot.display, spot.hitbox}) {
			Entity e = level.getEntity(id);
			if (e != null) {
				e.discard();
			}
		}
		level.playSound(null, spot.pos, spot.how == How.GROUND ? SoundEvents.ITEM_PICKUP : SoundEvents.TRIDENT_RETURN, SoundSource.PLAYERS, 0.8f,
			spot.how == How.GROUND ? 1f : 0.7f);
		Inventory.give(player, HwItems.create(spot.kind));
		Component name = Component.translatable("halloween.item." + spot.kind.key());
		player.sendOverlayMessage(Component.translatable("halloween.hint.found", name).withStyle(ChatFormatting.GOLD));
		Match.score(player, HwConfig.get().scoreItemFound, Component.translatable("halloween.score.item_found", name));
		AiStats.add(player, "found.street_weapon");
	}

	/** What is still out there (bots, tests). */
	public static List<Spot> spots() {
		return new ArrayList<>(BY_HITBOX.values());
	}

	public static void clear(MinecraftServer server) {
		ServerLevel level = server.overworld();
		for (Spot s : BY_HITBOX.values()) {
			for (UUID id : new UUID[]{s.display, s.hitbox}) {
				Entity e = level.getEntity(id);
				if (e != null) {
					e.discard();
				}
			}
		}
		BY_HITBOX.clear();
		// Any from before (a crash, an unloaded corner) that are loaded now.
		for (Entity e : level.getAllEntities()) {
			if (e.entityTags().contains(TAG)) {
				e.discard();
			}
		}
	}
}
