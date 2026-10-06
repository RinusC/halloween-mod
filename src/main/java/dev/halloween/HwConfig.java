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
	// Survivors shove the Shape (he no longer pushes them).
	public double survivorPushRange = 2.6;
	public double survivorPushStrength = 0.9;
	public int survivorPushCooldownTicks = 300;
	/** How long a shoved Shape is slowed, and by how much. */
	public int pushedSlowTicks = 25;
	public double pushedSlowModifier = -0.5;
	/** The Shape holding someone in a grab still shuffles along, this much slower. */
	public double grabSpeedModifier = -0.85;
	/** Jumping during a match (off: everyone stays on foot, bots and townsfolk too). */
	public boolean jumping = false;
	/** Pause between two reaction checks of a grab (on top of the round itself). */
	public int qteRoundGapTicks = 20;
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
	public float passiveStalkPerSecond = 0.056f;
	public double senseRange = 32;
	public double senseSpeedModifier = -0.45;
	public float senseStalkPerSecond = 0.154f;
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
	/** Shape Jump can't be left closer than this to a survivor or townsperson. */
	public double shapeJumpExitMinDistance = 2.0;
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

	// --- Firearms ---
	/** Rounds in the cylinder / magazine. */
	public int revolverCapacity = 3;
	public int rifleCapacity = 1;
	/** Stun meter one hit fills: the rifle knocks him down at once. */
	public float revolverStun = 0.30f;
	public float rifleStun = 1.0f;
	/** Damage to anyone else the bullet finds (survivors have 20). */
	public float revolverDamage = 7f;
	public float rifleDamage = 12f;
	public double revolverRange = 50;
	public double rifleRange = 110;
	/** Cone of fire in degrees: from the hip and aimed (right mouse), plus extra when walking and running. */
	public float revolverHipSpread = 5f;
	public float revolverAimSpread = 1.2f;
	public float rifleHipSpread = 7f;
	public float rifleAimSpread = 0.2f;
	public float moveSpread = 2.5f;
	public float runSpread = 6f;
	/** Ticks between revolver shots (a heavy double-action trigger). */
	public int revolverShotTicks = 9;
	/** Revolver: one round into the cylinder per this many ticks; rifle: a new round and the bolt worked. */
	public int revolverRoundTicks = 16;
	public int rifleReloadTicks = 80;
	/** Muzzle rise per shot in degrees (the camera kicks up). */
	public float revolverRecoil = 5f;
	public float rifleRecoil = 11f;
	/** Walking with the gun raised. */
	public double aimSpeedModifier = -0.35;
	/** Field of view while aiming (1 = no zoom). */
	public float revolverAimZoom = 0.82f;
	public float rifleAimZoom = 0.55f;
	/** A shot is heard far off: the Shape sees where for this long; townsfolk this close are frightened. */
	public int gunshotRevealTicks = 60;
	public double gunshotHearRange = 48;
	public float gunshotVolume = 6f;
	/** Rounds per find in a container, and how many finds of each are hidden. */
	public int revolverAmmoPerFind = 3;
	public int rifleAmmoPerFind = 2;
	public int revolverAmmoFinds = 2;
	/** Chance that a searched container also holds a revolver (loaded). */
	public float revolverFindChance = 0.01f;
	public int rifleAmmoFinds = 2;
	/** Gun case: cutting the lock with bolt cutters takes this long. */
	public int caseCutTicks = 200;
	/** Police revolvers: spare rounds, how long they take to aim, how far they shoot, how steady they are. */
	public int policeSpareRounds = 6;
	public int policeAimTicks = 14;
	public double policeShootRange = 22;
	public float policeSpread = 2.2f;
	/** Bots with a revolver (deputies): aim time and spread. */
	public int botAimTicks = 16;
	public float botSpread = 2.8f;

	// --- Police and Michael's wanted level ---
	/** Calls that can get through in a match; each needs a body someone has found. */
	public int policeMaxCalls = 3;
	/** Officers arriving at wanted levels 1, 2, 3. */
	public int[] policeOfficersByLevel = {2, 2, 2};
	/** Wanted levels whose police bring a van one survivor can leave in. */
	public int[] policeVanLevels = {1, 3};
	public String[] policeNames = {"Officer Miller", "Officer Hayes", "Officer Reed", "Officer Cole", "Officer Grant", "Officer Walsh"};
	public float policeHealth = 30f;
	public double policeWalkSpeed = 0.075;
	public double policeRunSpeed = 0.12;
	public double policeSightRange = 24;
	public double policeDarkSightRange = 10;
	public float policeFovDeg = 140f;
	/** How close an officer keeps to the one it guards. */
	public double policeGuardDistance = 3.5;
	/** The Shape this close to the one it guards: the officer goes for him even from further off. */
	public double policeGuardAlarm = 8;
	/** The Shape this close to the officer itself: it goes for him. */
	public double policeEngageRange = 12;
	/** An officer doesn't chase him further than this from the one it guards. */
	public double policeLeash = 20;
	public double policeHitRange = 2.6;
	public int policeHitCooldownTicks = 30;
	/** Stun meter a baton blow fills (a full meter knocks the Shape down). */
	public float policeBatonStun = 0.25f;
	/** How far a body can be seen, in light and in the dark, and across what field of view. */
	public double bodySightRange = 16;
	public double bodyDarkSightRange = 7;
	public float bodyFovDeg = 100f;
	public int scoreFindBody = 25;
	public int deputyRespawnSeconds = 30;
	public int maxDeputies = 2;
	/** Containers are looked for this far around the map centre. */
	public int lootRadius = 96;

	// --- Hold-to-interact durations (ticks) ---
	public int searchTicks = 30;
	/** Walking while rummaging through a container. */
	public double searchSpeedModifier = -0.6;
	public int boltCuttersTicks = 400;
	public int padlockKeyTicks = 400;
	public int closeCellarTicks = 40;
	public int installPartTicks = 60;
	public int repairCarTicks = 300;
	public int startCarTicks = 30;
	public int removePartTicks = 40;
	public int callPoliceTicks = 400;
	public int cutPhoneTicks = 40;
	public int repairPhoneTicks = 80;
	public int openGateTicks = 160;
	public int escapeTicks = 10;

	// --- Fighting back ---
	/** Stun meter drains this much per second; a full meter knocks the Shape down. */
	public float stunDecayPerSecond = 0.025f;
	public int stunnedTicks = 80;
	public int survivorHitCooldownTicks = 15;
	public float dartSpeed = 1.8f;
	public double flashlightRange = 20;
	public float flashlightConeDeg = 12f;
	/** The beam blinds the Shape only if he faces it within this angle. */
	public float flashlightFaceDeg = 70f;
	public int flashlightLightLevel = 12;
	/** A battery lasts this long switched on; spare batteries are found in containers. */
	public int flashlightChargeTicks = 200;

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
	/** Getting in quietly (not on the run) takes this long. */
	public int lockerQuietTicks = 30;
	/** Bots don't play the minigames: joining a breaker's wires takes them this long (fixing the car: repairCarTicks). */
	public int botWiresTicks = 200;
	/** Slipping off the line while fixing the car: the Shape sees where for this long. */
	public int minigameNoiseRevealTicks = 60;
	/** Bots and townsfolk keep away from a locker the Shape threw open this recently. */
	public int lockerAvoidAfterOpenTicks = 1200;

	// --- Reaction checks during long actions (police call, talking to townsfolk) ---
	public int skillCheckPeriodTicks = 30;
	public float skillCheckZoneWidth = 0.14f;
	public int skillCheckBonusTicks = 40;
	public int skillCheckFirstMinTicks = 30;
	public int skillCheckFirstMaxTicks = 60;
	public int skillCheckGapMinTicks = 50;
	public int skillCheckGapMaxTicks = 90;
	public float botSkillCheckChance = 0.6f;
	/** Talking a townsperson into something takes this long (reaction checks shorten it). */
	public int talkTicks = 300;
	public double talkRange = 4;

	// --- HUD ---
	/** Size of the match HUD relative to the GUI scale. */
	public double hudScale = 0.85;

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
