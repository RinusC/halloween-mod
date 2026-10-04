package dev.halloween.game;

import java.util.Locale;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Match items. For now they are vanilla items with our own name and a marker in custom data;
 * graphics can come later. Weapons carry how many hits they have left and how much they stun.
 */
public final class HwItems {
	private static final String KIND = "hw_item";
	private static final String USES = "hw_uses";

	public enum Kind {
		BOLT_CUTTERS("shears", 0, 0f),
		PADLOCK_KEY("trial_key", 0, 0f),
		CAR_KEYS("ominous_trial_key", 0, 0f),
		CAR_PART("lightning_rod", 0, 0f),
		REPAIR_KIT("bundle", 0, 0f),
		BAT("stick", 4, 0.34f),
		KNIFE("stone_sword", 6, 0.25f),
		DART("arrow", 1, 0.5f),
		BATON("breeze_rod", 8, 0.5f),
		FLASHLIGHT("lantern", 0, 0f);

		public final String baseItem;
		public final int uses;
		public final float stun;

		Kind(String baseItem, int uses, float stun) {
			this.baseItem = baseItem;
			this.uses = uses;
			this.stun = stun;
		}

		public String key() {
			return this.name().toLowerCase(Locale.ROOT);
		}

		public boolean melee() {
			return this == BAT || this == KNIFE || this == BATON;
		}
	}

	private HwItems() {
	}

	public static ItemStack create(Kind kind) {
		ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(kind.baseItem)));
		stack.set(DataComponents.CUSTOM_NAME, Component.translatable("halloween.item." + kind.key()));
		CompoundTag tag = new CompoundTag();
		tag.putString(KIND, kind.key());
		if (kind.uses > 0) {
			tag.putInt(USES, kind.uses);
		}
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return stack;
	}

	public static Kind kindOf(ItemStack stack) {
		if (stack.isEmpty()) {
			return null;
		}
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) {
			return null;
		}
		String key = data.copyTag().getStringOr(KIND, "");
		for (Kind k : Kind.values()) {
			if (k.key().equals(key)) {
				return k;
			}
		}
		return null;
	}

	public static boolean is(ItemStack stack, Kind kind) {
		return kindOf(stack) == kind;
	}

	/** Uses up one hit of a weapon; returns true when it just broke. */
	public static boolean wear(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) {
			return false;
		}
		CompoundTag tag = data.copyTag();
		int left = tag.getIntOr(USES, 1) - 1;
		if (left <= 0) {
			stack.setCount(0);
			return true;
		}
		tag.putInt(USES, left);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return false;
	}
}
