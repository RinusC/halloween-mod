package dev.halloween.game;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Survivors carry one item in hand plus three in pockets: hotbar slots 0-3, nothing else. */
public final class Inventory {
	public static final int SLOTS = 4;

	private Inventory() {
	}

	public static boolean has(ServerPlayer player, HwItems.Kind kind) {
		return slotOf(player, kind) >= 0;
	}

	public static int slotOf(ServerPlayer player, HwItems.Kind kind) {
		var inv = player.getInventory();
		for (int i = 0; i < SLOTS; i++) {
			if (HwItems.is(inv.getItem(i), kind)) {
				return i;
			}
		}
		return -1;
	}

	public static void consume(ServerPlayer player, HwItems.Kind kind) {
		int slot = slotOf(player, kind);
		if (slot >= 0) {
			player.getInventory().getItem(slot).shrink(1);
			player.getInventory().setChanged();
		}
	}

	/** Puts an item into a free slot, or drops it at the player's feet when all four are taken. */
	public static void give(ServerPlayer player, ItemStack stack) {
		var inv = player.getInventory();
		for (int i = 0; i < SLOTS; i++) {
			if (inv.getItem(i).isEmpty()) {
				inv.setItem(i, stack);
				return;
			}
		}
		player.drop(stack, false, false);
	}

	/** Anything that ended up outside the four slots moves into a free one or falls on the ground. */
	public static void enforce(ServerPlayer player) {
		var inv = player.getInventory();
		for (int i = SLOTS; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (!stack.isEmpty()) {
				inv.setItem(i, ItemStack.EMPTY);
				give(player, stack);
			}
		}
		if (inv.getSelectedSlot() >= SLOTS) {
			inv.setSelectedSlot(0);
		}
	}

	public static void dropAll(ServerPlayer player) {
		var inv = player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (!stack.isEmpty()) {
				inv.setItem(i, ItemStack.EMPTY);
				player.drop(stack, true, false);
			}
		}
	}
}
