package dev.halloween.client;

import dev.halloween.HwSounds;
import dev.halloween.Role;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Michael's quick stride while he speeds up: real footsteps on whatever is underfoot (concrete and asphalt,
 * wood, grass and earth, carpet, snow), one every stride. Only he hears them; the plain step sounds stop
 * meanwhile so they don't double up.
 */
public final class DashSteps {
	/** Blocks per step at a fast walk. */
	private static final double STRIDE = 1.15;
	private static double walked;
	/** For tests: steps played since the start. */
	public static int played;

	private DashSteps() {
	}

	public static boolean dashing() {
		return ClientState.myRole() == Role.SHAPE && ClientState.hud != null && ClientState.hud.dashTicks() > 0;
	}

	public static void tick(Minecraft mc) {
		if (mc.player == null || !dashing()) {
			walked = STRIDE * 0.6;
			return;
		}
		if (!mc.player.onGround()) {
			return;
		}
		double dx = mc.player.getX() - mc.player.xo;
		double dz = mc.player.getZ() - mc.player.zo;
		walked += Math.sqrt(dx * dx + dz * dz);
		if (walked < STRIDE) {
			return;
		}
		walked -= STRIDE;
		BlockState under = mc.player.level().getBlockState(mc.player.getOnPos());
		float pitch = 0.86f + mc.player.getRandom().nextFloat() * 0.12f;
		mc.player.level().playLocalSound(mc.player.getX(), mc.player.getY(), mc.player.getZ(), surface(under), SoundSource.PLAYERS, 0.9f, pitch, false);
		played++;
	}

	private static SoundEvent surface(BlockState state) {
		SoundType type = state.getSoundType();
		if (type == SoundType.WOOD || type == SoundType.CHERRY_WOOD || type == SoundType.BAMBOO_WOOD || type == SoundType.NETHER_WOOD) {
			return HwSounds.DASH_STEP_WOOD;
		}
		if (type == SoundType.WOOL || type == SoundType.MOSS_CARPET) {
			return HwSounds.DASH_STEP_CARPET;
		}
		if (type == SoundType.GRASS || type == SoundType.ROOTED_DIRT || type == SoundType.MOSS || type == SoundType.AZALEA || type == SoundType.GRAVEL
			|| type == SoundType.SAND) {
			return HwSounds.DASH_STEP_GRASS;
		}
		if (type == SoundType.SNOW) {
			return HwSounds.DASH_STEP_SNOW;
		}
		return HwSounds.DASH_STEP_CONCRETE;
	}
}
