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
 * Arrays named "...ByTier" hold values for Bloodthirst levels 1, 2, 3; arrays named "...ByStalk"
 * hold values for stalk levels 0, 1, 2, 3. See docs/ORIGINAL_NOTES.md for where they come from.
 */
public class HwConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static HwConfig instance = new HwConfig();

	// --- Civilians ---
	public float civilianMaxHealth = 20f;

	// --- The Shape: movement and camera ---
	public double shapeSpeedModifier = -0.15;
	public double shapeCameraDistance = 2.6;
	public float shapeShoulderOffset = 0.75f;

	// --- Melee: light/heavy attacks with combos ---
	public float lightDamage = 4f;
	public float heavyDamage = 8f;
	public int lightCooldownTicks = 12;
	public int heavyCooldownTicks = 30;
	public int heavyChargeTicks = 8;
	public double attackReach = 3.2;
	public int comboWindowTicks = 30;
	public float[] comboMultiplier = {1.0f, 1.15f, 1.3f};
	public float[] damageMultiplierByTier = {1.0f, 1.25f, 1.5f};

	// --- Push (shove): interrupts and unbalances ---
	public double pushRange = 2.6;
	public double pushStrength = 0.55;
	public int pushCooldownTicks = 60;
	public int pushStaggerTicks = 30;
	public double staggerSpeedModifier = -0.6;

	// --- Dash (acceleration): fast burst, loud steps ---
	public int dashTicks = 30;
	public double dashSpeedModifier = 0.9;
	public int[] dashCooldownByTier = {140, 120, 100};
	public float dashStepVolume = 2.2f;

	// --- Stalk ---
	/** Passive stalking (just watching) can raise a victim to this level at most. */
	public float passiveStalkCap = 1f;
	public double passiveStalkRange = 20;
	public float passiveStalkAngleDeg = 25f;
	public float passiveStalkPerSecond = 0.08f;
	public double senseRange = 32;
	public double senseSpeedModifier = -0.45;
	public float senseStalkPerSecond = 0.22f;
	/** Reticle on a focus point counts fully; anywhere else on the body only partly. */
	public float reticleFocusAngleDeg = 5f;
	public float reticleBodyAngleDeg = 18f;
	public float bodyAimMultiplier = 0.3f;
	public float stillnessMultiplier = 1.5f;
	public float movingMultiplier = 0.8f;
	public double proximityNear = 4;
	public float proximityNearMultiplier = 1.6f;
	public float proximityFarMultiplier = 0.6f;
	public float focusMultiplier = 2.0f;
	public double focusSpeedModifier = -0.85;
	public float senseFatigueSeconds = 20f;
	public float senseRecoverySeconds = 10f;
	public float senseFatiguePenalty = 0.6f;
	/** How long a victim stays marked after the last stalk gain, by stalk level. */
	public int[] markTicksByStalk = {0, 160, 300, 600};
	public float[] damageTakenByStalk = {1.0f, 1.1f, 1.25f, 1.5f};
	public double[] speedByStalk = {0, -0.05, -0.1, -0.15};

	// --- Bloodthirst: 3 levels of 100 points each ---
	public float bloodthirstPerStalkLevel = 30f;
	public float bloodthirstPerGrab = 10f;
	public float bloodthirstPerKill = 40f;
	public float bloodthirstPerWindowKill = 30f;
	public float bloodthirstPerCorpseSecond = 4f;
	public int corpseAdmireTicks = 200;
	public double corpseAdmireRange = 8;
	public float bloodthirstLightLossPerSecond = 3f;
	public float bloodthirstDamageLoss = 15f;
	public float bloodthirstFallLoss = 20f;
	public double bloodthirstFallDistance = 3.5;
	/** Bloodthirst level (1-3) that unlocks bloody weapon executions. */
	public int bloodyExecutionLevel = 3;
	/** Bloodthirst level (1-3) from which Shape Jump shows light beams over victims. */
	public int beamsMinLevel = 2;

	// --- Grab: race of reaction checks ---
	public double grabRange = 2.8;
	public int[] grabCooldownByTier = {160, 120, 80};
	public int victimPointsToEscape = 4;
	/** Hits the Shape needs for the kill, by the victim's stalk level 0..3: stalking makes the kill easier. */
	public int[] shapePointsToKillByStalk = {5, 5, 4, 3};
	/** If true, a victim's miss also gives the Shape a point. */
	public boolean victimMissGivesShapePoint = false;
	public int victimRoundTicks = 50;
	public int victimCursorPeriodTicks = 30;
	public float victimZoneWidth = 0.24f;
	public float[] victimZoneShrinkByTier = {0f, 0.03f, 0.06f};
	public float[] victimZoneShrinkByStalk = {0f, 0.02f, 0.04f, 0.07f};
	public int shapeRoundTicks = 60;
	public int shapeNeedlePeriodTicks = 40;
	public float shapeZoneWidth = 0.16f;
	public float[] shapeZoneGrowByStalk = {0f, 0.02f, 0.04f, 0.06f};
	public float mannequinQteSuccessChance = 0.35f;
	public int escapeShapeRecoverTicks = 30;
	public int finisherTicks = 40;
	public int windowSearchRadius = 3;
	public int windowFinisherStalkLevel = 3;

	// --- Shape Jump (Поступь Призрака) ---
	public int[] shapeJumpCooldownByTier = {600, 480, 360};
	public double[] shapeJumpSpeedByTier = {0.6, 0.9, 1.2};
	public int lightThreshold = 7;
	public double civilianSightRange = 40;
	public float civilianSightFovDeg = 55f;
	public float stingerVolume = 2.5f;

	// --- Detection Impulse ---
	public double impulseRange = 60;
	public int impulseDurationTicks = 80;
	public int[] impulseCooldownByTier = {900, 750, 600};

	// --- Score (XP feed) ---
	public int scorePassiveStalk = 10;
	public int scoreStalkLevel = 25;
	public int scoreGrab = 50;
	public int scoreKill = 100;
	public int scoreKillPerStalkLevel = 50;
	public int scoreWindow = 75;
	public int scoreBloody = 75;
	public int scoreCorpse = 25;

	// --- Doors: civilians lock, the Shape breaks them down ---
	public int lockTicks = 30;
	/** Unlocking takes twice as long as locking. */
	public int unlockTicks = 60;
	public int doorBreakPoints = 4;
	public int doorLightHitPoints = 1;
	public int doorHeavyHitPoints = 2;

	// --- Windows: civilians climb through ---
	public int vaultSlowTicks = 60;
	public int vaultFastTicks = 20;
	/** Damage from glass when jumping through fast, or from shards of a broken window, as a share of max health. */
	public float vaultDamageFraction = 0.2f;

	// --- Match ---
	public int matchSeconds = 1200;
	public int countdownSeconds = 10;
	/** Back gates can be opened only during the last this many seconds. */
	public int gateOpenLastSeconds = 300;
	public int policeArriveSeconds = 120;
	public int policeStaySeconds = 90;
	public int deputyRespawnSeconds = 30;
	public int maxDeputies = 2;
	/** Containers are looked for this far around the map centre. */
	public int lootRadius = 96;

	// --- Hold-to-interact durations (ticks) ---
	public int searchTicks = 30;
	public int boltCuttersTicks = 60;
	public int padlockKeyTicks = 30;
	public int closeCellarTicks = 40;
	public int installPartTicks = 60;
	public int repairCarTicks = 100;
	public int startCarTicks = 30;
	public int removePartTicks = 40;
	public int callPoliceTicks = 200;
	public int cutPhoneTicks = 40;
	public int repairPhoneTicks = 80;
	public int openGateTicks = 160;
	public int escapeTicks = 10;

	// --- Fighting back ---
	/** Stun meter drains this much per second; a full meter knocks the Shape down. */
	public float stunDecayPerSecond = 0.05f;
	public int stunnedTicks = 80;
	public int survivorHitCooldownTicks = 15;
	public float dartSpeed = 1.8f;
	public double flashlightRange = 20;
	public float flashlightConeDeg = 12f;
	/** The beam blinds the Shape only if he faces it within this angle. */
	public float flashlightFaceDeg = 70f;
	public int flashlightLightLevel = 12;

	// --- Civilian score ---
	public int scoreEscape = 500;
	public int scoreItemFound = 25;
	public int scoreExitProgress = 100;
	public int scoreCallPolice = 150;
	public int scoreHitShape = 20;
	public int scoreStunShape = 150;
	public int scoreRescueNpc = 200;
	public int scoreHiddenNpc = 100;

	// --- Bots (fill the civilians' side when people are missing) ---
	/** Bots are added until there are this many civilians (only when someone plays the Shape). 0 = no bots. */
	public int botsFillTo = 4;
	public String[] botNames = {"Laurie", "Tommy", "Lindsey", "Annie", "Lynda", "Bob", "Marion", "Lonnie"};
	public float botQteSuccessChance = 0.55f;
	public int botReactionMinTicks = 8;
	public int botReactionMaxTicks = 24;
	public double botSightRange = 28;
	/** How far bots make out the Shape when he stands in the dark. */
	public double botDarkSightRange = 9;
	public float botFovDeg = 140f;
	/** Footsteps this close give the Shape away even unseen (not in Shape Jump). */
	public double botHearRange = 4;
	public double botHearAlertRange = 40;
	public int botMemoryTicks = 160;
	public double botFleeDistance = 18;
	/** Out of sight this long after fleeing: hide (with {@link #botHideChance}) or carry on. */
	public int botCalmTicks = 60;
	public float botHideChance = 0.6f;
	public int botHideMinTicks = 160;
	public int botHideMaxTicks = 360;
	/** A callout puts the Shape this far away: hiding bots come out early. */
	public double botHideReleaseDistance = 30;
	/** Bots lock a door behind them only when the Shape is at least this far back. */
	public double botLockMinDistance = 4;
	/** Closer than this, a fleeing bot first looks for a window right at hand. */
	public double botWindowEscapeDistance = 7;
	/** At least this far ahead of the Shape, a fleeing bot runs behind a nearby door and locks it. */
	public double botShelterMinDistance = 4;
	public int botLockerRange = 14;
	/** Bots hide in a locker only this far from where they last saw the Shape (he searches around there). */
	public double botLockerAwayFromSighting = 9;
	/** Bots tell neighbours at home this close to hide (when it is calm). */
	public double botWarnRange = 16;
	/** On the way to an exit, bots take a neighbour this close along. */
	public double botEscortRange = 14;
	/** The Shape this close (by the proximity music): bots start nothing long (gates, calls, repairs). */
	public double botCautionDistance = 12;
	/** While the music plays, bots look all around about this often (ticks). */
	public int botLookAroundTicks = 40;
	public int botCalloutCooldownTicks = 300;
	public float botSwingWhenCaughtChance = 0.6f;

	// --- Townsfolk ---
	public int npcMax = 8;
	public String[] npcNames = {"Mrs. Elrod", "Mr. Riddle", "Mrs. Blankenship", "Mr. Garrett", "Mrs. Wallace", "Mr. Doyle",
		"Mrs. Brackett", "Mr. Sheldon"};
	public double npcSightRange = 22;
	public double npcDarkSightRange = 8;
	public float npcFovDeg = 150f;
	public double npcWalkSpeed = 0.07;
	public double npcRunSpeed = 0.115;
	public int npcScreamCooldownTicks = 200;
	public float npcCallChance = 0.5f;
	public double npcPhoneRange = 28;
	/** Townsfolk at home hear a scream or a battered door this far away and go hide (or call). */
	public double npcHearRange = 18;
	public int npcCalmTicks = 80;
	public int npcHideMinTicks = 400;
	public int npcHideMaxTicks = 900;
	public double npcFollowLoseDistance = 32;
	/** A townsperson brought this close to any exit counts as rescued. */
	public double npcRescueRadius = 4;
	/** How long survivors see where a scream pointed at. */
	public int alertTicks = 120;

	// --- Lockers ---
	/** How long you can stay in a locker before you have to come out for air. */
	public int lockerMaxTicks = 1200;
	/** Getting in on the run: the Shape sees where for this long. */
	public int lockerSlamRevealTicks = 60;

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

	public static float at(float[] values, int index) {
		return values[Math.min(Math.max(index, 0), values.length - 1)];
	}

	public static int at(int[] values, int index) {
		return values[Math.min(Math.max(index, 0), values.length - 1)];
	}

	public static double at(double[] values, int index) {
		return values[Math.min(Math.max(index, 0), values.length - 1)];
	}
}
