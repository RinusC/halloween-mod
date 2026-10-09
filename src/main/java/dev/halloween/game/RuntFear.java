package dev.halloween.game;

import dev.halloween.HwConfig;
import dev.halloween.HwSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The Runt's fear from knocking: on the door or a window of a house, for everyone near (survivors and
 * townsfolk) to hear. (The rest of his fear comes from his pounce, see Runt.)
 */
final class RuntFear {
	private RuntFear() {
	}

	static void clear() {
	}

	/** Knocking on a door or a window: the people in the house hear it. True if anyone did. */
	static boolean knock(ServerPlayer runt, Runt.State s, BlockPos pos) {
		HwConfig cfg = HwConfig.get();
		ServerLevel level = runt.level();
		BlockState st = level.getBlockState(pos);
		boolean door = st.getBlock() instanceof DoorBlock;
		if (s.knockCooldown > 0 || s.busy() || !door && !Windows.isWindow(st) || Vec3.atCenterOf(pos).distanceTo(runt.getEyePosition()) > 3.5) {
			return false;
		}
		s.knockCooldown = cfg.runtKnockCooldownTicks;
		Vec3 c = Vec3.atCenterOf(pos);
		if (door) {
			for (int i = 0; i < 3; i++) {
				level.playSound(null, c.x, c.y, c.z, HwSounds.DOOR_HIT, SoundSource.HOSTILE, 0.9f, 1.25f);
			}
		} else {
			// A blow on the glass sounds like Michael smashing a window (the pane holds, though).
			level.playSound(null, c.x, c.y, c.z, HwSounds.WINDOW_SMASH, SoundSource.BLOCKS, 2.5f, 0.9f);
		}
		int heard = 0;
		for (ServerPlayer v : level.players()) {
			if (MatchManager.role(v).survivor() && !v.isSpectator() && v.position().distanceTo(c) <= cfg.runtKnockRange) {
				Panic.add(v, cfg.runtKnockPanic);
				heard++;
			}
		}
		// The townsfolk in there jump too.
		for (net.minecraft.world.entity.decoration.Mannequin npc : Townsfolk.bodies()) {
			if (npc.position().distanceTo(c) <= cfg.runtKnockRange) {
				heard++;
			}
		}
		if (heard > 0) {
			Townsfolk.hearNoise(level, c, level.getServer().getTickCount());
		}
		if (heard > 0) {
			Runt.gain(runt, s, cfg.runtFearKnock * Math.min(3, heard));
		}
		AiStats.add(runt, "runt.fear.knock");
		return heard > 0;
	}
}
