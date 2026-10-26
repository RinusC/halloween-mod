package dev.halloween.game;

import dev.halloween.HwSounds;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;

/**
 * What people say when a survivor talks a townsperson round: the words come as subtitles (to the survivor
 * talking), the voices are short spoken lines (the Coqui voice pack): asking for something, a question,
 * agreeing, refusing; everyone keeps their own voice, a woman's or a man's.
 */
public final class Dialogue {
	public enum Mood {
		REQUEST, QUESTION, AGREE, REFUSE
	}

	/** How many variants each line has (halloween.say.KEY.1 ..). */
	private static final java.util.Map<String, Integer> VARIANTS = java.util.Map.ofEntries(
		java.util.Map.entry("ask.follow", 3), java.util.Map.entry("ask.hide", 3), java.util.Map.entry("ask.call", 3), java.util.Map.entry("ask.stay", 2),
		java.util.Map.entry("greet", 4), java.util.Map.entry("yes.follow", 3), java.util.Map.entry("yes.hide", 3), java.util.Map.entry("yes.call", 3),
		java.util.Map.entry("yes.stay", 2), java.util.Map.entry("no", 3));

	private Dialogue() {
	}

	/** The survivor asks: follow me, hide, call the police, or (to someone following) stay here. */
	public static void ask(ServerPlayer survivor, int option, boolean stay) {
		say(survivor, Mood.REQUEST, survivor, "ask." + (stay ? "stay" : key(option)), true);
	}

	/** The townsperson, on being spoken to: what is it? */
	public static void greet(LivingEntity npc, ServerPlayer listener) {
		say(npc, Mood.QUESTION, listener, "greet", false);
	}

	/** The townsperson agrees to what was asked. */
	public static void agree(LivingEntity npc, ServerPlayer listener, int option, boolean stay) {
		say(npc, Mood.AGREE, listener, "yes." + (stay ? "stay" : key(option)), false);
	}

	/** The talk broke off: the townsperson will have none of it. */
	public static void refuse(LivingEntity npc, ServerPlayer listener) {
		say(npc, Mood.REFUSE, listener, "no", false);
	}

	/** A townsperson says something of its own (a favour asked, thanks): its voice, and the words to {@code listener}. */
	public static void speak(LivingEntity npc, ServerPlayer listener, Mood mood, Component words) {
		if (!(npc.level() instanceof ServerLevel level) || npc.isRemoved() || !npc.isAlive()) {
			return;
		}
		level.playSound(null, npc.getX(), npc.getEyeY(), npc.getZ(), HwSounds.talk(voice(npc), mood.ordinal()), SoundSource.NEUTRAL, 1f, 1f);
		if (listener != null && !Bots.isBot(listener)) {
			Notices.send(listener, Component.translatable("halloween.say.format", npc.getDisplayName(), words).withStyle(ChatFormatting.WHITE));
		}
	}

	private static String key(int option) {
		return option == Townsfolk.HIDE ? "hide" : option == Townsfolk.CALL ? "call" : "follow";
	}

	private static void say(LivingEntity speaker, Mood mood, ServerPlayer listener, String line, boolean self) {
		if (!(speaker.level() instanceof ServerLevel level) || speaker.isRemoved() || !speaker.isAlive()) {
			return;
		}
		level.playSound(null, speaker.getX(), speaker.getEyeY(), speaker.getZ(), HwSounds.talk(voice(speaker), mood.ordinal()), SoundSource.NEUTRAL, 1f, 1f);
		AiStats.add(speaker, "talk.say." + mood.name().toLowerCase(java.util.Locale.ROOT));
		if (listener != null && !Bots.isBot(listener)) {
			int n = 1 + level.getRandom().nextInt(VARIANTS.getOrDefault(line, 1));
			Component words = Component.translatable("halloween.say." + line + "." + n);
			Component who = self ? Component.translatable("halloween.say.you") : speaker.getDisplayName();
			Notices.send(listener, Component.translatable("halloween.say.format", who, words).withStyle(self ? ChatFormatting.GRAY : ChatFormatting.WHITE));
		}
	}

	/** Everyone keeps their own voice: a woman's or a man's, picked by who they are. */
	static String voice(LivingEntity who) {
		String[] voices = Voices.female(who) ? HwSounds.TALK_WOMEN : HwSounds.TALK_MEN;
		return voices[Math.floorMod(who.getUUID().hashCode(), voices.length)];
	}
}
