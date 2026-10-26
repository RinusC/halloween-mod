package dev.halloween.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.halloween.Halloween;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Default controller bindings for SteamVR, so the role's powers sit on the controllers with nothing to set up.
 * Vivecraft ships bindings for Vive, Touch, WMR, Index and Cosmos only: a Pico (PICO Connect or the Streaming
 * Assistant) reports a controller type of its own and got none, every button blank. The Pico 4 / 4 Ultra controllers
 * have the Touch layout (stick, trigger, grip, A/B and X/Y), so they get Vivecraft's Touch bindings with the free left
 * hand turned to the powers (assets/halloween/vr/pico_defaults.json):
 *
 * <ul>
 * <li>left trigger: power 1 (held for what is held), left grip: power 3, X: power 2, X held: perks;</li>
 * <li>left stick: walk, its click: run / dash; Y: menu, as before;</li>
 * <li>right hand as Vivecraft has it: trigger strikes or fires, grip the next slot, A grab / use, B the radial menu,
 * the stick turns and its click crouches (or crouch for real).</li>
 * </ul>
 * Touch controllers (a Quest, or a Pico streamed as one by Virtual Desktop) get the same. SteamVR keeps a binding the
 * player chose over these defaults.
 */
public final class VrBindings {
	/** Controller types Pico's SteamVR drivers have reported, over the years and headsets. */
	private static final String[] PICO = {"pico_controller", "pico4_controller", "pico4u_controller", "pico4s_controller",
		"pico_neo3_controller", "pico_neo4_controller", "pico_g3_controller"};
	/** The ones Vivecraft has bindings of its own for. */
	private static final Set<String> VIVECRAFT = Set.of("vive_controller", "holographic_controller", "knuckles",
		"vive_cosmos_controller");

	private VrBindings() {
	}

	/** Just after Vivecraft writes openvr/input/action_manifest.json, before SteamVR reads it. */
	public static void addDefaults() {
		try {
			Path dir = Path.of("openvr", "input");
			Path manifest = dir.resolve("action_manifest.json");
			String template;
			try (InputStream in = VrBindings.class.getResourceAsStream("/assets/halloween/vr/pico_defaults.json")) {
				template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}
			JsonObject root = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
			JsonArray defaults = root.getAsJsonArray("default_bindings");
			Set<String> types = new LinkedHashSet<>(java.util.List.of(PICO));
			types.add("oculus_touch");
			String seen = ClientVr.controllerType();
			if (seen != null && !seen.isEmpty() && !VIVECRAFT.contains(seen) && !seen.startsWith("vive_tracker")) {
				// Whatever this headset's controllers call themselves, if Vivecraft has nothing for it.
				types.add(seen);
			}
			JsonArray kept = new JsonArray();
			for (JsonElement e : defaults) {
				if (!types.contains(e.getAsJsonObject().get("controller_type").getAsString())) {
					kept.add(e);
				}
			}
			for (String type : types) {
				String file = "halloween_" + type + ".json";
				Files.writeString(dir.resolve(file), template.replace("\"oculus_touch\"", "\"" + type + "\""));
				JsonObject entry = new JsonObject();
				entry.addProperty("controller_type", type);
				entry.addProperty("binding_url", file);
				kept.add(entry);
			}
			root.add("default_bindings", kept);
			Gson gson = new GsonBuilder().setPrettyPrinting().create();
			Files.writeString(manifest, gson.toJson(root));
			Halloween.LOGGER.info("VR bindings: defaults for {}", types);
		} catch (Throwable e) {
			Halloween.LOGGER.warn("VR bindings: could not be added ({})", e.toString());
		}
	}
}
