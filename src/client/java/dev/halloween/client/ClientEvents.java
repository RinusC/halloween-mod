package dev.halloween.client;

import dev.halloween.Role;
import dev.halloween.net.HwNet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Match events on this screen (game.Events): the banner when one is announced and while it runs, its markers
 * (a ringing phone, the bag, the patrol, the transformer, the party, the fire; at midnight the ways out), whom
 * it shows through walls, and Samhain's red night with its music.
 */
public final class ClientEvents {
	private static final int PURPLE = 0xFFD18CFF;
	public static HwNet.EventSync state = HwNet.EventSync.NONE;
	/** How red the night is (Samhain), 0..1, easing in and out. */
	private static float red;
	/** The earthquake's tremor: until when (game time) and how hard. */
	private static float quakeStart;
	private static float quakeUntil;
	private static float quakeStrength;

	static void quake(int ticks, float strength) {
		Minecraft mc = Minecraft.getInstance();
		float now = mc.level == null ? 0 : mc.level.getGameTime();
		quakeStart = now;
		quakeUntil = now + ticks;
		quakeStrength = strength;
	}

	/**
	 * The view shaking in a tremor (degrees: yaw, pitch), or null: hard at first, easing off at its end; jolts on a
	 * quick tremble.
	 */
	public static float[] quakeShake(float time) {
		if (time >= quakeUntil || time < quakeStart) {
			return null;
		}
		float in = Math.min(1f, (time - quakeStart) / 6f);
		float out = Math.min(1f, (quakeUntil - time) / 25f);
		float amp = quakeStrength * 1.6f * in * out;
		float yaw = amp * (net.minecraft.util.Mth.sin(time * 2.9f) * 0.5f + net.minecraft.util.Mth.sin(time * 7.7f) * 0.35f + net.minecraft.util.Mth.sin(time * 13.1f) * 0.15f);
		float pitch = amp * (net.minecraft.util.Mth.sin(time * 3.7f + 1.1f) * 0.5f + net.minecraft.util.Mth.sin(time * 9.3f) * 0.35f + net.minecraft.util.Mth.sin(time * 15.7f) * 0.15f);
		return new float[]{yaw, pitch};
	}
	private static SoundInstance music;

	private ClientEvents() {
	}

	public static boolean masks() {
		return (state.flags() & HwNet.EventSync.MASKS) != 0 && ClientState.match.state() == 2;
	}

	public static boolean samhain() {
		return (state.flags() & HwNet.EventSync.SAMHAIN) != 0 && ClientState.match.state() == 2;
	}

	public static float redness() {
		return red;
	}

	public static void reset() {
		state = HwNet.EventSync.NONE;
		red = 0f;
		if (music != null) {
			Minecraft.getInstance().getSoundManager().stop(music);
			music = null;
		}
	}

	static void tick(Minecraft mc) {
		boolean on = samhain();
		red += Mth.clamp((on ? 1f : 0f) - red, -0.025f, 0.025f);
		if (on && music == null) {
			// An old record slowed down, from nowhere in particular.
			music = SimpleSoundInstance.forLocalAmbience(SoundEvents.MUSIC_DISC_13.value(), 0.8f, 0.9f);
			mc.getSoundManager().play(music);
		} else if (!on && music != null) {
			mc.getSoundManager().stop(music);
			music = null;
		}
	}

	/** Seen through walls because of an event (Loomis's call, Midnight): the colour, or -1. */
	public static int glow(net.minecraft.world.entity.Entity entity) {
		if (ClientState.match.state() != 2 || !state.glows().contains(entity.getId())) {
			return -1;
		}
		return ClientState.myRole().survivor() ? 0xE0261F : 0xFFC94A;
	}

	/** An object of the event to hold use on (the ringing phone, the bag, the transformer, the crate). */
	public static boolean isObject(net.minecraft.core.BlockPos pos) {
		return ClientState.match.state() == 2 && state.objects().contains(pos);
	}

	static void render(GuiGraphicsExtractor g, Minecraft mc, int w, int h, float partial) {
		if (ClientState.match.state() != 2) {
			return;
		}
		marks(g, mc, w, h, partial);
		HwNet.EventSync s = state;
		if (s.phase() == 0 || s.id().isEmpty()) {
			return;
		}
		Component title = Component.translatable("halloween.event." + s.id() + ".title");
		int y = 58;
		if (s.phase() == 1) {
			// Announced: the name big, what it means for you under it, and how soon.
			g.pose().pushMatrix();
			g.pose().translate(w / 2f, y);
			g.pose().scale(1.6f, 1.6f);
			g.centeredText(mc.font, title, 0, 0, PURPLE);
			g.pose().popMatrix();
			Role role = ClientState.myRole();
			String side = role.survivor() ? "survivors" : "shape";
			g.centeredText(mc.font, Component.translatable("halloween.event." + s.id() + "." + side), w / 2, y + 17, HwHud.TEXT);
			g.centeredText(mc.font, Component.translatable("halloween.event.in", (s.ticksLeft() + 19) / 20), w / 2, y + 29, HwHud.DIM);
			return;
		}
		g.centeredText(mc.font, title, w / 2, y, PURPLE);
		int bw = 110;
		float part = Mth.clamp(s.ticksLeft() / (float) Math.max(1, s.total()), 0f, 1f);
		g.fill(w / 2 - bw / 2 - 1, y + 11, w / 2 + bw / 2 + 1, y + 15, 0xA0000000);
		g.fill(w / 2 - bw / 2, y + 12, w / 2 - bw / 2 + Math.round(bw * part), y + 14, PURPLE);
	}

	private static int color(int kind) {
		return switch (kind) {
			case HwNet.EventMark.PHONE -> 0xFF6FD0FF;
			case HwNet.EventMark.BAG -> 0xFFFFD24A;
			case HwNet.EventMark.PATROL -> 0xFF5A9CFF;
			case HwNet.EventMark.TRANSFORMER -> 0xFFFFE066;
			case HwNet.EventMark.PARTY -> 0xFFE070FF;
			case HwNet.EventMark.FIRE -> 0xFFFF7A2E;
			default -> 0xFF7FE07F;
		};
	}

	/** Each marker: a diamond and a word with the distance; off screen, the word at the side it is on. */
	private static void marks(GuiGraphicsExtractor g, Minecraft mc, int w, int h, float partial) {
		for (HwNet.EventMark m : state.marks()) {
			int color = color(m.kind());
			int dist = (int) Math.round(mc.player.position().distanceTo(m.pos()));
			Component label = Component.translatable("halloween.event.mark." + m.kind(), dist);
			Projection.Point p = Projection.toScreen(m.pos().add(0, 0.6, 0), w, h);
			if (p != null && p.x() >= 0 && p.x() <= w && p.y() >= 0 && p.y() <= h) {
				HwHud.diamond(g, (int) p.x(), (int) p.y(), 5, 0xFF0C0C0C);
				HwHud.diamond(g, (int) p.x(), (int) p.y(), 4, color);
				g.centeredText(mc.font, label, (int) p.x(), (int) p.y() - 16, color);
				continue;
			}
			Vec3 to = m.pos().subtract(mc.player.getEyePosition(partial));
			Vec3 look = mc.player.getViewVector(partial);
			boolean right = look.x * to.z - look.z * to.x > 0;
			int x = right ? w - 40 : 40;
			Component side = right ? label.copy().append(" ▶") : Component.literal("◀ ").append(label);
			g.centeredText(mc.font, side, x, h / 2 + 20 + (m.kind() % 4) * 11, color);
		}
	}
}
