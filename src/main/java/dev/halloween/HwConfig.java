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
	public float heavyDamage = 7f;
	public int lightCooldownTicks = 18;
	public int heavyCooldownTicks = 45;
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
	public int[] dashCooldownByTier = {180, 160, 140};
	/** After Shape Jump ends, he can't speed up for this long. */
	public int dashAfterShadowTicks = 40;

	// --- Stalk ---
	/** Passive stalking (just watching) can raise a victim to this level at most. */
	public float passiveStalkCap = 1f;
	public double passiveStalkRange = 25;
	public float passiveStalkAngleDeg = 25f;
	public float passiveStalkPerSecond = 0.0487f;
	public double senseRange = 37;
	public double senseSpeedModifier = -0.45;
	public float senseStalkPerSecond = 0.1339f;
	/** Reticle on a focus point counts fully; anywhere else on the body only partly. */
	public float reticleFocusAngleDeg = 5f;
	public float reticleBodyAngleDeg = 18f;
	public float bodyAimMultiplier = 0.3f;
	/** Stalked on this long, a focus point bursts (all burst: they grow back). */
	public int focusPointBurstTicks = 30;
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
	public int beamsMinLevel = 3;
	/** The beams flash up for this long once every period, from the moment he enters Shape Jump. */
	public int beamsShowTicks = 40;
	public int beamsPeriodTicks = 200;

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
	public float[] victimZoneShrinkByStalk = {0f, 0.03f, 0.06f, 0.10f};
	public int shapeRoundTicks = 60;
	public int shapeNeedlePeriodTicks = 40;
	public float shapeZoneWidth = 0.16f;
	public float[] shapeZoneGrowByStalk = {0f, 0.02f, 0.04f, 0.06f};
	public float mannequinQteSuccessChance = 0.8f;
	/** Police officers are trained: they hit their reaction checks in a grab this often. */
	public float policeQteSuccessChance = 0.9f;
	public int escapeShapeRecoverTicks = 30;
	public int finisherTicks = 40;
	public int windowSearchRadius = 3;
	/** Victim stalk level a window throw needs (0: any victim, wherever he grabbed them). */
	public int windowFinisherStalkLevel = 0;

	// --- Shape Jump (Поступь Призрака) ---
	public int[] shapeJumpCooldownByTier = {600, 480, 360};
	/** Shape Jump can't be left closer than this to a survivor or townsperson. */
	public double shapeJumpExitMinDistance = 2.0;
	public double[] shapeJumpSpeedByTier = {0.7, 1.0, 1.35};
	/** Block light above this counts as light: Shape Jump can't be left there. */
	public int lightThreshold = 6;
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
	public int matchSeconds = 1500;
	/** The opening scenes before a match starts (everyone stands still meanwhile; under 3 s plays none). */
	public int countdownSeconds = 15;
	/** Back gates can be opened only during the last this many seconds (0: any time, as in the original). */
	public int gateOpenLastSeconds = 0;
	public int policeArriveSeconds = 120;
	public int policeStaySeconds = 90;

	// --- Firearms ---
	/** Rounds in the cylinder / magazine. */
	public int revolverCapacity = 3;
	public int rifleCapacity = 1;
	/** Stun meter one hit fills: the rifle knocks him down at once. */
	public float revolverStun = 0.35f;
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
	/** Each search that turns up no escape item adds this to the chance that the next one does (an escape item still hidden moves there). */
	public float escapeFindStep = 0.04f;
	/** Crows about town (see Birds): how many flocks, how often a new one settles, how near one scares them (running, walking; crouched, never). */
	public int birdFlocks = 10;
	public int birdFlockEveryTicks = 600;
	public double birdScareRun = 11;
	public double birdScareWalk = 8;
	/** How far off the caw of a crow put up is heard. */
	public float birdCawRange = 30;
	/** The Demogorgon moves this many times as fast as Michael. */
	public float demoSpeedFactor = 1.2f;
	/** Arrest: knocked down this many times within this many ticks with an officer this close, and the survivors win. */
	public int arrestKnockdowns = 5;
	public int arrestWindowTicks = 1200;
	public double arrestRange = 10;
	public float revolverRecoil = 5f;
	public float rifleRecoil = 11f;
	/** Walking with the gun raised. */
	public double aimSpeedModifier = -0.35;
	/** Field of view while aiming (1 = no zoom). */
	public float revolverAimZoom = 0.93f;
	public float rifleAimZoom = 0.8f;
	/** Of a kick, this share comes back down by itself over the next few ticks (the hands bring it back). */
	public float recoilReturn = 0.35f;
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
	/** Shotgun: shells in the tube, pellets per shot, their cone, reach and what each pellet does up close. */
	public int shotgunCapacity = 4;
	public int shotgunPellets = 8;
	public float shotgunHipSpread = 6f;
	public float shotgunAimSpread = 4f;
	public double shotgunRange = 26;
	/** Within this distance pellets hit with full force, then fade out to nothing at the range. */
	public double shotgunFullForce = 6;
	public float shotgunPelletStun = 0.12f;
	public float shotgunPelletDamage = 2.5f;
	public int shotgunPumpTicks = 16;
	public int shotgunShellTicks = 18;
	public float shotgunRecoil = 9f;
	public float shotgunAimZoom = 0.92f;
	public int shotgunShellsPerFind = 4;
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
	public int[] policeOfficersByLevel = {1, 2, 1};
	/** Search furniture closer than this to another on the same floor is taken away at the start of a match (one a room or so). */
	public double lootMinSpacing = 4.5;
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
	/** The Shape holding someone this close to an officer: it drops everything and runs to help. */
	public double policeHelpRange = 20;
	/** Officers get a locked door open in this long (they don't break it). */
	public int policeUnlockTicks = 30;
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
	/** The Runt (game.Runt): in every match unless switched off; a bot plays him unless /hw runt picked someone. */
	public boolean runtEnabled = true;
	public float runtScale = 0.55f;
	/** His walking speed (the movement attribute: 0.1 is a person walking, 4.3 m/s): quicker than a walk, slower than a run. */
	public float runtSpeed = 0.115f;
	public float runtScareReach = 2.6f;
	public int runtScareCooldownTicks = 300;
	public float runtScarePanic = 0.35f;
	public int runtStaggerTicks = 25;
	public int runtScareRevealTicks = 100;
	/** Fear it takes to reach levels 1, 2, 3; what feeds it. */
	public int[] runtLevelFear = {30, 80, 150};
	public float runtFearScare = 10f;
	public float runtFearNpc = 5f;
	public float runtFearMischief = 4f;
	public float runtFearHeldForMichael = 15f;
	public int runtFearPassiveTicks = 200;
	public float runtScorePerFear = 10f;
	/** Clinger: how long he holds on, how many shoves shake him off, the pause after. */
	public int runtHoldTicks = 100;
	/** The pounce: crouch to wind up (at least, at most), how far it carries then, how long he lies after a miss, the pause before the next. */
	public int runtPounceMinChargeTicks = 8;
	public int runtPounceChargeTicks = 30;
	public double runtPounceMinDistance = 3;
	public double runtPounceMaxDistance = 9;
	public int runtPounceMissStunTicks = 100;
	public int runtPounceCooldownTicks = 500;
	/** His camera's distance while he rides someone (the default is 4; it is scaled with his size). */
	public double runtRideCameraDistance = 9;
	public float runtFearPounce = 12f;
	public float runtFearPounceNpc = 8f;
	/** A bot he holds tears his hands off in this many ticks, give or take. */
	public int runtBotFreeTicks = 120;
	/** Fear for really frightening people (see game.RuntFear). */
	public double runtAmbushRange = 5;
	public int runtAmbushHiddenTicks = 200;
	public int runtAmbushCooldownTicks = 600;
	public float runtAmbushPanic = 0.25f;
	public float runtFearAmbush = 12f;
	public float runtEdgeFearPerSecond = 0.6f;
	public float runtWindowFearPerSecond = 1.5f;
	public int runtLaughCooldownTicks = 240;
	public double runtLaughRange = 20;
	public float runtLaughPanic = 0.08f;
	public float runtFearLaugh = 3f;
	public int runtKnockCooldownTicks = 160;
	public double runtKnockRange = 10;
	public float runtKnockPanic = 0.1f;
	public float runtFearKnock = 4f;
	public int runtHoldCooldownTicks = 500;
	/** Hex: how long no breath comes back and no healing works (the breath comes back at this rate meanwhile). */
	public int runtHexTicks = 600;
	public float runtHexRegen = 0.2f;
	/** Shared sight (the "scent" gift): how far and how wide (degrees off the look) each sees for the other. */
	public int runtScentRange = 48;
	public double runtSharedSightAngle = 60;
	public int runtMischiefCooldownTicks = 400;
	/** Dread: how far it reaches, the panic it gives every second, how much shorter his pause between frights. */
	public int runtDreadRange = 8;
	public float runtDreadPanic = 0.03f;
	public float runtDreadCooldownFactor = 0.5f;
	public int runtSwapCooldownTicks = 2400;
	/** Fighting him off (he is never sent out of the game): a shove's push and how long it floors him, a blow, a shot or a flare. */
	public double runtShoveStrength = 1.4;
	public int runtShoveStunTicks = 60;
	public int runtHitStunTicks = 80;
	public int runtShotStunTicks = 140;
	public int runtTrappedTicks = 80;
	public int scoreHitRunt = 50;
	public int scoreShakeRunt = 75;
	// --- Match events (see game.Events) ---
	public boolean eventsEnabled = true;
	/** The first comes this far in (seconds), the next this long after the one before began; at most this many. */
	public int eventFirstMinSeconds = 360;
	public int eventFirstMaxSeconds = 480;
	public int eventGapMinSeconds = 300;
	public int eventGapMaxSeconds = 420;
	public int eventMaxPerMatch = 3;
	/** None begin in the last minutes (Midnight has them). */
	public int eventQuietEndSeconds = 360;
	public int eventAnnounceSeconds = 10;
	/** The side that is behind gets the events that help it this much more often. */
	public float eventCatchUpFactor = 2f;
	public java.util.Map<String, Integer> eventWeights = defaultEventWeights();
	/** Points for answering Loomis, emptying the bag, mending the transformer, the find in the fire (half for waving the patrol down). */
	public int scoreEvent = 75;
	/** Loomis's call: how long the phone rings, how far from Michael it is, how long answering takes, then how long he shows and the find is marked. */
	public int loomisSeconds = 60;
	public double loomisMinDistance = 40;
	public int loomisAnswerTicks = 100;
	public int loomisRevealTicks = 200;
	public int loomisItemMarkTicks = 1200;
	/** The forgotten bag: how long it lies there, how far from Michael, the search, what is in it (one rare, two uncommon). */
	public int bagSeconds = 120;
	public double bagMinDistance = 25;
	public int bagSearchTicks = 80;
	public String[] bagRare = {"med_kit", "flare_gun", "shotgun_shells", "amulet"};
	public String[] bagUncommon = {"healing_spray", "flashlight", "firecrackers", "road_flare", "revolver_ammo", "pocket_knife", "battery"};
	/** Brackett's patrol: how long, how fast (blocks a tick), how far and wide its headlights see, how often it stops, how long the officer stays. */
	public int patrolSeconds = 120;
	public double patrolSpeed = 0.3;
	public double patrolSightRange = 12;
	public double patrolSightAngle = 35;
	public int patrolMaxStops = 2;
	public int patrolGuardSeconds = 60;
	/** A bot this frightened by the road waves it down. */
	public float patrolBotPanic = 0.15f;
	/** Blackout: how long, how far from Michael the transformer is. */
	public int blackoutSeconds = 90;
	public double blackoutBoxMinDistance = 30;
	/** Samhain night: how long, stalking this much faster, the Runt's pause between pounces this share of its usual. */
	public int samhainSeconds = 60;
	public float samhainStalkFactor = 2f;
	public float samhainPounceFactor = 0.5f;
	/** The party: how long, how far round it nothing is heard, how far townsfolk come from and what share of them. */
	public int partySeconds = 120;
	public double partyMuffleRange = 20;
	public double partyGatherRange = 90;
	public float partyGatherShare = 0.6f;
	/** The fire: how long, flames on the roof and inside, how far the smoke hides things. */
	public int fireSeconds = 150;
	public int fireRoofFlames = 12;
	public int fireInsideFlames = 7;
	public double fireSmokeRadius = 6;
	/** Health lost every second by anyone inside the burning house (smoke and heat; it never kills, see Events). */
	public float fireSmokeDamage = 1.5f;
	/** Midnight: this long before the end; Michael sees everyone this often, for this long. */
	public boolean midnightEnabled = true;
	public int midnightSeconds = 300;
	public int midnightRevealEverySeconds = 60;
	public int midnightRevealTicks = 100;

	public static java.util.Map<String, Integer> defaultEventWeights() {
		java.util.Map<String, Integer> w = new java.util.LinkedHashMap<>();
		w.put("loomis", 3);
		w.put("bag", 3);
		w.put("patrol", 2);
		w.put("blackout", 3);
		w.put("samhain", 2);
		w.put("party", 3);
		w.put("fire", 1);
		w.put("bell", 2);
		w.put("masks", 2);
		return w;
	}

	/** Hand-to-hand weapons are found out of doors (see StreetWeapons): how many a match, how far apart at least. */
	public int streetWeapons = 24;
	public int streetWeaponSpacing = 12;

	// --- Items from the original: what the containers hide, throwing, first aid, flares, relics ---
	/** On top of one of each escape item, this many more picked at random. */
	public int extraEscapeItems = 1;
	/** Storm cellars open for escape in a match (each takes one survivor). */
	public int cellarsPerMatch = 2;
	/** Share of the containers left after the escape items that hide something. */
	public float lootFillShare = 0.55f;
	/** How likely each find is, as draw weights (common 10, uncommon 5, rare 2, very rare 1). */
	public java.util.Map<String, Integer> lootWeights = defaultLootWeights();
	/** Each relic (amulet, hourglass, brass lantern) turns up in a match with this chance. */
	public float relicChance = 0.35f;
	public float throwSpeed = 1.4f;
	/** A thrown brick, rock or ball that hits the Shape also staggers him this long. */
	public int throwStaggerTicks = 15;
	public int firecrackerFuseTicks = 30;
	public double firecrackerRange = 6;
	public float firecrackerStun = 0.6f;
	public int firecrackerBlindTicks = 40;
	/** The bang is heard far off: the Shape sees where for this long. */
	public int firecrackerRevealTicks = 80;
	public int roadFlareTicks = 600;
	/** A burning flare blinds the Shape this close if he looks at it. */
	public double flareBlindRange = 7;
	public float flareGunStun = 1.0f;
	public int flareGunBlindTicks = 60;
	public int flareGunBurnTicks = 200;
	/** The flare gun fired at least this steeply up is a distress signal: a police call (a body must be found). */
	public float flareSkyPitch = 45f;
	public float sprayHeal = 6f;
	public double sprayRange = 3.5;
	public int sprayCooldownTicks = 20;
	/** The med kit heals fully, standing still this long. */
	public int medKitTicks = 60;
	public int amuletBlindTicks = 60;
	public int hourglassExtraTicks = 100;
	public int lanternLightLevel = 13;
	public double lanternBlindRange = 6;
	/** The Shape this close to someone carrying the brass lantern shows up on their screen, even in Shape Jump. */
	public double lanternRevealRange = 16;
	/** Bots stab their way out of a grab this long after it starts. */
	public int botStabTicks = 25;
	/** Bots patch themselves up below this share of their health when nothing is after them. */
	public float botHealBelow = 0.6f;
	/** Pouring the gas can into the car takes this long. */
	public int refuelTicks = 80;
	public int installFuseTicks = 60;

	public static java.util.Map<String, Integer> defaultLootWeights() {
		java.util.Map<String, Integer> w = new java.util.LinkedHashMap<>();
		// Common.
		w.put("rock", 10);
		w.put("brick", 10);
		w.put("baseball", 8);
		w.put("kitchen_knife", 10);
		w.put("bat", 8);
		w.put("battery", 3);
		// Uncommon.
		w.put("dart", 5);
		w.put("pocket_knife", 5);
		w.put("firecrackers", 5);
		w.put("road_flare", 5);
		w.put("healing_spray", 5);
		w.put("flashlight", 5);
		w.put("hatchet", 5);
		w.put("cleaver", 5);
		w.put("pipe_wrench", 5);
		w.put("fire_poker", 5);
		w.put("revolver_ammo", 4);
		w.put("shotgun_shells", 3);
		w.put("bear_trap", 4);
		w.put("tin_cans", 5);
		// Rare.
		w.put("butterfly_knife", 2);
		w.put("ice_pick", 2);
		w.put("axe", 2);
		w.put("pickaxe", 2);
		w.put("pitchfork", 2);
		w.put("med_kit", 2);
		w.put("flare_gun", 2);
		// Very rare.
		w.put("shotgun", 1);
		return w;
	}

	private static java.util.Map<String, Integer> defaultPerkCosts() {
		java.util.Map<String, Integer> c = new java.util.LinkedHashMap<>();
		c.put("quick_search", 1200);
		c.put("stamina", 1800);
		c.put("quiet", 2250);
		c.put("tough", 3000);
		c.put("adrenaline", 1800);
		c.put("barricade", 1500);
		c.put("glass_walker", 1200);
		c.put("brimstone", 1200);
		c.put("contender", 2250);
		c.put("lethal_pitch", 1800);
		c.put("elusory", 3000);
		c.put("field_triage", 1200);
		c.put("slippery", 3750);
		c.put("unphased", 1500);
		c.put("stubborn", 1800);
		c.put("repair_expert", 1500);
		c.put("trespasser", 1200);
		c.put("smooth_talker", 1500);
		c.put("witness", 2250);
		return c;
	}

	// --- Hold-to-interact durations (ticks) ---
	public int searchTicks = 30;
	/** Walking while rummaging through a container. */
	public double searchSpeedModifier = -0.6;
	public int boltCuttersTicks = 400;
	public int padlockKeyTicks = 400;
	public int closeCellarTicks = 40;
	public int installPartTicks = 60;
	public int repairCarTicks = 300;
	public int startCarTicks = 60;
	/** Survivors the car takes (it goes when they are all in, or when the driver says so); how long a bot at the wheel waits for the others. */
	public int carSeats = 2;
	public int carWaitTicks = 160;
	/** The Shape in Shape Jump passes through a window this slowly (no glass broken). */
	public int shapeWindowTicks = 50;
	/** A survivor below this share of their health leaves a blood trail. */
	public float bloodTrailBelowHealth = 0.999f;
	/** Ticks between drips while they move (at least a block), and while they stand still. */
	public int bloodTrailMovingTicks = 12;
	public int bloodTrailStillTicks = 60;
	/** How long a drip stays on the ground before it dries up. */
	public int bloodTrailSeconds = 30;
	/** Stamina a survivor gets back at once when the killer hits them. */
	public float staminaOnHit = 0.2f;
	/** The Demogorgon after a Shred that caught nobody (no door, no window either): this slow (speed modifier), this long. */
	public float demoMissSlowSpeed = -0.7f;
	public int demoMissSlowTicks = 20;
	/** Torn by a Shred: this much faster (share) for this long. */
	public float demoHitBurst = 0.5f;
	public int demoHitBurstTicks = 40;
	/** Survivors and townsfolk don't start within this many blocks of where the killer starts. */
	public int spawnAwayFromKiller = 40;
	/** The killer searching an empty wardrobe stands at it this long. */
	public int lockerSearchTicks = 40;
	/** He can grab someone climbing through a window this much further than his usual reach. */
	public double grabVaultBonusRange = 1.0;
	/** The Demogorgon on a blood trail: drips this near him give him the scent of whoever bled them (stalk levels a second). */
	public double demoScentRange = 2.5;
	public float demoScentPerSecond = 0.3f;
	public int removePartTicks = 40;
	public int callPoliceTicks = 400;
	public int cutPhoneTicks = 40;
	public int repairPhoneTicks = 80;
	public int openGateTicks = 160;
	public int escapeTicks = 10;

	// --- Fighting back ---
	/** Stun meter drains this much per second; a full meter knocks the Shape down. */
	public float stunDecayPerSecond = 0.025f;
	public int stunnedTicks = 120;
	/** The last this many ticks of a stun he spends getting back up (the animation). */
	public int stunRiseTicks = 20;
	public int survivorHitCooldownTicks = 15;
	public float dartSpeed = 1.8f;
	public double flashlightRange = 20;
	public float flashlightConeDeg = 12f;
	/** The beam blinds the Shape only if he faces it within this angle. */
	public float flashlightFaceDeg = 70f;
	public int flashlightLightLevel = 12;
	/** A battery lasts this long switched on; spare batteries are found in containers. */
	/** Stamina: ticks of running from full to empty; ticks to fill back up walking, and standing or hiding. */
	public int staminaRunTicks = 160;
	public int staminaWalkRegenTicks = 200;
	public int staminaRestRegenTicks = 100;
	/** It starts coming back this long after the run stops. */
	public int staminaRegenDelayTicks = 20;
	/** Run dry: no running until this much is back. */
	public double staminaRecoverFraction = 0.34;
	/** A shove or a melee blow takes this much stamina (not possible with less). */
	public float staminaActionCost = 0.2f;
	/** Bear trap: how long it holds Michael; points for catching him, for the cans giving him away. */
	public int bearTrapTicks = 60;
	public int scoreTrap = 150;
	public int scoreCans = 50;
	public float staminaPantVolume = 0.8f;
	/** Footsteps Michael hears (a burst on his screen): running, running on a loud surface (gravel, leaf piles, broken glass), walking on one. */
	public double noiseRunRange = 10;
	public double noiseLoudRunRange = 28;
	public double noiseLoudWalkRange = 10;
	/** At most one burst per survivor this often. */
	public int noiseEveryTicks = 20;
	/** Panic: how far (lit / dark) and how wide survivors see Michael to panic; seconds of watching to full panic (twice as fast this close). */
	public double panicSeeRange = 24;
	public double panicSeeDarkRange = 10;
	public float panicSeeFovDeg = 60f;
	public float panicSeeSeconds = 9f;
	public double panicNearDistance = 8;
	/** It ebbs away (over this many seconds from full) once he is further than this and no body is near. */
	public double panicCalmDistance = 24;
	public double panicBodyRange = 12;
	public float panicCalmSeconds = 12f;
	/** Shocks: struck, grabbed, a body found. */
	public float panicOnHit = 0.17f;
	public float panicOnGrab = 0.27f;
	public float panicOnBody = 0.33f;
	/** At full panic the breath comes back this much slower; heavy breathing from this much panic; extra gun spread (degrees) at full panic. */
	public float panicRegenPenalty = 0.333f;
	public float panicBreathOn = 0.5f;
	public float panicSpread = 2f;
	/** Perks a survivor takes into a match, and what each costs (points over all matches, spent; see game.Perks). */
	public int perkSlots = 3;
	public java.util.Map<String, Integer> perkCosts = defaultPerkCosts();
	public float perkQuickSearchFactor = 0.6f;
	public double perkStaminaFactor = 1.2;
	public double perkQuietNoiseFactor = 0.5;
	public float perkToughHealth = 10f;
	/** Adrenaline: the breath comes back this much faster. Barricade: blows a door you locked takes, times this. */
	public float perkAdrenalineRegen = 1.3f;
	public double perkBarricadeFactor = 1.5;
	/** Glass walker: window cuts times this. Brimstone: firecracker blinding times this. */
	public float perkGlassWalkerFactor = 0.5f;
	public float perkBrimstoneBlindFactor = 2f;
	/** Contender: melee stun times this. Lethal pitch: thrown stun times this. */
	public float perkContenderStun = 1.25f;
	public float perkLethalPitchStun = 1.3f;
	/** Elusory: stalking on you times this. */
	public float perkElusoryFactor = 0.75f;
	/** Field triage: med kit time times this, the spray heals this much more. */
	public float perkTriageMedKitFactor = 0.6f;
	public float perkTriageSprayFactor = 1.5f;
	/** Slippery: your zone in his grab this much wider. Unphased: panic builds up times this. */
	public float perkSlipperyZone = 0.04f;
	public float perkUnphasedFactor = 0.65f;
	/** Stubborn: this much faster below this share of health. */
	public double perkStubbornSpeed = 0.1;
	public float perkStubbornBelow = 0.5f;
	/** Repair expert: chance the repair kit stays whole. Trespasser: unlocking time times this. Smooth talker: talks and calls times this. */
	public float perkRepairKeepChance = 0.4f;
	public float perkTrespasserFactor = 0.5f;
	public float perkTalkerFactor = 0.7f;
	/** Witness: other survivors this close show through walls. */
	public double perkWitnessRange = 24;
	/** How often a match night is clear, foggy, rainy (weights). */
	public int[] weatherWeights = {40, 35, 25};
	/** How far townsfolk, bots and police notice the Shape in fog and in rain, against a clear night. */
	public double fogSightFactor = 0.7;
	public double rainSightFactor = 0.8;
	public int flashlightChargeTicks = 200;
	/** With this little charge left the flashlight starts to flicker. */
	public int flashlightFlickerTicks = 60;

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
	/** Bots are added until there are this many civilians (with or without a Shape). 0 = no bots. */
	public int botsFillTo = 4;
	/** The survivors of Halloween: The Game (each bot wears that survivor's skin). */
	public String[] botNames = {"Laurie", "Annie", "Lynda", "Bob", "Eric", "Rachel", "Marcus", "Tanya", "Jennifer", "Thomas", "Richard", "Alexis"};
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
	/** Bots fetch a neighbour at home this far away to lead out (with a way out open; without one, only this near), and wait
	 * at the way out this long for them to go first. */
	public double botRescueRange = 50;
	public double botRescueRangeNoExit = 20;
	public int botEscortWaitTicks = 300;
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
	/** Out of a locker for air: no getting into any locker again for this long. */
	public int lockerBreathTicks = 400;
	/** Michael hears whoever is hiding in a locker this close: their breathing, quiet. */
	public double lockerHearRange = 2;
	public float lockerBreathVolume = 0.45f;
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
	/** Music: the Shape within this of a survivor who has seen him (in the last musicNoticeTicks) and coming on is a chase. */
	public double musicChaseRange = 16;
	public int musicNoticeTicks = 100;
	/** A chase's music holds on this long after he stops closing in. */
	public int musicChaseLingerTicks = 80;
	/** How far a survivor can have seen him for the music. */
	public double musicSightRange = 32;
	/** Closer than this but not after you: Being Stalked. */
	public double musicStalkedRange = 24;
	/** The killer of the next matches (/hw killer): the Demogorgon, or Michael. Kept across restarts. */
	public boolean killerDemogorgon = false;

	public static HwConfig get() {
		return instance;
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("halloween.json");
	}

	/** The file's version: older files get the defaults that changed since moved on (see {@link #migrate}). */
	public int configVersion;
	private static final int VERSION = 13;

	public static void load() {
		Path path = path();
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path)) {
				HwConfig loaded = GSON.fromJson(reader, HwConfig.class);
				if (loaded != null) {
					instance = loaded;
					instance.migrate();
				}
			} catch (IOException | RuntimeException e) {
				Halloween.LOGGER.error("Failed to read {}, using defaults", path, e);
			}
		} else {
			instance.configVersion = VERSION;
		}
		// Rewrite so newly added fields show up in the file.
		save();
	}

	/** Writes the settings as they are now (what the commands change too). */
	public static void save() {
		Path path = path();
		try (Writer writer = Files.newBufferedWriter(path)) {
			GSON.toJson(instance, writer);
		} catch (IOException e) {
			Halloween.LOGGER.error("Failed to write {}", path, e);
		}
	}

	/**
	 * A file written by an older version keeps the old defaults; where a setting still has the value that was
	 * the default then, it gets today's (a value somebody changed by hand stays as they set it).
	 */
	private void migrate() {
		if (this.configVersion < 1) {
			if (this.matchSeconds == 1200) {
				this.matchSeconds = 1500;
			}
			if (this.stunnedTicks == 80) {
				this.stunnedTicks = 120;
			}
			if (this.panicSeeSeconds == 6f) {
				this.panicSeeSeconds = 9f;
			}
			if (this.panicOnHit == 0.25f) {
				this.panicOnHit = 0.17f;
			}
			if (this.panicOnGrab == 0.4f) {
				this.panicOnGrab = 0.27f;
			}
			if (this.panicOnBody == 0.5f) {
				this.panicOnBody = 0.33f;
			}
			if (this.panicRegenPenalty == 0.5f) {
				this.panicRegenPenalty = 0.333f;
			}
			if (this.panicSpread == 4f) {
				this.panicSpread = 2f;
			}
			if (this.mannequinQteSuccessChance == 0.35f) {
				this.mannequinQteSuccessChance = 0.8f;
			}
		}
		if (this.configVersion < 2) {
			if (this.lockerHearRange == 8) {
				this.lockerHearRange = 2;
			}
			if (this.runtScentRange == 16) {
				this.runtScentRange = 48;
			}
		}
		if (this.configVersion < 3 && this.runtPounceCooldownTicks == 280) {
			this.runtPounceCooldownTicks = 500;
		}
		if (this.configVersion < 4 && java.util.Arrays.equals(this.policeOfficersByLevel, new int[]{2, 2, 2})) {
			this.policeOfficersByLevel = new int[]{1, 2, 1};
		}
		if (this.configVersion < 6 && this.lootFillShare == 0.6f) {
			this.lootFillShare = 0.55f;
		}
		if (this.configVersion < 5 && this.lootWeights != null && Integer.valueOf(8).equals(this.lootWeights.get("battery"))) {
			this.lootWeights.put("battery", 3);
		}
		if (this.configVersion < 4 && this.partyGatherRange == 60) {
			this.partyGatherRange = 90;
		}
		if (this.configVersion < 7) {
			// Iron sights, not scopes: a slight zoom.
			if (this.revolverAimZoom == 0.82f) {
				this.revolverAimZoom = 0.93f;
			}
			if (this.rifleAimZoom == 0.55f) {
				this.rifleAimZoom = 0.8f;
			}
			if (this.shotgunAimZoom == 0.85f) {
				this.shotgunAimZoom = 0.92f;
			}
		}
		if (this.configVersion < 8) {
			// One repair kit a match, always there (Loot): none at random any more.
			if (this.lootWeights != null) {
				this.lootWeights.remove("repair_kit");
			}
			if (this.bagUncommon != null) {
				this.bagUncommon = java.util.Arrays.stream(this.bagUncommon).filter(k -> !k.equals("repair_kit")).toArray(String[]::new);
			}
		}
		if (this.configVersion < 9 && this.eventWeights != null) {
			// Two new events on Michael's side.
			this.eventWeights.putIfAbsent("bell", 2);
			this.eventWeights.putIfAbsent("masks", 2);
		}
		if (this.configVersion < 10) {
			// Stalking takes 15% longer.
			if (this.passiveStalkPerSecond == 0.056f) {
				this.passiveStalkPerSecond = 0.0487f;
			}
			if (this.senseStalkPerSecond == 0.154f) {
				this.senseStalkPerSecond = 0.1339f;
			}
		}
		if (this.configVersion < 11 && this.bloodTrailSeconds == 90) {
			// A survivor's trail dries up in half a minute (the Demogorgon sees it bright).
			this.bloodTrailSeconds = 30;
		}
		if (this.configVersion < 12 && this.focusPointBurstTicks == 60) {
			// Focus points burst twice as fast.
			this.focusPointBurstTicks = 30;
		}
		if (this.configVersion < 13) {
			// Crows take fright 4 blocks further off.
			if (this.birdScareRun == 7) {
				this.birdScareRun = 11;
			}
			if (this.birdScareWalk == 4) {
				this.birdScareWalk = 8;
			}
		}
		if (this.configVersion < VERSION) {
			Halloween.LOGGER.info("Config moved on to version {}", VERSION);
		}
		this.configVersion = VERSION;
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
