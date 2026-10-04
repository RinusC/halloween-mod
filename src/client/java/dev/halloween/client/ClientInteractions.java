package dev.halloween.client;

import dev.halloween.HwBlocks;
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
import net.minecraft.world.phys.Vec3;

/**
 * Client side of doors, windows and match objects. Returning SUCCESS stops the local block from
 * reacting and still sends the click to the server, which refuses it; the real action goes through
 * {@link HwNet.UseAction}.
 */
public final class ClientInteractions {
	/** A window climb fires once per click, not again while the button stays held. */
	private static boolean vaultArmed = true;
	private static int lastSlot;

	private ClientInteractions() {
	}

	public static BlockPos doorBase(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
	}

	/** What kind of match object is at {@code pos}: 0 none, 1 container, 2 phone, 3 exit. */
	public static int matchObject(Level level, BlockPos pos) {
		HwNet.MatchSync match = ClientState.match;
		if (match.state() != 2) {
			return 0;
		}
		if (match.containers().contains(pos)) {
			return 1;
		}
		if (level.getBlockState(pos).is(HwBlocks.PHONE)) {
			return 2;
		}
		Vec3 c = Vec3.atCenterOf(pos);
		for (HwNet.ExitInfo exit : match.exits()) {
			double radius = exit.type() == 1 ? 3.2 : exit.type() == 2 ? 3.5 : 2.5;
			if (Vec3.atCenterOf(exit.pos()).distanceTo(c) <= radius) {
				return 3;
			}
		}
		return 0;
	}

	public static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		if (!level.isClientSide() || hand != InteractionHand.MAIN_HAND) {
			return InteractionResult.PASS;
		}
		Role role = ClientState.myRole();
		if (role == Role.NONE) {
			return InteractionResult.PASS;
		}
		BlockPos pos = hit.getBlockPos();
		BlockState state = level.getBlockState(pos);
		boolean door = state.getBlock() instanceof DoorBlock;
		if (role.survivor()) {
			if (door) {
				if (player.isShiftKeyDown()) {
					ClientPlayNetworking.send(new HwNet.UseAction(HwNet.UseAction.DOOR_LOCK, pos));
					return InteractionResult.SUCCESS;
				}
				return ClientState.LOCKED_DOORS.contains(doorBase(level, pos)) ? InteractionResult.SUCCESS : InteractionResult.PASS;
			}
			if (Sight.isGlass(state) && matchObject(level, pos) == 0) {
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
		if (!door && matchObject(level, pos) != 0) {
			// Held: the server keeps the action going while these pings keep arriving.
			ClientPlayNetworking.send(new HwNet.UseAction(HwNet.UseAction.INTERACT, pos));
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.PASS;
	}

	public static void tick(Minecraft mc) {
		if (!mc.options.keyUse.isDown()) {
			vaultArmed = true;
		}
		// Survivors have a hand and three pockets: the selection wraps around slots 0-3.
		if (mc.player != null && ClientState.myRole().survivor()) {
			var inv = mc.player.getInventory();
			int slot = inv.getSelectedSlot();
			if (slot >= dev.halloween.game.Inventory.SLOTS) {
				inv.setSelectedSlot(lastSlot == dev.halloween.game.Inventory.SLOTS - 1 && slot == dev.halloween.game.Inventory.SLOTS ? 0
					: lastSlot == 0 && slot == 8 ? dev.halloween.game.Inventory.SLOTS - 1 : Math.min(lastSlot, dev.halloween.game.Inventory.SLOTS - 1));
			}
			lastSlot = inv.getSelectedSlot();
		}
	}
}
