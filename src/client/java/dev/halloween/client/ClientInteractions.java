package dev.halloween.client;

import dev.halloween.Role;
import dev.halloween.game.Sight;
import dev.halloween.net.HwNet;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Client side of doors and windows. Returning SUCCESS here stops the local door from flapping and still
 * sends the click to the server, which refuses it; the real action goes through {@link HwNet.UseAction}.
 */
public final class ClientInteractions {
	/** A window climb fires once per click, not again while the button stays held. */
	private static boolean vaultArmed = true;

	private ClientInteractions() {
	}

	public static BlockPos doorBase(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
	}

	public static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		if (!level.isClientSide() || hand != InteractionHand.MAIN_HAND) {
			return InteractionResult.PASS;
		}
		Role role = ClientState.myRole();
		BlockPos pos = hit.getBlockPos();
		BlockState state = level.getBlockState(pos);
		boolean door = state.getBlock() instanceof DoorBlock;
		if (role == Role.CIVILIAN) {
			if (door) {
				if (player.isShiftKeyDown()) {
					ClientPlayNetworking.send(new HwNet.UseAction(HwNet.UseAction.DOOR_LOCK, pos));
					return InteractionResult.SUCCESS;
				}
				return ClientState.LOCKED_DOORS.contains(doorBase(level, pos)) ? InteractionResult.SUCCESS : InteractionResult.PASS;
			}
			if (Sight.isGlass(state)) {
				if (vaultArmed) {
					vaultArmed = false;
					Minecraft mc = Minecraft.getInstance();
					boolean fast = mc.options.keySprint.isDown() || player.isSprinting();
					ClientPlayNetworking.send(new HwNet.UseAction(fast ? HwNet.UseAction.VAULT_FAST : HwNet.UseAction.VAULT_SLOW, pos));
				}
				return InteractionResult.SUCCESS;
			}
		} else if (role == Role.SHAPE && door && ClientState.LOCKED_DOORS.contains(doorBase(level, pos))) {
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.PASS;
	}

	public static void tick(Minecraft mc) {
		if (!mc.options.keyUse.isDown()) {
			vaultArmed = true;
		}
	}
}
