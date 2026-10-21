package dev.halloween.game;

import dev.halloween.HwBlocks;
import dev.halloween.HwConfig;
import dev.halloween.HwSounds;
import dev.halloween.Role;
import dev.halloween.net.HwNet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Civilians climbing through windows. Slow: ~3 s and harmless through intact glass. Fast: ~1 s, smashes
 * the glass and costs a fifth of max health (refused when that much health isn't left). Once a window is
 * broken, its shards cut on every pass, slow or fast (a slow pass through shards never kills).
 * The Shape climbs no windows, but in Shape Jump he slips through one slowly (the glass stays whole).
 * Broken panes are remembered so a reset can restore them.
 */
public final class Windows {
	private static final Map<UUID, Vault> VAULTS = new HashMap<>();
	/** Broken pane positions and the glass that was there, for /hw reset. */

	private static final class Vault {
		final BlockPos window;
		final int bottomY;
		final boolean fast;
		final boolean cuts;
		final int duration;
		final Vec3 start;
		final Vec3 approach;
		final Vec3 exit;
		final Vec3 landing;
		final double sill;
		int ticks;
		boolean glassDone;

		Vault(BlockPos window, int bottomY, boolean fast, boolean cuts, int duration, Vec3 start, Vec3 approach, Vec3 exit, Vec3 landing, double sill) {
			this.window = window;
			this.bottomY = bottomY;
			this.fast = fast;
			this.cuts = cuts;
			this.duration = duration;
			this.start = start;
			this.approach = approach;
			this.exit = exit;
			this.landing = landing;
			this.sill = sill;
		}
	}

	private Windows() {
	}

	public static boolean isWindow(BlockState state) {
		return Sight.isGlass(state);
	}

	public static boolean isBrokenPane(BlockState state) {
		return state.is(HwBlocks.BROKEN_GLASS_PANE);
	}

	public static boolean isVaulting(ServerPlayer player) {
		return VAULTS.containsKey(player.getUUID());
	}

	/** Shards cut for a fifth of max health. */
	public static float cutDamage(ServerPlayer player) {
		return player.getMaxHealth() * HwConfig.get().vaultDamageFraction * (Perks.has(player, Perks.Perk.GLASS_WALKER) ? HwConfig.get().perkGlassWalkerFactor : 1f);
	}

	// ---------------------------------------------------------------- start

	public static void request(ServerPlayer player, BlockPos clicked, boolean fast) {
		ServerLevel level = player.level();
		HwConfig cfg = HwConfig.get();
		boolean shadow = shadow(player);
		if (!MatchManager.role(player).survivor() && !shadow || player.isSpectator() || isVaulting(player)
			|| MatchManager.isGrabbed(player) || Vecna.held(player) || !isWindow(level.getBlockState(clicked))) {
			return;
		}
		fast &= !shadow;
		if (player.getEyePosition().distanceTo(Vec3.atCenterOf(clicked)) > 3.2) {
			return;
		}
		if (fast && !shadow && player.getHealth() <= cutDamage(player)) {
			player.sendOverlayMessage(Component.translatable("halloween.msg.vault_fast_low_hp").withStyle(ChatFormatting.GRAY));
			return;
		}

		// Whole glass column of this window and the sill under it.
		int bottomY = clicked.getY();
		while (bottomY > level.getMinY() && isWindow(level.getBlockState(new BlockPos(clicked.getX(), bottomY - 1, clicked.getZ())))) {
			bottomY--;
		}
		BlockPos bottom = new BlockPos(clicked.getX(), bottomY, clicked.getZ());
		Vec3 center = Vec3.atBottomCenterOf(bottom);
		Vec3 dir = crossing(level.getBlockState(bottom), center, player.position());
		double feetY = player.getY();
		double sill = Math.max(0, bottomY - feetY);
		if (sill > 1.6) {
			return;
		}
		Vec3 approach = new Vec3(center.x - dir.x * 0.75, feetY, center.z - dir.z * 0.75);
		Vec3 exit = new Vec3(center.x + dir.x * 0.75, feetY, center.z + dir.z * 0.75);
		Vec3 landing = new Vec3(center.x + dir.x * 1.3, feetY, center.z + dir.z * 1.3);
		AABB box = player.getDimensions(player.getPose()).makeBoundingBox(landing);
		if (!level.noCollision(player, box)) {
			player.sendOverlayMessage(Component.translatable("halloween.msg.vault_blocked").withStyle(ChatFormatting.GRAY));
			return;
		}
		boolean broken = isBrokenPane(level.getBlockState(bottom));
		int duration = shadow ? cfg.shapeWindowTicks : fast ? cfg.vaultFastTicks : cfg.vaultSlowTicks;
		VAULTS.put(player.getUUID(), new Vault(bottom, bottomY, fast, !shadow && (fast || broken), duration, player.position(), approach, exit, landing, sill));
		player.setSprinting(false);
	}

	/** The Shape in Shape Jump: a shadow that slips through glass. */
	private static boolean shadow(ServerPlayer player) {
		ShapeState state = MatchManager.shapeState(player);
		return MatchManager.role(player) == Role.SHAPE && state != null && state.shadowTicks > 0;
	}

	/** Unit vector across the wall, pointing away from the player's side. */
	private static Vec3 crossing(BlockState glass, Vec3 center, Vec3 player) {
		boolean alongX = false;
		boolean alongZ = false;
		if (glass.getBlock() instanceof IronBarsBlock) {
			alongX = glass.getValue(BlockStateProperties.EAST) || glass.getValue(BlockStateProperties.WEST);
			alongZ = glass.getValue(BlockStateProperties.NORTH) || glass.getValue(BlockStateProperties.SOUTH);
		}
		double dx = center.x - player.x;
		double dz = center.z - player.z;
		boolean crossZ;
		if (alongX != alongZ) {
			crossZ = alongX;
		} else {
			crossZ = Math.abs(dz) >= Math.abs(dx);
		}
		return crossZ ? new Vec3(0, 0, Math.signum(dz) == 0 ? 1 : Math.signum(dz)) : new Vec3(Math.signum(dx) == 0 ? 1 : Math.signum(dx), 0, 0);
	}

	// ---------------------------------------------------------------- tick

	public static void tick(MinecraftServer server) {
		Iterator<Map.Entry<UUID, Vault>> it = VAULTS.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Vault> entry = it.next();
			Vault v = entry.getValue();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player == null || player.isSpectator() || !MatchManager.role(player).survivor() && !shadow(player) || MatchManager.isGrabbed(player)) {
				it.remove();
				if (player != null) {
					ServerPlayNetworking.send(player, HwNet.Progress.NONE);
				}
				continue;
			}
			v.ticks++;
			float t = Math.min(1f, v.ticks / (float) v.duration);
			player.teleportTo(player.level(), pathX(v, t), pathY(v, t), pathZ(v, t), Relative.ROTATION, 0f, 0f, false);
			player.setDeltaMovement(Vec3.ZERO);
			player.fallDistance = 0;

			if (!v.glassDone && t >= 0.4f) {
				v.glassDone = true;
				throughGlass(player, v);
			}
			if (t >= 1f) {
				it.remove();
				ServerPlayNetworking.send(player, HwNet.Progress.NONE);
			} else if (v.ticks % 2 == 0) {
				ServerPlayNetworking.send(player, new HwNet.Progress(v.fast ? "halloween.hud.progress.vault_fast" : "halloween.hud.progress.vault_slow", t, v.fast || v.cuts));
			}
		}
	}

	// Path: step up to the window, cross over the sill, drop down on the other side.
	private static double pathX(Vault v, float t) {
		return lerp3(v.start.x, v.approach.x, v.exit.x, v.landing.x, t);
	}

	private static double pathZ(Vault v, float t) {
		return lerp3(v.start.z, v.approach.z, v.exit.z, v.landing.z, t);
	}

	private static double pathY(Vault v, float t) {
		double base = v.start.y;
		double top = base + v.sill + 0.05;
		if (t < 0.25f) {
			return Mth.lerp(t / 0.25f, base, top);
		}
		if (t < 0.75f) {
			return top + Math.sin((t - 0.25f) / 0.5f * Math.PI) * 0.1;
		}
		return Mth.lerp((t - 0.75f) / 0.25f, top, base);
	}

	private static double lerp3(double a, double b, double c, double d, float t) {
		if (t < 0.25f) {
			return Mth.lerp(t / 0.25f, a, b);
		}
		if (t < 0.75f) {
			return Mth.lerp((t - 0.25f) / 0.5f, b, c);
		}
		return Mth.lerp((t - 0.75f) / 0.25f, c, d);
	}

	private static void throughGlass(ServerPlayer player, Vault v) {
		ServerLevel level = player.level();
		if (v.fast) {
			breakColumn(level, v.window);
		}
		if (!v.cuts) {
			return;
		}
		float damage = cutDamage(player);
		// A slow climb through shards hurts but never kills.
		if (!v.fast) {
			damage = Math.min(damage, player.getHealth() - 1f);
		}
		Vec3 c = Vec3.atCenterOf(v.window);
		level.playSound(null, c.x, c.y, c.z, HwSounds.SHARDS, SoundSource.PLAYERS, 1f, 1f);
		if (damage > 0) {
			player.hurtServer(level, player.damageSources().generic(), damage);
			MatchManager.bleed(level, player, 10);
		}
	}

	// ---------------------------------------------------------------- broken glass

	/** Turns the glass column at {@code pos} into broken panes with a loud smash. */
	public static void breakColumn(ServerLevel level, BlockPos pos) {
		boolean any = false;
		// The window may be two panes wide: take the neighbouring column of the same window too.
		java.util.List<BlockPos> cells = new java.util.ArrayList<>();
		for (BlockPos column : new BlockPos[]{pos, pos.east(), pos.west(), pos.north(), pos.south()}) {
			if (column == pos || isWindow(level.getBlockState(column))) {
				for (int dy = -3; dy <= 3; dy++) {
					cells.add(column.above(dy));
				}
			}
		}
		for (BlockPos p : cells) {
			BlockState state = level.getBlockState(p);
			if (!isWindow(state) || isBrokenPane(state)) {
				continue;
			}
			BrokenWindows.get(level.getServer()).add(p, state);
			BlockState broken = HwBlocks.BROKEN_GLASS_PANE.defaultBlockState();
			if (state.getBlock() instanceof IronBarsBlock) {
				for (var prop : new net.minecraft.world.level.block.state.properties.BooleanProperty[]{
					BlockStateProperties.NORTH, BlockStateProperties.EAST, BlockStateProperties.SOUTH, BlockStateProperties.WEST, BlockStateProperties.WATERLOGGED}) {
					broken = broken.setValue(prop, state.getValue(prop));
				}
			}
			level.setBlock(p, broken, Block.UPDATE_ALL);
			Vec3 c = Vec3.atCenterOf(p);
			level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), c.x, c.y, c.z, 25, 0.3, 0.4, 0.3, 0.15);
			any = true;
		}
		if (any) {
			level.playSound(null, pos, HwSounds.WINDOW_SMASH, SoundSource.BLOCKS, 2.5f, 0.9f);
		}
	}

	public static void clear(MinecraftServer server) {
		// Kept with the world: a game closed mid-match still gets its windows back next time.
		BrokenWindows saved = BrokenWindows.get(server);
		for (Map.Entry<BlockPos, BlockState> e : saved.panes().entrySet()) {
			ServerLevel level = server.overworld();
			if (isBrokenPane(level.getBlockState(e.getKey()))) {
				level.setBlock(e.getKey(), e.getValue(), Block.UPDATE_ALL);
			}
		}
		saved.clear();
		VAULTS.clear();
	}
}
