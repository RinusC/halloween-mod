package dev.halloween;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Which build of the mod this is. The full game ("halloween") has three killers; the Michael-only build is a separate
 * jar with its own mod id ("halloween_michael") that carries none of the Demogorgon's or Vecna's art and sound and
 * offers no way to pick them. Both builds come from one source tree (see build.gradle, -Pedition).
 */
public final class Edition {
	/** The Michael-only build: Michael is the only killer. */
	public static final boolean MICHAEL_ONLY = FabricLoader.getInstance().isModLoaded("halloween_michael");

	private Edition() {
	}
}
