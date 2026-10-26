package dev.halloween.game;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** The lobby's little farm: 5x5 with water in the middle; ripe crops pay a few points, then a random seed goes in. */
public final class Farm {
	/** The farm's corner in lobby coordinates (the yard, east of the path). */
	static final int X0 = 57;
	static final int Z0 = 12;
	static final int SIZE = 5;
	private static final int GROW = 20 * 120;
	private static final int SPREAD = 20 * 20;

	private record Crop(String block, int maxAge, int points) {
	}

	private static final Crop[] CROPS = {
		new Crop("wheat", 7, 10), new Crop("potatoes", 7, 20), new Crop("carrots", 7, 35), new Crop("beetroots", 3, 50)};

	private record Plot(Crop crop, int planted, int grow) {
	}

	private static final Map<BlockPos, Plot> PLOTS = new HashMap<>();

	private Farm() {
	}

	static void build(ServerLevel level, BlockPos o) {
		PLOTS.clear();
		// A log rim, the field sunk into it, water at the heart.
		Lobby.fill(level, o, X0 - 1, -1, Z0 - 1, X0 + SIZE, -1, Z0 + SIZE, MapPlan.b("stripped_spruce_log"));
		BlockPos water = o.offset(X0 + SIZE / 2, -1, Z0 + SIZE / 2);
		level.setBlock(water, MapPlan.b("water"), Block.UPDATE_ALL);
		level.setBlock(water.above(), MapPlan.b("lily_pad"), Block.UPDATE_ALL);
		sow(level, o);
		BlockPos sign = o.offset(X0 + SIZE / 2, 0, Z0 - 2);
		level.setBlock(sign, MapPlan.b("dark_oak_sign").setValue(StandingSignBlock.ROTATION, 8), Block.UPDATE_CLIENTS);
		Lobby.text(level, sign,
			Component.translatable("halloween.lobby.farm").withStyle(ChatFormatting.BOLD), Component.translatable("halloween.lobby.farm2"));
	}

	/** The whole field anew (a fresh build, or a restart forgot it), at mixed ages so there's something to pick at once. */
	private static void sow(ServerLevel level, BlockPos o) {
		int now = level.getServer().getTickCount();
		for (int x = 0; x < SIZE; x++) {
			for (int z = 0; z < SIZE; z++) {
				if (x != SIZE / 2 || z != SIZE / 2) {
					plant(level, o.offset(X0 + x, 0, Z0 + z), now - level.getRandom().nextInt(GROW + SPREAD));
				}
			}
		}
	}

	private static void plant(ServerLevel level, BlockPos crop, int planted) {
		RandomSource r = level.getRandom();
		Crop c = CROPS[r.nextInt(CROPS.length)];
		PLOTS.put(crop.immutable(), new Plot(c, planted, GROW - SPREAD + r.nextInt(SPREAD * 2 + 1)));
		show(level, crop, PLOTS.get(crop), level.getServer().getTickCount());
	}

	private static int age(Plot p, int now) {
		return Math.min(p.crop.maxAge, (int) ((long) Math.max(0, now - p.planted) * (p.crop.maxAge + 1) / p.grow));
	}

	private static void show(ServerLevel level, BlockPos crop, Plot p, int now) {
		BlockState soil = MapPlan.b("farmland").setValue(BlockStateProperties.MOISTURE, 7);
		if (level.getBlockState(crop.below()) != soil) {
			level.setBlock(crop.below(), soil, Block.UPDATE_CLIENTS);
		}
		BlockState want = ((CropBlock) MapPlan.b(p.crop.block).getBlock()).getStateForAge(age(p, now));
		if (level.getBlockState(crop) != want) {
			level.setBlock(crop, want, Block.UPDATE_CLIENTS);
		}
	}

	/** Each second: crops follow our clock, not the random ticks, and trampled soil comes back. */
	static void tick(ServerLevel level, BlockPos o, int now) {
		if (now % 20 != 0 || !level.isLoaded(o.offset(X0, 0, Z0))) {
			return;
		}
		if (PLOTS.isEmpty()) {
			sow(level, o);
		}
		for (Map.Entry<BlockPos, Plot> e : PLOTS.entrySet()) {
			if (level.isLoaded(e.getKey())) {
				show(level, e.getKey(), e.getValue(), now);
			}
		}
	}

	/** A hit on a farm crop: ripe ones are sold and replanted. Returns whether it was ours. */
	static boolean harvest(ServerPlayer player, BlockPos pos, Lobby data) {
		Plot p = PLOTS.get(pos);
		if (p == null) {
			return false;
		}
		ServerLevel level = (ServerLevel) player.level();
		int now = level.getServer().getTickCount();
		if (age(p, now) < p.crop.maxAge) {
			player.sendOverlayMessage(Component.translatable("halloween.lobby.farm_unripe").withStyle(ChatFormatting.GRAY));
			return true;
		}
		int total = data.addFarmPoints(player.getScoreboardName(), p.crop.points);
		level.playSound(null, pos, SoundEvents.CROP_BREAK, SoundSource.BLOCKS, 1f, 1f);
		level.playSound(null, pos, SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.5f, 1.2f);
		level.sendParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 6, 0.3, 0.3, 0.3, 0);
		player.sendOverlayMessage(Component.translatable("halloween.lobby.farm_sold", p.crop.points, total).withStyle(ChatFormatting.GREEN));
		plant(level, pos, now);
		return true;
	}

	/** Whether this school has its farm yet (the water at its heart). */
	static boolean built(ServerLevel level, BlockPos o) {
		return level.getBlockState(o.offset(X0 + SIZE / 2, -1, Z0 + SIZE / 2)).is(net.minecraft.world.level.block.Blocks.WATER);
	}

	/** Client and server: a left click on any crop goes to the server even in adventure mode. */
	public static boolean isCrop(BlockState state) {
		return state.getBlock() instanceof CropBlock;
	}
}
