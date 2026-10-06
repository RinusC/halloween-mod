package dev.halloween.client;

import dev.halloween.game.Skins;
import dev.halloween.net.HwNet;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

/** The match skins the server handed out, by entity id (see {@link Skins}). */
public final class ClientSkins {
	private static final Map<Integer, String> BY_ENTITY = new HashMap<>();
	private static final Map<String, PlayerSkin> SKINS = new HashMap<>();

	private ClientSkins() {
	}

	public static void apply(HwNet.Skins payload) {
		BY_ENTITY.clear();
		for (int i = 0; i < payload.ids().size(); i++) {
			BY_ENTITY.put(payload.ids().get(i), payload.keys().get(i));
		}
	}

	public static void clear() {
		BY_ENTITY.clear();
	}

	/** The skin this player wears in the match, or null for their own. */
	public static PlayerSkin forEntity(int id) {
		String key = BY_ENTITY.get(id);
		return key == null ? null : SKINS.computeIfAbsent(key, ClientSkins::load);
	}

	private static PlayerSkin load(String key) {
		String path = key.equals("police") ? "entity/police" : "entity/skin/" + key;
		int index = Skins.SURVIVORS.indexOf(key);
		boolean slim = index >= 0 && index < Skins.SLIM_COUNT;
		return PlayerSkin.insecure(new ClientAsset.ResourceTexture(Identifier.fromNamespaceAndPath("halloween", path)), null, null,
			slim ? PlayerModelType.SLIM : PlayerModelType.WIDE);
	}
}
