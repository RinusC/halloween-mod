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
	private static boolean useWasDown;

	private ClientInteractions() {
	}

	public static BlockPos doorBase(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
	}

	/** What kind of match object is at {@code pos}: 0 none, 1 container, 2 phone, 3 exit, 4 gun case. */
	public static int matchObject(Level level, BlockPos pos) {
		HwNet.MatchSync match = ClientState.match;
		if (match.state() != 2) {
			return 0;
		}
		if (match.containers().contains(pos)) {
			return 1;
		}
		if (match.cases().contains(pos)) {
			return 4;
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

	static boolean aimsAtUsable(Minecraft mc) {
		if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
			return false;
		}
		BlockState state = mc.level.getBlockState(hit.getBlockPos());
		return state.getBlock() instanceof DoorBlock || state.getBlock() instanceof dev.halloween.game.LockerBlock || Sight.isGlass(state) || matchObject(mc.level, hit.getBlockPos()) != 0;
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
			if (state.getBlock() instanceof dev.halloween.game.LockerBlock) {
				// Held like a search: slow and quiet, or at once (and loud) on the run. FAIL sends no vanilla
				// click, which would be refused right after the teleport inside with a build-limit message.
				if (!player.isInvisible()) {
					ClientPlayNetworking.send(new HwNet.UseAction(HwNet.UseAction.INTERACT, pos));
				}
				return InteractionResult.FAIL;
			}
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
				// FAIL: no vanilla click goes out, which the server would refuse mid-climb with a build-limit message.
				return InteractionResult.FAIL;
			}
		} else if (role == Role.SHAPE && door && ClientState.LOCKED_DOORS.contains(doorBase(level, pos))) {
			return InteractionResult.SUCCESS;
		} else if (role == Role.SHAPE && ClientState.shadowActive() && Sight.isGlass(state)) {
			// A shadow slips through the window, slowly.
			if (vaultArmed) {
				vaultArmed = false;
				ClientPlayNetworking.send(new HwNet.UseAction(HwNet.UseAction.VAULT_SLOW, pos));
			}
			return InteractionResult.FAIL;
		}
		if (!door && matchObject(level, pos) != 0) {
			// Held: the server keeps the action going while these pings keep arriving.
			ClientPlayNetworking.send(new HwNet.UseAction(HwNet.UseAction.INTERACT, pos));
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.PASS;
	}

	/** A knife, an ice pick or a lawn dart in the pockets: something to break a grab with. */
	public static boolean hasStab(Minecraft mc) {
		var inv = mc.player.getInventory();
		for (int i = 0; i < dev.halloween.game.Inventory.SLOTS; i++) {
			dev.halloween.game.HwItems.Kind kind = dev.halloween.game.HwItems.kindOf(inv.getItem(i));
			if (kind != null && kind.stab()) {
				return true;
			}
		}
		return false;
	}

	public static void tick(Minecraft mc) {
		boolean useDown = mc.options.keyUse.isDown();
		if (!useDown) {
			vaultArmed = true;
		}
		// Using again while talking to a townsperson breaks the talk off.
		if (useDown && !useWasDown && ClientState.talking() && mc.gui.screen() == null) {
			ClientPlayNetworking.send(new HwNet.NpcTalk(-1, -1));
		}
		// Right click with the flashlight switches it, unless the click is meant for a door, window or object.
		if (useDown && !useWasDown && mc.player != null && mc.level != null && ClientState.myRole().survivor()
			&& dev.halloween.game.HwItems.is(mc.player.getMainHandItem(), dev.halloween.game.HwItems.Kind.FLASHLIGHT) && !aimsAtUsable(mc)) {
			ClientPlayNetworking.send(new HwNet.ActionPress(HwNet.Action.FLASHLIGHT));
		}
		if (useDown && !useWasDown && mc.player != null && mc.level != null && ClientState.myRole().survivor() && mc.gui.screen() == null) {
			dev.halloween.game.HwItems.Kind held = dev.halloween.game.HwItems.kindOf(mc.player.getMainHandItem());
			if (ClientState.qte.active() && !ClientState.qte.forShape()) {
				// Caught: right click stabs him with a knife from the pockets, if there is one.
				if (hasStab(mc)) {
					ClientPlayNetworking.send(new HwNet.ActionPress(HwNet.Action.STAB));
				}
			} else if (held != null && held.usable() && !aimsAtUsable(mc)
				&& !(mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult eh && ClientState.match.npcs().contains(eh.getEntity().getId()))) {
				// Throw it, spray, open the med kit, fire the flare gun.
				ClientPlayNetworking.send(new HwNet.ActionPress(HwNet.Action.ITEM_USE));
			}
		}
		useWasDown = useDown;
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
