package dev.halloween.client;

import dev.halloween.Role;
import dev.halloween.net.HwNet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/** What the client knows about the match, filled from server packets. */
public final class ClientState {
	public static final Map<UUID, Role> ROLES = new HashMap<>();
	public static int shapeEntityId = -1;
	public static boolean shapeShadow;
	public static HwNet.ShapeHud hud;
	public static final Map<Integer, HwNet.VictimInfo> VICTIMS = new HashMap<>();
	public static HwNet.Qte qte = HwNet.Qte.INACTIVE;

	private ClientState() {
	}

	public static Role myRole() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player == null ? Role.NONE : ROLES.getOrDefault(mc.player.getUUID(), Role.NONE);
	}

	public static boolean inMatch() {
		return myRole() != Role.NONE;
	}

	/** True for the Shape's entity while he is hidden in Shape Jump, as seen by anyone but himself. */
	public static boolean hideEntity(Entity entity) {
		Minecraft mc = Minecraft.getInstance();
		return shapeShadow && entity.getId() == shapeEntityId && entity != mc.player;
	}

	public static boolean shouldGlow(Entity entity) {
		if (myRole() != Role.SHAPE) {
			return false;
		}
		HwNet.VictimInfo info = VICTIMS.get(entity.getId());
		return info != null && info.glow();
	}

	public static void reset() {
		ROLES.clear();
		shapeEntityId = -1;
		shapeShadow = false;
		hud = null;
		VICTIMS.clear();
		qte = HwNet.Qte.INACTIVE;
	}
}
