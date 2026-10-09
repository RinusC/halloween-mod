package dev.halloween.game;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Survivors carry one item in hand plus four in pockets: hotbar slots 0-4, nothing else. */
public final class Inventory {
	public static final int SLOTS = 5;

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

	/** How many of this kind the pockets hold, stacks added up (rounds). */
	public static int count(ServerPlayer player, HwItems.Kind kind) {
		var inv = player.getInventory();
		int n = 0;
		for (int i = 0; i < SLOTS; i++) {
			if (HwItems.is(inv.getItem(i), kind)) {
				n += inv.getItem(i).getCount();
			}
		}
		return n;
	}

	public static void consume(ServerPlayer player, HwItems.Kind kind) {
		int slot = slotOf(player, kind);
		if (slot >= 0) {
			player.getInventory().getItem(slot).shrink(1);
			player.getInventory().setChanged();
		}
	}

	/**
	 * Puts an item into the pockets: onto a stack of the same (rounds go in with rounds), else into a free
	 * slot, else (a bot) in place of something less useful, else at the player's feet.
	 */
	public static void give(ServerPlayer player, ItemStack stack) {
		var inv = player.getInventory();
		for (int i = 0; i < SLOTS && !stack.isEmpty(); i++) {
			ItemStack have = inv.getItem(i);
			if (!have.isEmpty() && ItemStack.isSameItemSameComponents(have, stack) && have.getCount() < have.getMaxStackSize()) {
				int move = Math.min(stack.getCount(), have.getMaxStackSize() - have.getCount());
				have.grow(move);
				stack.shrink(move);
			}
		}
		if (stack.isEmpty()) {
			inv.setChanged();
			return;
		}
		for (int i = 0; i < SLOTS; i++) {
			if (inv.getItem(i).isEmpty()) {
				inv.setItem(i, stack);
				return;
			}
		}
		if (swapIn(player, stack)) {
			return;
		}
		dropAside(player, stack);
	}

	/**
	 * A bot with full pockets finds something to get away with (petrol, keys, bolt cutters, a fuse...) that is
	 * still needed: it throws down one of its other things (whichever, as luck has it) and keeps the find.
	 */
	private static boolean swapIn(ServerPlayer player, ItemStack stack) {
		HwItems.Kind kind = HwItems.kindOf(stack);
		if (!Bots.isBot(player) || kind == null || !kind.escape() || !BotBrain.escapeNeeded(kind)) {
			return false;
		}
		var inv = player.getInventory();
		java.util.List<Integer> junk = new java.util.ArrayList<>();
		for (int i = 0; i < SLOTS; i++) {
			HwItems.Kind held = HwItems.kindOf(inv.getItem(i));
			if (held == null || !held.escape()) {
				junk.add(i);
			}
		}
		if (junk.isEmpty()) {
			return false;
		}
		int slot = junk.get(player.getRandom().nextInt(junk.size()));
		ItemStack old = inv.getItem(slot);
		inv.setItem(slot, stack);
		dropAside(player, old);
		AiStats.add(player, "loot.swapped");
		return true;
	}

	/** At the feet; a bot leaves it there a good while (it doesn't pick its own cast-off straight back up and drop it again). */
	private static void dropAside(ServerPlayer player, ItemStack stack) {
		net.minecraft.world.entity.item.ItemEntity item = player.drop(stack, false, false);
		if (item != null && Bots.isBot(player)) {
			item.setPickUpDelay(200);
		}
	}

	/** Is there something in the pockets that isn't for getting away (a bot could swap it for something that is)? */
	public static boolean hasRoomForEscape(ServerPlayer player) {
		var inv = player.getInventory();
		for (int i = 0; i < SLOTS; i++) {
			HwItems.Kind held = HwItems.kindOf(inv.getItem(i));
			if (inv.getItem(i).isEmpty() || held == null || !held.escape()) {
				return true;
			}
		}
		return false;
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
