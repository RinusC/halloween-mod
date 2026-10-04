package dev.halloween;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Every gameplay number lives here so it can be tuned against footage of the original
 * without recompiling. Stored as config/halloween.json. Tick values are 20 per second.
 * Arrays indexed by tier hold values for Bloodthirst tiers I, II, III.
 */
public class HwConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static HwConfig instance = new HwConfig();

	// --- Civilians ---
	public float civilianMaxHealth = 20f;

	// --- The Shape: movement and melee ---
	public double shapeSpeedModifier = -0.15;
	public float shapeBaseDamage = 5f;
	public int shapeAttackCooldownTicks = 24;
	public float[] tierDamageMultiplier = {1.0f, 1.25f, 1.5f};

	// --- Killer Sense / Stalk ---
	public double senseRange = 32;
	public double senseSpeedModifier = -0.45;
	public float senseFocusAngleDeg = 6f;
	public float senseBodyAngleDeg = 25f;
	public float stalkPerSecondFocus = 14f;
	public float stalkPerSecondBody = 2.5f;
	public int focusPointSwitchTicks = 60;
	public float senseFatigueSeconds = 20f;
	public float senseRecoverySeconds = 10f;
	public float senseFatiguePenalty = 0.75f;
	public float stalkWeakenedThreshold = 33f;
	public float stalkMarkedThreshold = 66f;
	public float weakenedDamageMultiplier = 1.25f;
	public double weakenedSpeedModifier = -0.1;

	// --- Bloodthirst ---
	public float[] bloodthirstTierThresholds = {0f, 35f, 70f};
	public float bloodthirstPerStalk = 0.25f;
	public float bloodthirstPerKill = 20f;
	public float bloodthirstPerWindowKill = 30f;

	// --- Grab and QTE ---
	public double grabRange = 2.8;
	public int[] grabCooldownTicks = {160, 120, 80};
	public int qteSuccessesToEscape = 3;
	public int qteMaxMisses = 3;
	public int qteRoundTicks = 50;
	public int qteCursorPeriodTicks = 30;
	public float qteZoneWidth = 0.24f;
	public float[] qteZoneShrinkPerTier = {0f, 0.04f, 0.08f};
	public float mannequinQteSuccessChance = 0.35f;
	public int escapeShapeRecoverTicks = 30;
	public int finisherTicks = 40;
	public int windowSearchRadius = 3;
	public float windowFinisherStalkRequired = 100f;

	// --- Shape Jump ---
	public int shapeJumpDurationTicks = 100;
	public int[] shapeJumpCooldownTicks = {600, 480, 360};
	public double[] shapeJumpSpeedModifier = {0.6, 0.9, 1.2};
	public int lightThreshold = 7;
	public double civilianSightRange = 40;
	public float civilianSightFovDeg = 55f;
	public float stingerVolume = 2.5f;

	// --- Lights ---
	public int breakerRadius = 14;

	// --- Audio ---
	public double themeRange = 28;

	public static HwConfig get() {
		return instance;
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("halloween.json");
	}

	public static void load() {
		Path path = path();
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path)) {
				HwConfig loaded = GSON.fromJson(reader, HwConfig.class);
				if (loaded != null) {
					instance = loaded;
				}
			} catch (IOException | RuntimeException e) {
				Halloween.LOGGER.error("Failed to read {}, using defaults", path, e);
			}
		}
		// Rewrite so newly added fields show up in the file.
		try (Writer writer = Files.newBufferedWriter(path)) {
			GSON.toJson(instance, writer);
		} catch (IOException e) {
			Halloween.LOGGER.error("Failed to write {}", path, e);
		}
	}

	public static float tierValue(float[] values, int tier) {
		return values[Math.min(Math.max(tier, 0), values.length - 1)];
	}

	public static int tierValue(int[] values, int tier) {
		return values[Math.min(Math.max(tier, 0), values.length - 1)];
	}

	public static double tierValue(double[] values, int tier) {
		return values[Math.min(Math.max(tier, 0), values.length - 1)];
	}
}
