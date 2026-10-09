package dev.halloween.game;

import dev.halloween.HwConfig;
import dev.halloween.Role;
import dev.halloween.net.HwNet;
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
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Survivors' traps, found in furniture and set down at their feet (use). A bear trap snaps shut on Michael's leg
 * and holds him where he stands for a few seconds; a string of tin cans rattles when he walks through it, and
 * every survivor sees where. Each goes off once; neither touches anyone else, nor Michael in Shape Jump.
 */
public final class Traps {
	public enum Kind {
		BEAR_TRAP, CANS
	}

	private record Trap(Kind kind, UUID owner, BlockState under) {
	}

	private static final Map<BlockPos, Trap> TRAPS = new LinkedHashMap<>();

	private Traps() {
	}

	/** Sets the trap in hand down where the survivor stands. */
	public static void place(ServerPlayer player, Kind kind) {
		ServerLevel level = player.level();
		net.minecraft.core.Direction facing = player.getDirection();
		// At your feet, or just in front if there is something there (a carpet, a step).
		BlockPos at = null;
		for (BlockPos p : new BlockPos[]{player.blockPosition(), player.blockPosition().relative(facing)}) {
			BlockState here = level.getBlockState(p);
			if (here.canBeReplaced() && here.getFluidState().isEmpty() && level.getBlockState(p.below()).isFaceSturdy(level, p.below(), net.minecraft.core.Direction.UP)
				&& !TRAPS.containsKey(p) && !Exits.isProp(p)) {
				at = p.immutable();
				break;
			}
		}
		if (at == null) {
			player.sendOverlayMessage(Component.translatable("halloween.trap.no_room").withStyle(ChatFormatting.GRAY));
			return;
		}
		BlockState here = level.getBlockState(at);
		BlockState look = (kind == Kind.BEAR_TRAP ? dev.halloween.HwBlocks.BEAR_TRAP : dev.halloween.HwBlocks.TIN_CANS).defaultBlockState()
			.setValue(TrapBlock.FACING, facing);
		level.setBlock(at, look, Block.UPDATE_CLIENTS);
		TRAPS.put(at.immutable(), new Trap(kind, player.getUUID(), here));
		player.getMainHandItem().shrink(1);
		level.playSound(null, at, kind == Kind.BEAR_TRAP ? SoundEvents.IRON_TRAPDOOR_OPEN : SoundEvents.CHAIN_PLACE, SoundSource.PLAYERS, 0.8f, 0.9f);
		player.sendOverlayMessage(Component.translatable("halloween.trap.set." + kind.name().toLowerCase(java.util.Locale.ROOT)).withStyle(ChatFormatting.GREEN));
		AiStats.add(player, "trap.set." + kind.name().toLowerCase(java.util.Locale.ROOT));
	}

	public static boolean isTrap(BlockPos pos) {
		return TRAPS.containsKey(pos);
	}

	public static void tick(MinecraftServer server) {
		if (TRAPS.isEmpty() || !Match.running()) {
			return;
		}
		HwConfig cfg = HwConfig.get();
		ServerLevel level = server.overworld();
		// The Runt sets them off too: the bear trap holds him, the cans give him away.
		for (ServerPlayer runt : Runt.all(server)) {
			Runt.State rs = Runt.state(runt);
			if (rs == null || rs.holding != null) {
				continue;
			}
			Iterator<Map.Entry<BlockPos, Trap>> it = TRAPS.entrySet().iterator();
			while (it.hasNext()) {
				Map.Entry<BlockPos, Trap> e = it.next();
				Vec3 c = Vec3.atBottomCenterOf(e.getKey());
				if (Math.abs(runt.getX() - c.x) > 0.75 || Math.abs(runt.getZ() - c.z) > 0.75 || Math.abs(runt.getY() - c.y) > 1) {
					continue;
				}
				it.remove();
				restore(level, e.getKey(), e.getValue());
				if (e.getValue().kind() == Kind.BEAR_TRAP) {
					Runt.trap(runt);
					level.playSound(null, e.getKey(), SoundEvents.IRON_TRAPDOOR_CLOSE, SoundSource.BLOCKS, 1.6f, 0.7f);
				} else {
					level.playSound(null, e.getKey(), SoundEvents.CHAIN_BREAK, SoundSource.BLOCKS, 2f, 1.3f);
					HwNet.Alert alert = new HwNet.Alert(c, cfg.alertTicks);
					for (ServerPlayer p : level.players()) {
						if (MatchManager.role(p).survivor() && !Bots.isBot(p)) {
							ServerPlayNetworking.send(p, alert);
						}
					}
				}
				AiStats.add(runt, "runt.trap");
			}
		}
		for (ServerPlayer shape : level.players()) {
			ShapeState state = MatchManager.role(shape) == Role.SHAPE ? MatchManager.shapeState(shape) : null;
			if (state == null || state.shadowTicks > 0 || shape.isSpectator()) {
				continue;
			}
			Iterator<Map.Entry<BlockPos, Trap>> it = TRAPS.entrySet().iterator();
			while (it.hasNext()) {
				Map.Entry<BlockPos, Trap> e = it.next();
				BlockPos at = e.getKey();
				Vec3 c = Vec3.atBottomCenterOf(at);
				if (Math.abs(shape.getX() - c.x) > 0.75 || Math.abs(shape.getZ() - c.z) > 0.75 || Math.abs(shape.getY() - c.y) > 1) {
					continue;
				}
				it.remove();
				Trap trap = e.getValue();
				restore(level, at, trap);
				ServerPlayer owner = server.getPlayerList().getPlayer(trap.owner());
				if (trap.kind() == Kind.BEAR_TRAP) {
					// Snap: he can't take a step for a while.
					state.trappedTicks = cfg.bearTrapTicks;
					level.playSound(null, at, SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 1.4f, 1.6f);
					level.playSound(null, at, SoundEvents.IRON_TRAPDOOR_CLOSE, SoundSource.BLOCKS, 1.6f, 0.7f);
					level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, MapPlan.b("iron_block")), c.x, c.y + 0.2, c.z, 20, 0.3, 0.1, 0.3, 0.1);
					MatchManager.bleed(level, shape, 10);
					Notices.send(shape, Component.translatable("halloween.trap.caught").withStyle(ChatFormatting.DARK_RED));
					if (owner != null) {
						Match.score(owner, cfg.scoreTrap, Component.translatable("halloween.score.trap"));
					}
					Match.chronicle(server, Component.translatable("halloween.chronicle.trap", owner == null ? Component.literal("?") : owner.getDisplayName())
						.withStyle(ChatFormatting.YELLOW));
					AiStats.add(shape, "trap.caught");
				} else {
					// Rattle: everyone on the other side knows where he just went.
					level.playSound(null, at, SoundEvents.CHAIN_BREAK, SoundSource.BLOCKS, 2f, 1.3f);
					level.playSound(null, at, SoundEvents.LANTERN_BREAK, SoundSource.BLOCKS, 2f, 1.6f);
					HwNet.Alert alert = new HwNet.Alert(c, cfg.alertTicks);
					for (ServerPlayer p : level.players()) {
						if (MatchManager.role(p).survivor() && !Bots.isBot(p)) {
							ServerPlayNetworking.send(p, alert);
							Notices.send(p, Component.translatable("halloween.trap.cans").withStyle(ChatFormatting.GOLD));
						}
					}
					Bots.alert(c, server.getTickCount());
					if (owner != null) {
						Match.score(owner, cfg.scoreCans, Component.translatable("halloween.score.cans"));
					}
					AiStats.add(shape, "trap.cans");
				}
			}
		}
	}

	private static void restore(ServerLevel level, BlockPos at, Trap trap) {
		level.setBlock(at, trap.under(), Block.UPDATE_CLIENTS);
	}

	/** The match is over: traps still set are taken up again. */
	public static void clear(MinecraftServer server) {
		for (Map.Entry<BlockPos, Trap> e : TRAPS.entrySet()) {
			if (server.overworld().getBlockState(e.getKey()).is(e.getValue().kind() == Kind.BEAR_TRAP ? dev.halloween.HwBlocks.BEAR_TRAP : dev.halloween.HwBlocks.TIN_CANS)) {
				restore(server.overworld(), e.getKey(), e.getValue());
			}
		}
		TRAPS.clear();
	}
}
