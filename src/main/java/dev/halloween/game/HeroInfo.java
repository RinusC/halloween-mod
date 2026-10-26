package dev.halloween.game;

import dev.halloween.HwConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * Who one can play, as the character select shows them (client.CharacterSelectScreen): the twelve survivors and the
 * three killers. Their texts live in the lang files (halloween.heroinfo.{id}.*), their numbers come from where the
 * game keeps them (Heroes for the skills, Perks and HwConfig for the traits), so the card never says one thing while
 * the match does another. Laurie, Bob, Eric and Tanya and Michael are open to everyone; the rest are bought for
 * {@link HwConfig#heroPrice} points, the same purse as the perks.
 */
public final class HeroInfo {
	/** The killers, in the order of their tab. */
	public static final List<String> KILLERS = List.of("michael", "demogorgon", "vecna");
	/** Open without buying. */
	public static final List<String> FREE = List.of("laurie", "bob", "eric", "tanya", "michael");
	/** The bars on the card: survivors' and killers'. */
	public static final List<String> SURVIVOR_STATS = List.of("stealth", "toughness", "search", "help");
	public static final List<String> KILLER_STATS = List.of("speed", "stealth", "pressure", "difficulty");

	/** The bought ones by strength, weakest first: 3000 for the first, 8900 for the last. */
	private static final List<String> BY_POWER = List.of("annie", "lynda", "marcus", "alexis", "jennifer", "richard", "rachel", "thomas",
		"demogorgon", "vecna");
	private static final int[] PRICES = {3000, 3700, 4300, 5000, 5600, 6300, 6900, 7600, 8200, 8900};

	private HeroInfo() {
	}

	/** What a hero costs; zero for the open ones. */
	public static int price(String id) {
		int i = BY_POWER.indexOf(id);
		return i < 0 ? 0 : PRICES[i];
	}

	public static boolean killer(String id) {
		return KILLERS.contains(id);
	}

	public static boolean known(String id) {
		return killer(id) || Skins.SURVIVORS.contains(id);
	}

	public static boolean free(String id) {
		return FREE.contains(id);
	}

	/** The order of the survivors' list: the open four first, then the rest as the game knows them. */
	public static List<String> survivors() {
		List<String> out = new ArrayList<>();
		for (String id : Skins.SURVIVORS) {
			if (free(id)) {
				out.add(id);
			}
		}
		for (String id : Skins.SURVIVORS) {
			if (!free(id)) {
				out.add(id);
			}
		}
		return out;
	}

	/** Seconds the skill lasts and rests (survivors with a skill of their own; zeros without). */
	public static int[] skillSeconds(String id) {
		Heroes.Skill skill = Heroes.skill(id);
		return new int[]{Heroes.duration(skill) / 20, Heroes.cooldown(skill) / 20};
	}

	/**
	 * The card's bars, 0..100, in the order of {@link #SURVIVOR_STATS} or {@link #KILLER_STATS}. A survivor's are
	 * worked out from what they always have: footsteps that carry half as far make for stealth, the extra health
	 * for toughness, a faster search for search, a talker, a mechanic or someone who warns the others for help.
	 */
	public static int[] stats(String id) {
		if (killer(id)) {
			return switch (id) {
				case "demogorgon" -> new int[]{85, 60, 80, 60};
				case "vecna" -> new int[]{45, 75, 70, 90};
				default -> new int[]{60, 85, 65, 40};
			};
		}
		HwConfig cfg = HwConfig.get();
		double stealth = 35;
		double tough = 35;
		double search = 35;
		double help = 30;
		Perks.Perk innate = Heroes.innate(id);
		if (innate != null) {
			switch (innate) {
				case QUIET -> stealth += (1 - cfg.perkQuietNoiseFactor) * 90;
				case ELUSORY -> stealth += 35;
				case TOUGH -> tough += cfg.perkToughHealth / 20.0 * 90;
				case UNPHASED -> tough += 25;
				case QUICK_SEARCH -> search += (1 - cfg.perkQuickSearchFactor) * 110;
				case SMOOTH_TALKER -> help += 35;
				case REPAIR_EXPERT -> help += 40;
				default -> {
				}
			}
		}
		switch (id) {
			// Laurie feels him coming, Alexis knows when he is near: both get away sooner.
			case "laurie" -> tough += 15;
			case "alexis" -> {
				tough += 15;
				stealth += 20;
			}
			case "rachel" -> {
				stealth += 15;
				help += 25;
			}
			case "jennifer" -> search += 40;
			case "marcus" -> help += 25;
			case "richard" -> help += 45;
			default -> {
			}
		}
		return new int[]{clamp(stealth), clamp(tough), clamp(search), clamp(help)};
	}

	private static int clamp(double v) {
		return (int) Math.round(Math.max(10, Math.min(100, v)));
	}

	/**
	 * What a team without anyone of these lacks (the card's hint, never a rule): the key of the hint, or null when
	 * the picks cover it all. Looks at the survivors picked so far.
	 */
	public static String teamHint(List<String> picked) {
		if (picked.isEmpty()) {
			return null;
		}
		if (!picked.contains("eric")) {
			return "car";
		}
		if (!picked.contains("laurie") && !picked.contains("alexis") && !picked.contains("richard")) {
			return "watch";
		}
		if (!picked.contains("tanya") && !picked.contains("jennifer")) {
			return "search";
		}
		if (!picked.contains("annie")) {
			return "police";
		}
		return null;
	}
}
