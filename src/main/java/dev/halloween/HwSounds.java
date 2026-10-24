package dev.halloween;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;

/**
 * Sound events. assets/halloween/sounds.json maps them to vanilla placeholder files;
 * a resource pack can override that file with the real recordings.
 */
public final class HwSounds {
	public static final SoundEvent SENSE_BREATH = register("shape.sense_breath");
	/** Shape Jump: going in, coming out, the drone and the wind inside (synthesised, sounds/shape/). */
	public static final SoundEvent SHADOW_ENTER = register("shape.shadow_enter");
	public static final SoundEvent SHADOW_EXIT = register("shape.shadow_exit");
	public static final SoundEvent SHADOW_DRONE = register("shape.shadow_drone");
	public static final SoundEvent SHADOW_WIND = register("shape.shadow_wind");
	public static final SoundEvent STINGER = register("shape.stinger");
	/** Michael's footsteps while he speeds up, by what is underfoot (Kenney's Impact Sounds, CC0). */
	public static final SoundEvent DASH_STEP_CONCRETE = register("shape.dash_step.concrete");
	public static final SoundEvent DASH_STEP_WOOD = register("shape.dash_step.wood");
	public static final SoundEvent DASH_STEP_GRASS = register("shape.dash_step.grass");
	public static final SoundEvent DASH_STEP_CARPET = register("shape.dash_step.carpet");
	public static final SoundEvent DASH_STEP_SNOW = register("shape.dash_step.snow");
	public static final SoundEvent TIER_UP = register("shape.tier_up");
	public static final SoundEvent STAB = register("shape.stab");
	public static final SoundEvent GRAB = register("shape.grab");
	public static final SoundEvent WINDOW_SMASH = register("shape.window_smash");
	/** Match music, cut from the soundtrack of Halloween: The Game (see game.Music). */
	public static final SoundEvent[] MUSIC_AMBIENCE = music("ambience", 4);
	public static final SoundEvent[] MUSIC_BEING_STALKED = music("being_stalked", 3);
	public static final SoundEvent[] MUSIC_PROXIMITY = music("proximity", 4);
	public static final SoundEvent[] MUSIC_STALKING = music("stalking", 3);
	public static final SoundEvent QTE_HIT = register("qte.hit");
	public static final SoundEvent QTE_MISS = register("qte.miss");
	public static final SoundEvent ESCAPE = register("qte.escape");
	public static final SoundEvent BREAKER = register("world.breaker");
	/** An overheating bulb as the Demogorgon comes up near by: the whine, the sizzle going dark, the click coming back. */
	public static final SoundEvent BULB_HUM = register("world.bulb_hum");
	public static final SoundEvent BULB_FIZZ = register("world.bulb_fizz");
	public static final SoundEvent BULB_CLICK = register("world.bulb_click");
	/** Michael's breathing, all match long (two CC0 recordings, sounds/shape/breath, see CREDITS.txt; heard within two blocks). */
	public static final SoundEvent SHAPE_BREATH = register("shape.breath");
	public static final SoundEvent PUSH = register("shape.push");
	public static final SoundEvent IMPULSE = register("shape.impulse");
	public static final SoundEvent SWING = register("shape.swing");
	public static final SoundEvent LOCK = register("door.lock");
	public static final SoundEvent UNLOCK = register("door.unlock");
	public static final SoundEvent DOOR_RATTLE = register("door.rattle");
	public static final SoundEvent DOOR_HIT = register("door.hit");
	public static final SoundEvent DOOR_BREAK = register("door.break");
	public static final SoundEvent SHARDS = register("window.shards");
	public static final SoundEvent NPC_SCREAM = register("npc.scream");
	/** People's voices (recordings under sounds/voice/, see game.Voices). */
	public static final SoundEvent VOICE_FEMALE_SCREAM = register("voice.female.scream");
	public static final SoundEvent VOICE_FEMALE_HURT = register("voice.female.hurt");
	public static final SoundEvent VOICE_FEMALE_DEATH = register("voice.female.death");
	public static final SoundEvent VOICE_MALE_SCREAM = register("voice.male.scream");
	public static final SoundEvent VOICE_MALE_HURT = register("voice.male.hurt");
	public static final SoundEvent VOICE_MALE_DEATH = register("voice.male.death");
	/** Out of breath after running (game.Stamina, client.ClientPanting): a man's, a woman's long take of panting. */
	public static final SoundEvent VOICE_PANT_MALE = register("voice.pant.male");
	public static final SoundEvent VOICE_PANT_FEMALE = register("voice.pant.female");
	/** Choking and gasping for air, held by the throat: long takes, one at a time (pitched a little by who it is). */
	public static final SoundEvent[] VOICE_CHOKES = {register("voice.choke.1"), register("voice.choke.2"), register("voice.choke.3"), register("voice.choke.4")};
	/** How long each choke take lasts (ticks, at pitch 1). */
	public static final int[] VOICE_CHOKE_TICKS = {182, 279, 179, 168};
	/** Voices for townsfolk talks (see game.Dialogue; the Coqui voice pack): women, men; each says request, question, agree, refuse lines. */
	public static final String[] TALK_WOMEN = {"claribel_dervla", "daisy_studious", "gracie_wise", "tammie_ema", "ana_florence", "sofia_hellen"};
	public static final String[] TALK_MEN = {"andrew_chipper", "badr_odhiambo", "damien_black", "craig_gutsy", "viktor_eka", "royston_min"};
	private static final String[] MOODS = {"request", "question", "agree", "refuse"};
	private static final java.util.Map<String, SoundEvent> TALK = new java.util.HashMap<>();

	static {
		for (String[] voices : new String[][]{TALK_WOMEN, TALK_MEN}) {
			for (String voice : voices) {
				for (String mood : MOODS) {
					TALK.put(voice + "." + mood, register("talk." + voice + "." + mood));
				}
			}
		}
	}

	/** What {@code voice} says in this mood (0 request, 1 question, 2 agree, 3 refuse). */
	public static SoundEvent talk(String voice, int mood) {
		return TALK.get(voice + "." + MOODS[mood]);
	}
	public static final SoundEvent LOCKER_SLAM = register("locker.slam");
	public static final SoundEvent LOCKER_CLOSE = register("locker.close");
	/** A crow put up: a caw heard far down the street (see game.Birds). */
	public static final SoundEvent CROW_CAW = register("crow.caw");
	public static final SoundEvent GUN_REVOLVER = register("gun.revolver");
	public static final SoundEvent GUN_RIFLE = register("gun.rifle");
	public static final SoundEvent GUN_CRACK = register("gun.crack");
	public static final SoundEvent GUN_DRY = register("gun.dry");
	public static final SoundEvent GUN_LOAD_ROUND = register("gun.load_round");
	public static final SoundEvent GUN_BOLT = register("gun.bolt");
	public static final SoundEvent GUN_RICOCHET = register("gun.ricochet");
	public static final SoundEvent CASE_KEY = register("case.key");
	public static final SoundEvent CASE_WRONG = register("case.wrong");
	public static final SoundEvent CASE_OPEN = register("case.open");
	/** Vecna (sounds/vecna/, made by tools/vecna_sounds.pl): the Eyes, the Anchor, the Grip. */
	public static final SoundEvent VECNA_EYES_OPEN = register("vecna.eyes_open");
	public static final SoundEvent VECNA_EYES_CLOSE = register("vecna.eyes_close");
	public static final SoundEvent VECNA_WHISPER = register("vecna.whisper");
	public static final SoundEvent VECNA_ANCHOR_VANISH = register("vecna.anchor_vanish");
	public static final SoundEvent VECNA_ANCHOR_ARRIVE = register("vecna.anchor_arrive");
	public static final SoundEvent VECNA_ANCHOR_TOLL = register("vecna.anchor_toll");
	public static final SoundEvent VECNA_ANCHOR_REVEAL = register("vecna.anchor_reveal");
	public static final SoundEvent VECNA_GRIP_CHARGE = register("vecna.grip_charge");
	public static final SoundEvent VECNA_GRIP_READY = register("vecna.grip_ready");
	public static final SoundEvent VECNA_GRIP_CAST = register("vecna.grip_cast");
	public static final SoundEvent VECNA_GRIP_HUM = register("vecna.grip_hum");
	public static final SoundEvent VECNA_GRIP_FIZZLE = register("vecna.grip_fizzle");
	public static final SoundEvent VECNA_GRIP_CATCH = register("vecna.grip_catch");
	public static final SoundEvent VECNA_GRIP_PULL = register("vecna.grip_pull");
	public static final SoundEvent VECNA_GRIP_RELEASE = register("vecna.grip_release");
	public static final SoundEvent VECNA_GRIP_SNAP = register("vecna.grip_snap");
	/** The curse: the grandfather clock, its last toll, lifted at a booth, the levitation and the bones. */
	public static final SoundEvent VECNA_CLOCK_CHIME = register("vecna.clock_chime");
	public static final SoundEvent VECNA_CLOCK_TICK = register("vecna.clock_tick");
	public static final SoundEvent VECNA_CLOCK_TOCK = register("vecna.clock_tock");
	public static final SoundEvent VECNA_CURSE_LAST = register("vecna.curse_last");
	public static final SoundEvent VECNA_CURSE_LIFTED = register("vecna.curse_lifted");
	public static final SoundEvent VECNA_CURSE_FLOAT = register("vecna.curse_float");
	public static final SoundEvent VECNA_BONE_CRACK = register("vecna.bone_crack");
	public static final SoundEvent VECNA_PHANTOM_VANISH = register("vecna.phantom_vanish");
	/** The ritual and the rift. */
	public static final SoundEvent VECNA_BODY_LIFT = register("vecna.body_lift");
	public static final SoundEvent VECNA_BODY_DROP = register("vecna.body_drop");
	public static final SoundEvent VECNA_ALTAR_KINDLE = register("vecna.altar_kindle");
	public static final SoundEvent VECNA_RIFT_OPEN = register("vecna.rift_open");
	public static final SoundEvent VECNA_THUNDER = register("vecna.thunder");
	public static final SoundEvent VECNA_RIFT_DRONE = register("vecna.rift_drone");
	/** Pulled down in a chase and kicked apart (game.Obstacles): Kenney's Impact Sounds (CC0), layered several at once. */
	public static final SoundEvent OBSTACLE_CREAK = register("obstacle.creak");
	public static final SoundEvent OBSTACLE_CRASH_WOOD = register("obstacle.crash_wood");
	public static final SoundEvent OBSTACLE_SPLINTER = register("obstacle.splinter");
	public static final SoundEvent OBSTACLE_PLANK = register("obstacle.plank");
	public static final SoundEvent OBSTACLE_BOOK = register("obstacle.book");
	public static final SoundEvent OBSTACLE_THUD = register("obstacle.thud");
	public static final SoundEvent OBSTACLE_CRASH_METAL = register("obstacle.crash_metal");
	public static final SoundEvent OBSTACLE_CLINK = register("obstacle.clink");
	public static final SoundEvent OBSTACLE_RATTLE = register("obstacle.rattle");
	public static final SoundEvent OBSTACLE_TRASH = register("obstacle.trash");
	public static final SoundEvent OBSTACLE_KICK = register("obstacle.kick");

	private HwSounds() {
	}

	private static SoundEvent[] music(String name, int parts) {
		SoundEvent[] events = new SoundEvent[parts];
		for (int i = 0; i < parts; i++) {
			events[i] = register("music." + name + "_" + (i + 1));
		}
		return events;
	}

	private static SoundEvent register(String path) {
		var id = Halloween.id(path);
		return Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
	}

	public static void init() {
	}
}
