package dev.halloween.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.halloween.Halloween;
import dev.halloween.Role;
import dev.halloween.Vr;
import dev.halloween.client.mixin.KeyMappingAccessor;
import dev.halloween.net.HwNet;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * Buttons in the headset. The game's own keys mean different things by role ("Killer Sense / Grip / reload"), which
 * reads badly on a radial menu; so with Vivecraft there are short VR buttons, one per thing done, each passing its
 * press on to the key it stands for. They fill Vivecraft's radial menu (B on the right controller by default) by
 * role while a match role is set, and can be bound to any controller button in Vivecraft's or SteamVR's bindings.
 *
 * <p>The radial menu holds what it presses until pressed again: buttons that are held (Killer Sense, the Grip, the
 * Anchor) work as switches there; the rest are let go again as soon as they are passed on.
 */
public final class VrKeys {
	private record Alias(KeyMapping vr, java.util.function.Supplier<KeyMapping> to, boolean held) {
		KeyMapping target() {
			return this.to.get();
		}
	}

	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Halloween.id("vr"));
	private static final List<Alias> ALIASES = new ArrayList<>();
	private static KeyMapping dash;
	private static KeyMapping sense;
	private static KeyMapping grip;
	private static KeyMapping reload;
	private static KeyMapping shapeJump;
	private static KeyMapping upsideDown;
	private static KeyMapping eyes;
	private static KeyMapping runtSwap;
	private static KeyMapping impulse;
	private static KeyMapping anchor;
	private static KeyMapping orders;
	private static KeyMapping perks;
	private static KeyMapping skill;
	private static KeyMapping ping;
	private static KeyMapping gift1;
	private static KeyMapping gift2;
	/** What the radial menu was last set to (a role and form), so it is set only when that changes. */
	private static String shown = "";

	private VrKeys() {
	}

	/** At client start, only with Vivecraft there (nobody else needs these in their controls). */
	static void register() {
		if (!Vr.LOADED) {
			return;
		}
		// (The game's options are not there yet at start: the run key is looked up when pressed.)
		dash = alias("dash", () -> Minecraft.getInstance().options.keySprint, false);
		sense = alias("sense", HalloweenClient.KEY_SENSE, true);
		grip = alias("grip", HalloweenClient.KEY_SENSE, true);
		reload = alias("reload", HalloweenClient.KEY_SENSE, false);
		shapeJump = alias("shape_jump", HalloweenClient.KEY_SHAPE_JUMP, false);
		upsideDown = alias("upside_down", HalloweenClient.KEY_SHAPE_JUMP, false);
		eyes = alias("eyes", HalloweenClient.KEY_SHAPE_JUMP, false);
		runtSwap = alias("runt_swap", HalloweenClient.KEY_SHAPE_JUMP, false);
		impulse = alias("impulse", HalloweenClient.KEY_IMPULSE, false);
		anchor = alias("anchor", HalloweenClient.KEY_IMPULSE, true);
		orders = alias("orders", HalloweenClient.KEY_RUNT_ORDER, false);
		perks = alias("perks", HalloweenClient.KEY_PERKS, false);
		skill = alias("skill", HalloweenClient.KEY_SKILL, false);
		ping = alias("ping", HalloweenClient.KEY_PING, false);
		gift1 = key("gift1");
		gift2 = key("gift2");
	}

	private static KeyMapping key(String name) {
		return KeyMappingHelper.registerKeyMapping(new KeyMapping("key.halloween.vr." + name, InputConstants.Type.KEYSYM,
			InputConstants.UNKNOWN.getValue(), CATEGORY));
	}

	private static KeyMapping alias(String name, KeyMapping target, boolean held) {
		return alias(name, () -> target, held);
	}

	private static KeyMapping alias(String name, java.util.function.Supplier<KeyMapping> target, boolean held) {
		KeyMapping vr = key(name);
		ALIASES.add(new Alias(vr, target, held));
		return vr;
	}

	/** Whether a VR button that holds {@code target} down is held now (on the controller, or switched on at the wrist). */
	static boolean held(KeyMapping target) {
		for (Alias a : ALIASES) {
			if (a.held() && a.target() == target && (a.vr().isDown() || TOGGLED.contains(a.vr()))) {
				return true;
			}
		}
		return false;
	}

	/** Held buttons switched on from the wrist (VrWrist), until touched again or the role changes. */
	private static final java.util.Set<KeyMapping> TOGGLED = new java.util.HashSet<>();

	/** The buttons of this role and form, as the radial menu and the wrist show them (empty outside a match role). */
	static KeyMapping[] keys() {
		if (ALIASES.isEmpty()) {
			return new KeyMapping[0];
		}
		return switch (form()) {
			case "michael" -> new KeyMapping[]{sense, shapeJump, impulse, dash, orders, perks};
			case "demo" -> new KeyMapping[]{upsideDown, impulse, dash, orders, perks};
			case "vecna" -> new KeyMapping[]{grip, eyes, anchor, dash, perks};
			case "RUNT" -> new KeyMapping[]{runtSwap, gift1, gift2, perks};
			case "" -> new KeyMapping[0];
			default -> new KeyMapping[]{ping, skill, reload, perks};
		};
	}

	private static String form() {
		Role role = ClientState.myRole();
		return role == Role.NONE ? "" : role == Role.SHAPE ? (MichaelRenderer.vecna() ? "vecna" : MichaelRenderer.demogorgon() ? "demo" : "michael")
			: role.name();
	}

	/** Touched at the wrist: a press passed on next tick, or a held one switched on or off. */
	static void press(KeyMapping vr) {
		for (Alias a : ALIASES) {
			if (a.vr() == vr && a.held()) {
				if (!TOGGLED.remove(vr)) {
					TOGGLED.add(vr);
				}
				return;
			}
		}
		KeyMappingAccessor acc = (KeyMappingAccessor) vr;
		acc.halloween$setClickCount(acc.halloween$getClickCount() + 1);
	}

	private static KeyMapping[] testKeys;

	/** For tests without Vivecraft (none of the VR buttons registered): a survivor's buttons to draw. */
	static KeyMapping[] testKeys() {
		if (testKeys == null) {
			testKeys = new KeyMapping[]{
				new KeyMapping("key.halloween.vr.ping", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), CATEGORY),
				new KeyMapping("key.halloween.vr.skill", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), CATEGORY),
				new KeyMapping("key.halloween.vr.reload", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), CATEGORY),
				new KeyMapping("key.halloween.vr.perks", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), CATEGORY)};
		}
		return testKeys;
	}

	/** Switched on at the wrist (a held one). */
	static boolean toggled(KeyMapping vr) {
		return TOGGLED.contains(vr);
	}

	/** Every client tick, before the keys are read: presses passed on, the radial menu set for the role. */
	static void tick(Minecraft mc) {
		if (ALIASES.isEmpty()) {
			return;
		}
		for (Alias a : ALIASES) {
			while (!a.held() && a.vr().consumeClick()) {
				KeyMappingAccessor target = (KeyMappingAccessor) a.target();
				target.halloween$setClickCount(target.halloween$getClickCount() + 1);
				ClientVr.unhold(a.vr());
			}
			if (a.held()) {
				// A held button counts while down; its clicks mean nothing.
				while (a.vr().consumeClick()) {
				}
			}
		}
		for (int k = 0; k < 2; k++) {
			KeyMapping gift = k == 0 ? gift1 : gift2;
			while (gift.consumeClick()) {
				HwNet.RuntHud hud = ClientState.runtHud;
				if (hud != null && hud.pending() >= 0) {
					ClientPlayNetworking.send(new HwNet.RuntPick(hud.pending(), k));
				}
				ClientVr.unhold(gift);
			}
		}
		radial(mc);
	}

	private static void radial(Minecraft mc) {
		boolean on = ClientVr.active() && ClientState.myRole() != Role.NONE;
		String now = !on ? "" : form();
		if (now.equals(shown)) {
			return;
		}
		shown = now;
		// A new role or form: nothing left switched on at the wrist.
		TOGGLED.clear();
		if (!on) {
			ClientVr.radial(null);
			return;
		}
		KeyMapping[] keys = keys();
		// Eight places round the menu; the empty ones show nothing.
		String[] items = new String[8];
		for (int i = 0; i < items.length; i++) {
			items[i] = i < keys.length ? keys[i].getName() : "";
		}
		ClientVr.radial(items);
	}
}
