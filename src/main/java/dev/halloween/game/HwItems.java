package dev.halloween.game;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.CustomData;

/**
 * Match items, as in the original game: escape tools, knives that break a grab, melee weapons, things to
 * throw, first aid, light, rare relics and firearms. Every one is a plain barrier item underneath (no
 * vanilla behaviour at all) with our name, a marker in custom data and a model: a vanilla one where it
 * fits, otherwise our own (assets/halloween/items/{key}.json). Weapons carry how many hits they have left.
 */
public final class HwItems {
	private static final String KIND = "hw_item";
	private static final String USES = "hw_uses";
	private static final String OWN = "own";

	public enum Kind {
		// Escape: every match hides one of each (two repair kits).
		BOLT_CUTTERS("minecraft:shears"),
		PADLOCK_KEY("minecraft:trial_key"),
		SEDAN_KEYS("minecraft:ominous_trial_key"),
		GAS_CAN(OWN),
		REPAIR_KIT("minecraft:bundle"),
		FUSE(OWN),
		GATE_KEY(OWN),
		// Breaking free of a grab (the ice pick and the lawn dart also fight).
		POCKET_KNIFE(OWN),
		BUTTERFLY_KNIFE(OWN),
		ICE_PICK(OWN, 4, 0.22f, 12),
		DART("minecraft:arrow", 1, 1f, 0),
		// Melee: hits before it breaks, stun per hit, ticks between swings (two-handed tools are slow).
		KITCHEN_KNIFE("minecraft:stone_sword", 6, 0.22f, 12),
		CLEAVER(OWN, 5, 0.28f, 15),
		HATCHET("minecraft:stone_axe", 5, 0.28f, 15),
		PIPE_WRENCH(OWN, 7, 0.30f, 18),
		BAT(OWN, 5, 0.34f, 18),
		FIRE_POKER(OWN, 6, 0.30f, 16),
		AXE("minecraft:iron_axe", 4, 0.45f, 26),
		PICKAXE("minecraft:iron_pickaxe", 4, 0.45f, 26),
		PITCHFORK("minecraft:trident", 4, 0.40f, 22),
		BATON("minecraft:breeze_rod", 8, 0.5f, 15),
		// Thrown: stun on a hit (firecrackers go off where they land).
		BRICK("minecraft:brick", 0, 0.30f, 0),
		ROCK("minecraft:flint", 0, 0.25f, 0),
		BASEBALL("minecraft:snowball", 0, 0.20f, 0),
		FIRECRACKERS("minecraft:firework_rocket"),
		// First aid.
		HEALING_SPRAY(OWN, 3, 0f, 0),
		MED_KIT(OWN),
		// Light.
		FLASHLIGHT(OWN),
		BATTERY("minecraft:iron_nugget"),
		ROAD_FLARE(OWN),
		FLARE_GUN(OWN),
		// Relics: not in every match.
		AMULET(OWN),
		HOURGLASS(OWN),
		BRASS_LANTERN("minecraft:copper_lantern"),
		// Firearms.
		REVOLVER(OWN),
		RIFLE(OWN),
		SHOTGUN(OWN),
		REVOLVER_AMMO(OWN),
		RIFLE_AMMO(OWN),
		SHOTGUN_SHELLS(OWN),
		SAFE_CODE(OWN);

		private static final Set<Kind> MELEE = EnumSet.of(KITCHEN_KNIFE, CLEAVER, HATCHET, PIPE_WRENCH, BAT, FIRE_POKER, AXE, PICKAXE,
			PITCHFORK, BATON, ICE_PICK);
		private static final Set<Kind> THROWN = EnumSet.of(DART, BRICK, ROCK, BASEBALL, FIRECRACKERS, ROAD_FLARE, HOURGLASS, CLEAVER,
			HATCHET, PITCHFORK);
		private static final Set<Kind> STAB = EnumSet.of(POCKET_KNIFE, BUTTERFLY_KNIFE, ICE_PICK, DART);
		private static final Set<Kind> ESCAPE = EnumSet.of(BOLT_CUTTERS, PADLOCK_KEY, SEDAN_KEYS, GAS_CAN, REPAIR_KIT, FUSE, GATE_KEY);
		private static final Set<Kind> RELICS = EnumSet.of(AMULET, HOURGLASS, BRASS_LANTERN);

		public final String model;
		public final int uses;
		public final float stun;
		/** Ticks between two swings with it. */
		public final int hitTicks;

		Kind(String model) {
			this(model, 0, 0f, 0);
		}

		Kind(String model, int uses, float stun, int hitTicks) {
			this.model = model;
			this.uses = uses;
			this.stun = stun;
			this.hitTicks = hitTicks;
		}

		public String key() {
			return this.name().toLowerCase(Locale.ROOT);
		}

		public Identifier modelId() {
			return OWN.equals(this.model) ? Identifier.fromNamespaceAndPath("halloween", this.key()) : Identifier.parse(this.model);
		}

		public boolean gun() {
			return this == REVOLVER || this == RIFLE || this == SHOTGUN;
		}

		public boolean ammo() {
			return this == REVOLVER_AMMO || this == RIFLE_AMMO || this == SHOTGUN_SHELLS;
		}

		public boolean melee() {
			return MELEE.contains(this);
		}

		/** Right click throws it. */
		public boolean thrown() {
			return THROWN.contains(this);
		}

		/** Breaks the Shape's grab: stabbed into him, then gone. */
		public boolean stab() {
			return STAB.contains(this);
		}

		public boolean escape() {
			return ESCAPE.contains(this);
		}

		public boolean relic() {
			return RELICS.contains(this);
		}

		/** Does something on right click (the server's {@link Gear#use}). */
		public boolean usable() {
			return this.thrown() || this == HEALING_SPRAY || this == MED_KIT || this == FLARE_GUN || this == FLASHLIGHT;
		}
	}

	private HwItems() {
	}

	public static ItemStack create(Kind kind) {
		ItemStack stack = new ItemStack(Items.BARRIER);
		stack.set(DataComponents.CUSTOM_NAME, Component.translatable("halloween.item." + kind.key()));
		stack.set(DataComponents.ITEM_MODEL, kind.modelId());
		stack.set(DataComponents.RARITY, kind.relic() ? Rarity.RARE : Rarity.COMMON);
		stack.set(DataComponents.MAX_STACK_SIZE, 1);
		CompoundTag tag = new CompoundTag();
		tag.putString(KIND, kind.key());
		if (kind.uses > 0) {
			tag.putInt(USES, kind.uses);
		}
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		if (kind.uses > 1) {
			// Wear shows as the durability bar.
			stack.set(DataComponents.MAX_DAMAGE, kind.uses);
			stack.set(DataComponents.DAMAGE, 0);
		}
		if (kind.gun()) {
			Guns.setLoaded(stack, Guns.capacity(kind));
		}
		if (kind.ammo()) {
			stack.set(DataComponents.MAX_STACK_SIZE, 24);
		}
		if (kind == Kind.DART || kind == Kind.BATTERY || kind == Kind.ROCK || kind == Kind.BRICK || kind == Kind.BASEBALL) {
			stack.set(DataComponents.MAX_STACK_SIZE, 4);
		}
		if (kind == Kind.FLASHLIGHT) {
			// The battery charge shows as the durability bar.
			stack.set(DataComponents.MAX_DAMAGE, Math.max(1, dev.halloween.HwConfig.get().flashlightChargeTicks));
			stack.set(DataComponents.DAMAGE, 0);
		}
		return stack;
	}

	/** Ticks of light left in a flashlight. */
	public static int charge(ItemStack stack) {
		return stack.getMaxDamage() - stack.getDamageValue();
	}

	/** Burns {@code ticks} of a flashlight battery; returns the charge left. */
	public static int drain(ItemStack stack, int ticks) {
		stack.setDamageValue(Math.min(stack.getMaxDamage(), stack.getDamageValue() + ticks));
		return charge(stack);
	}

	/** A fresh battery in the flashlight. */
	public static void recharge(ItemStack stack) {
		stack.setDamageValue(0);
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

	/** Uses left (hits of a weapon, sprays of a can). */
	public static int usesLeft(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data == null ? 0 : data.copyTag().getIntOr(USES, 1);
	}

	/** Uses up one hit of a weapon (one spray of a can); returns true when it just broke (ran out). */
	public static boolean wear(ItemStack stack) {
		return wear(stack, 1);
	}

	public static boolean wear(ItemStack stack, int amount) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) {
			return false;
		}
		CompoundTag tag = data.copyTag();
		int left = tag.getIntOr(USES, 1) - amount;
		if (left <= 0) {
			stack.setCount(0);
			return true;
		}
		tag.putInt(USES, left);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		if (stack.has(DataComponents.MAX_DAMAGE)) {
			stack.setDamageValue(Math.max(0, stack.getMaxDamage() - left));
		}
		return false;
	}
}
