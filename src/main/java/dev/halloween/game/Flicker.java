package dev.halloween.game;

import dev.halloween.HwConfig;
import dev.halloween.HwSounds;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CopperBulbBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The lights going mad (as in Stranger Things): while the Demogorgon claws his way up out of the Upside Down, every
 * lit bulb in the houses round about flickers, humming and fizzing like an overheating filament. Each house's bulbs
 * (one breaker's) stutter together; when it is over they come back on, unless the power went off meanwhile.
 */
public final class Flicker {
	/** How far round the spot he comes up the houses feel it. */
	private static final int RANGE = 24;
	private static final int HEIGHT = 8;
	/** At most this many bulbs a house hum and click (a room full of them would be a wall of noise). */
	private static final int LOUD_PER_HOUSE = 3;

	/** One house's lit bulbs, its breaker (null: none), whether they are on now and when they change next. */
	private static final class House {
		final BlockPos breaker;
		final List<BlockPos> bulbs = new ArrayList<>();
		boolean on = true;
		int next;

		House(BlockPos breaker) {
			this.breaker = breaker;
		}
	}

	private static final List<House> HOUSES = new ArrayList<>();
	private static int endAt = -1;
	private static int beganAt;

	private Flicker() {
	}

	/** The lights near this spot start flickering for {@code ticks}. */
	public static void start(ServerLevel level, Vec3 at, int ticks) {
		int now = level.getServer().getTickCount();
		if (Events.blackout()) {
			return;
		}
		if (!HOUSES.isEmpty()) {
			// Already flickering (a second crossing): it just goes on a little longer.
			endAt = Math.max(endAt, now + ticks);
			return;
		}
		int r = HwConfig.get().breakerRadius;
		BlockPos center = BlockPos.containing(at);
		Map<BlockPos, House> byBreaker = new HashMap<>();
		House loose = new House(null);
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-RANGE, -HEIGHT, -RANGE), center.offset(RANGE, HEIGHT, RANGE))) {
			BlockState state = level.getBlockState(pos);
			if (!(state.getBlock() instanceof CopperBulbBlock) || !state.getValue(CopperBulbBlock.LIT)) {
				continue;
			}
			BlockPos bulb = pos.immutable();
			BlockPos breaker = nearest(LightSystem.breakers(level, bulb, r), bulb);
			(breaker == null ? loose : byBreaker.computeIfAbsent(breaker, House::new)).bulbs.add(bulb);
		}
		HOUSES.addAll(byBreaker.values());
		if (!loose.bulbs.isEmpty()) {
			HOUSES.add(loose);
		}
		if (HOUSES.isEmpty()) {
			return;
		}
		beganAt = now;
		endAt = now + ticks;
		for (House h : HOUSES) {
			h.next = now + level.getRandom().nextInt(6);
		}
	}

	private static BlockPos nearest(List<BlockPos> breakers, BlockPos to) {
		BlockPos best = null;
		for (BlockPos b : breakers) {
			if (best == null || b.distSqr(to) < best.distSqr(to)) {
				best = b;
			}
		}
		return best;
	}

	public static void tick(ServerLevel level, int now) {
		if (HOUSES.isEmpty()) {
			return;
		}
		if (Events.blackout()) {
			// The whole town went dark meanwhile: nothing to flicker, nothing to put back on.
			HOUSES.clear();
			return;
		}
		if (now >= endAt) {
			restore(level, true);
			return;
		}
		RandomSource random = level.getRandom();
		// Worse the longer it goes on: short sputters at first, long dark gaps as he breaks through.
		float k = Math.min(1f, (now - beganAt) / (float) Math.max(1, endAt - beganAt));
		for (House h : HOUSES) {
			if (h.breaker != null && !LightSystem.isOn(level, h.breaker)) {
				// Somebody threw this house's breaker: its lights stay as the breaker left them.
				continue;
			}
			if ((now - beganAt) % 20 == 0) {
				// The filament straining: a high whine and a sizzle.
				for (int i = 0; i < Math.min(LOUD_PER_HOUSE, h.bulbs.size()); i++) {
					BlockPos b = h.bulbs.get(i);
					level.playSound(null, b, HwSounds.BULB_HUM, SoundSource.BLOCKS, 0.5f + 0.4f * k, 0.95f + random.nextFloat() * 0.15f);
				}
			}
			if (now < h.next) {
				continue;
			}
			h.on = !h.on;
			h.next = now + (h.on ? 1 + random.nextInt(Math.max(1, Math.round(8 - 6 * k))) : 1 + random.nextInt(2 + Math.round(6 * k)));
			set(level, h, h.on);
			for (int i = 0; i < Math.min(LOUD_PER_HOUSE, h.bulbs.size()); i++) {
				BlockPos b = h.bulbs.get(i);
				level.playSound(null, b, h.on ? HwSounds.BULB_CLICK : HwSounds.BULB_FIZZ, SoundSource.BLOCKS, h.on ? 0.4f : 0.6f, 0.9f + random.nextFloat() * 0.3f);
			}
		}
	}

	private static void set(ServerLevel level, House h, boolean on) {
		for (BlockPos b : h.bulbs) {
			BlockState state = level.getBlockState(b);
			if (state.getBlock() instanceof CopperBulbBlock && state.getValue(CopperBulbBlock.LIT) != on) {
				level.setBlock(b, state.setValue(CopperBulbBlock.LIT, on), Block.UPDATE_CLIENTS);
			}
		}
	}

	/** Back on as they were (where the breaker is still on, the town not blacked out). */
	private static void restore(ServerLevel level, boolean sound) {
		boolean dark = Events.blackout();
		for (House h : HOUSES) {
			if (dark || h.breaker != null && !LightSystem.isOn(level, h.breaker)) {
				continue;
			}
			set(level, h, true);
			if (sound && !h.bulbs.isEmpty()) {
				level.playSound(null, h.bulbs.get(0), HwSounds.BULB_CLICK, SoundSource.BLOCKS, 0.6f, 0.8f);
			}
		}
		HOUSES.clear();
		endAt = -1;
	}

	/** The match is over: every bulb this left dark is lit again. */
	public static void clear(ServerLevel level) {
		restore(level, false);
	}
}
