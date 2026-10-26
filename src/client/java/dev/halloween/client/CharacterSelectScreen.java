package dev.halloween.client;

import dev.halloween.game.HeroInfo;
import dev.halloween.game.HeroPicks;
import dev.halloween.game.Heroes;
import dev.halloween.net.HwNet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

/**
 * The character select (the stand in the gym, key H): who to play next match. Two tabs, the survivors and the
 * killers. On the left the list (who is theirs, who is taken, the locked ones with their price, a pick left to
 * chance); in the middle the hero in 3D, turned with the mouse, in the pose of their scene or walking, something in
 * their hand, and their opening scene played on the spot; on the right their card (who they are, the skill and the
 * trait with the match's own numbers, the bars, their record, what the team lacks; for the killers a dossier).
 * Along the bottom the perks for the match (the perk screen), "Ready" and the practice. The lobby's picks come from
 * the server (game.HeroPicks), which has the last word on who gets what.
 *
 * <p>Dressed as the night of the 31st: a street far behind, out of focus, a lantern's light on the hero's pedestal,
 * fog and embers, panels of dark glass with cut corners (the art from tools/SelectArt.java, the titles in Oswald).
 * Pumpkin orange on the survivors' side; the killers' tab turns it all to blood red.
 */
public final class CharacterSelectScreen extends Screen {
	// The survivors' colours and the killers' (the screen goes from one to the other with the tab).
	static final int[] BG0 = {0xFF07080D, 0xFF070405};
	static final int[] PANEL = {0xDC121520, 0xE0140B0E};
	static final int[] LINE = {0xFF2A3040, 0xFF3A2228};
	static final int[] LINE2 = {0xFF3E475D, 0xFF5A323C};
	static final int[] ACCENT = {0xFFE8731A, 0xFFC42A36};
	static final int[] ACCENT2 = {0xFFFFB347, 0xFFFF6A6A};
	static final int[] STREET = {0xFFFFFFFF, 0xFFC88080};
	static final int TEXT = 0xFFECE6D8;
	static final int TEXT2 = 0xFFB4AFA2;
	static final int TEXT3 = 0xFF8A877E;
	static final int DARK_TEXT = 0xFF1A0E05;
	static final int COOL = 0xFF79B3A4;
	static final int ROW = 17;
	/** The opening scene on the spot: this long (ticks). */
	private static final float SCENE_TICKS = 140f;

	static final FontDescription LABEL = font("label");
	static final FontDescription DISPLAY = font("display");
	static final FontDescription BIG = font("display_big");

	/** A texture of tools/SelectArt.java, w by h texels (four to a GUI pixel). */
	record Tex(Identifier id, int w, int h) {
	}

	static final int D = 4;
	static final Tex PANEL_FILL = tex("panel_fill", 96, 96);
	static final Tex PANEL_LINE = tex("panel_line", 96, 96);
	static final Tex PANEL_CORNER = tex("panel_corner", 96, 96);
	static final Tex BTN_FILL = tex("btn_fill", 96, 56);
	static final Tex BTN_LINE = tex("btn_line", 96, 56);
	static final Tex SEG = tex("seg", 20, 20);
	static final Tex DIAMOND_FILL = tex("diamond_fill", 64, 64);
	static final Tex DIAMOND_LINE = tex("diamond_line", 64, 64);
	static final Tex DIAMOND_EMPTY = tex("diamond_empty", 64, 64);
	static final Tex FRAME = tex("frame", 56, 56);
	static final Tex LOCK = tex("lock", 28, 36);
	static final Tex CHEV_L = tex("chev_l", 32, 56);
	static final Tex CHEV_R = tex("chev_r", 32, 56);
	static final Tex GLOW = tex("glow", 128, 128);
	static final Tex GLOW_LINE = tex("glow_line", 256, 16);
	static final Tex BEAM = tex("beam", 128, 256);
	static final Tex PEDESTAL = tex("pedestal", 480, 120);
	static final Tex PEDESTAL_RIM = tex("pedestal_rim", 480, 120);
	static final Tex BACKDROP = tex("backdrop", 1024, 512);
	static final Tex FOG = tex("fog", 512, 128);
	static final Tex GRAIN = tex("grain", 128, 128);
	static final Tex VIGNETTE = tex("vignette", 256, 144);

	/** Remembered while the game runs: which tab and whom one looked at last. */
	private static int lastTab;
	private static String lastHero;

	private int tab;
	private int index;
	private float yaw = 18f;
	private boolean dragging;
	/** 0 their scene's pose (a killer: standing), 1 standing, 2 walking. */
	private int poseMode;
	private int itemChoice;
	private float sceneStart = -1;
	private float switchedAt = -100;
	private int switchDir = 1;
	private float lineUntil = -1;
	private float buyConfirmUntil = -1;
	private int listScroll;
	private int cardScroll;
	private int cardHeight;
	private int hoverRow = -1;
	/** Client ticks since it opened, and how far into the next this frame is: the clock of its animations. */
	private int ticks;
	private float partial;
	/** How far over to the killers' colours (0 the survivors', 1 the killers'), eased when the tab changes. */
	private float mix;
	private float mixFrom;
	private float tabAt = -100;
	/** The card fading in after a change, 0 to 1. */
	private float cardAlpha = 1f;
	private final List<Hit> hits = new ArrayList<>();

	private record Hit(int x0, int y0, int x1, int y1, Runnable action) {
		boolean in(double x, double y) {
			return x >= this.x0 && x < this.x1 && y >= this.y0 && y < this.y1;
		}
	}

	// Layout, worked out in init.
	private int top;
	private int bottom;
	private int listX0;
	private int listX1;
	private int cardX0;
	private int cardX1;
	private int stageX0;
	private int stageX1;
	private int barY;

	public CharacterSelectScreen() {
		super(Component.translatable("halloween.select.title"));
		this.tab = lastTab;
		this.mix = this.tab;
		this.mixFrom = this.tab;
		int at = lastHero == null ? -1 : this.list().indexOf(lastHero);
		this.index = Math.max(0, at);
		if (at < 0) {
			// Opened afresh: on the one picked, if any.
			String mine = this.tab == 0 ? myPick() : myKiller();
			this.index = Math.max(0, this.list().indexOf(mine));
		}
		// Opening, the card comes in and the bars fill.
		this.switchedAt = 0;
	}

	@Override
	protected void init() {
		int m = 8;
		this.top = 28;
		this.barY = this.height - 42;
		this.bottom = this.barY - 6;
		int w = this.width - 2 * m;
		int listW = Mth.clamp(w / 4, 114, 160);
		int cardW = Mth.clamp(w * 2 / 7, 150, 230);
		this.listX0 = m;
		this.listX1 = m + listW;
		this.cardX1 = this.width - m;
		this.cardX0 = this.cardX1 - cardW;
		this.stageX0 = this.listX1 + 6;
		this.stageX1 = this.cardX0 - 6;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private float now() {
		return this.ticks + this.partial;
	}

	@Override
	public void tick() {
		this.ticks++;
	}

	// ---------------------------------------------------------------- what the lobby says

	private List<String> list() {
		return this.tab == 0 ? HeroInfo.survivors() : HeroInfo.KILLERS;
	}

	private String current() {
		List<String> l = this.list();
		return l.get(Mth.clamp(this.index, 0, l.size() - 1));
	}

	private static String myName() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player == null ? "" : mc.player.getScoreboardName();
	}

	private static int me() {
		return ClientState.heroLobby.names().indexOf(myName());
	}

	private static String myPick() {
		int i = me();
		return i < 0 ? "" : ClientState.heroLobby.survivors().get(i);
	}

	private static String myKiller() {
		int i = me();
		return i < 0 ? "" : ClientState.heroLobby.killers().get(i);
	}

	private static boolean myReady() {
		int i = me();
		return i >= 0 && ClientState.heroLobby.ready().get(i);
	}

	private static boolean owned(String hero) {
		return HeroInfo.free(hero) || ClientState.heroesOwned.contains(hero);
	}

	/** Who else picked this survivor, or null. */
	private static String takenBy(String hero) {
		HwNet.HeroLobby l = ClientState.heroLobby;
		for (int i = 0; i < l.names().size(); i++) {
			if (!l.names().get(i).equals(myName()) && l.survivors().get(i).equals(hero)) {
				return l.names().get(i);
			}
		}
		return null;
	}

	/** How many others want to be this killer. */
	private static int keen(String hero) {
		HwNet.HeroLobby l = ClientState.heroLobby;
		int n = 0;
		for (int i = 0; i < l.names().size(); i++) {
			if (!l.names().get(i).equals(myName()) && l.killers().get(i).equals(hero)) {
				n++;
			}
		}
		return n;
	}

	private static boolean between() {
		return ClientState.match.state() == 0;
	}

	private static String pronoun(String hero) {
		int i = dev.halloween.game.Skins.SURVIVORS.indexOf(hero);
		return i >= 0 && i < dev.halloween.game.Skins.SLIM_COUNT ? "f" : "m";
	}

	/** For tests: a tab and a hero on it, maybe with their scene playing. */
	public void showForTest(int tab, String hero, boolean scene) {
		this.setTab(tab);
		this.show(Math.max(0, this.list().indexOf(hero)));
		this.switchedAt = -100;
		this.tabAt = -100;
		// Half way through (the clock moved on so the start isn't before it opened).
		this.ticks += (int) SCENE_TICKS;
		this.sceneStart = scene ? this.now() - SCENE_TICKS * 0.5f : -1;
	}

	// ---------------------------------------------------------------- doing things

	private void send(int action, String hero) {
		ClientPlayNetworking.send(new HwNet.HeroPick(action, hero));
	}

	static void click() {
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1f, 0.6f));
	}

	private void show(int i) {
		int n = this.list().size();
		int next = Math.floorMod(i, n);
		if (next != this.index) {
			this.switchDir = next > this.index ? 1 : -1;
			this.index = next;
			this.switchedAt = this.now();
			this.sceneStart = -1;
			this.itemChoice = 0;
			this.buyConfirmUntil = -1;
			this.cardScroll = 0;
			lastHero = this.current();
			Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1.2f, 0.5f));
			this.keepInView();
		}
	}

	private void keepInView() {
		int rows = this.listRows();
		if (this.index < this.listScroll) {
			this.listScroll = this.index;
		} else if (this.index >= this.listScroll + rows) {
			this.listScroll = this.index - rows + 1;
		}
	}

	private void setTab(int t) {
		if (t != this.tab) {
			this.tab = t;
			lastTab = t;
			this.mixFrom = this.mix;
			this.tabAt = this.now();
			this.index = 0;
			this.listScroll = 0;
			String mine = t == 0 ? myPick() : myKiller();
			this.index = Math.max(0, this.list().indexOf(mine));
			this.switchedAt = this.now();
			this.sceneStart = -1;
			this.itemChoice = 0;
			this.poseMode = 0;
			this.cardScroll = 0;
			lastHero = this.current();
			click();
			this.keepInView();
		}
	}

	/** The big button under the model: pick, buy (twice, to be sure), give the pick back. */
	private void primary() {
		String hero = this.current();
		if (!between()) {
			return;
		}
		if (!owned(hero)) {
			if (ClientState.perkPoints < HeroInfo.price(hero)) {
				return;
			}
			if (this.buyConfirmUntil < this.now()) {
				this.buyConfirmUntil = this.now() + 60;
				click();
				return;
			}
			this.buyConfirmUntil = -1;
			this.send(HeroPicks.BUY, hero);
			this.voice();
			return;
		}
		if (this.tab == 0) {
			if (hero.equals(myPick())) {
				this.send(HeroPicks.SURVIVOR, "");
				click();
			} else if (takenBy(hero) == null) {
				this.send(HeroPicks.SURVIVOR, hero);
				this.voice();
			}
		} else if (hero.equals(myKiller())) {
			this.send(HeroPicks.KILLER, "");
			click();
		} else {
			this.send(HeroPicks.KILLER, hero);
			this.voice();
		}
	}

	/** Picked: a heavy click, their line, and a low note under it. */
	private void voice() {
		this.lineUntil = this.now() + 70;
		Minecraft mc = Minecraft.getInstance();
		mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 0.55f, 0.7f));
		mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL.value(), 0.6f, 0.35f));
	}

	// ---------------------------------------------------------------- the look: colours, type, art

	static FontDescription font(String name) {
		return new FontDescription.Resource(Identifier.fromNamespaceAndPath("halloween", name));
	}

	static Tex tex(String name, int w, int h) {
		return new Tex(Identifier.fromNamespaceAndPath("halloween", "textures/gui/select/" + name + ".png"), w, h);
	}

	/** This side's colour of the pair, part way when the tab has just changed. */
	private int pal(int[] pair) {
		return ARGB.srgbLerp(this.mix, pair[0], pair[1]);
	}

	static Component in(FontDescription f, Component text) {
		return text.copy().withStyle(s -> s.withFont(f));
	}

	static Component caps(FontDescription f, Component text) {
		return Component.literal(text.getString().toUpperCase(Locale.ROOT)).withStyle(s -> s.withFont(f));
	}

	static int fade(int color, float a) {
		// Text nearly gone is not drawn at all (an alpha of almost nothing reads as opaque).
		return ARGB.multiplyAlpha(color, Math.max(0.05f, a));
	}

	static float ease(float k) {
		k = Mth.clamp(k, 0f, 1f);
		return 1 - (1 - k) * (1 - k) * (1 - k);
	}

	/** A texture stretched over the box, tinted. */
	static void blit(GuiGraphicsExtractor g, Tex t, int x, int y, int w, int h, int color) {
		if (w > 0 && h > 0) {
			g.blit(RenderPipelines.GUI_TEXTURED, t.id, x, y, 0, 0, w, h, t.w, t.h, t.w, t.h, color);
		}
	}

	/** The same at a point between pixels (embers, the sliding model's light). */
	static void blitAt(GuiGraphicsExtractor g, Tex t, float x, float y, float w, float h, int color) {
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(w / 64f, h / 64f);
		blit(g, t, 0, 0, 64, 64, color);
		g.pose().popMatrix();
	}

	/** Nine-sliced: the corners kept, b GUI pixels of each side kept, the rest stretched. */
	static void nine(GuiGraphicsExtractor g, Tex t, int x, int y, int w, int h, int b, int color) {
		int bx = Math.min(b, w / 2);
		int by = Math.min(b, h / 2);
		int[] xs = {x, x + bx, x + w - bx, x + w};
		int[] us = {0, bx * D, t.w - bx * D, t.w};
		int[] ys = {y, y + by, y + h - by, y + h};
		int[] vs = {0, by * D, t.h - by * D, t.h};
		for (int i = 0; i < 3; i++) {
			for (int j = 0; j < 3; j++) {
				int w1 = xs[i + 1] - xs[i];
				int h1 = ys[j + 1] - ys[j];
				if (w1 > 0 && h1 > 0) {
					g.blit(RenderPipelines.GUI_TEXTURED, t.id, xs[i], ys[j], us[i], vs[j], w1, h1, us[i + 1] - us[i], vs[j + 1] - vs[j], t.w, t.h, color);
				}
			}
		}
	}

	/** Sliced left and right only, stretched top to bottom: the slanted buttons. */
	static void three(GuiGraphicsExtractor g, Tex t, int x, int y, int w, int h, int color) {
		int b = Math.min(6, w / 2);
		int[] xs = {x, x + b, x + w - b, x + w};
		int[] us = {0, b * D, t.w - b * D, t.w};
		for (int i = 0; i < 3; i++) {
			int w1 = xs[i + 1] - xs[i];
			if (w1 > 0) {
				g.blit(RenderPipelines.GUI_TEXTURED, t.id, xs[i], y, us[i], 0, w1, h, us[i + 1] - us[i], t.h, t.w, t.h, color);
			}
		}
	}

	/** Text with its letters spaced out (the small titles); returns its width. */
	private float tracked(GuiGraphicsExtractor g, String text, FontDescription f, float x, int y, int color, float spacing) {
		float at = x;
		for (int i = 0; i < text.length(); ) {
			int cp = text.codePointAt(i);
			Component c = Component.literal(new String(Character.toChars(cp))).withStyle(s -> s.withFont(f));
			g.pose().pushMatrix();
			g.pose().translate(at, y);
			g.text(this.font, c, 0, 0, color, false);
			g.pose().popMatrix();
			at += this.font.width(c) + spacing;
			i += Character.charCount(cp);
		}
		return at - x - spacing;
	}

	private float trackedWidth(String text, FontDescription f, float spacing) {
		float w = 0;
		for (int i = 0; i < text.length(); ) {
			int cp = text.codePointAt(i);
			w += this.font.width(Component.literal(new String(Character.toChars(cp))).withStyle(s -> s.withFont(f))) + spacing;
			i += Character.charCount(cp);
		}
		return Math.max(0, w - spacing);
	}

	/** A pseudo-random number in 0..1 for an ember's or a mote's i-th property. */
	static float rnd(int i, int k) {
		int h = i * 374761393 + k * 668265263;
		h = (h ^ (h >>> 13)) * 1274126177;
		return ((h ^ (h >>> 16)) & 0xFFFF) / 65535f;
	}

	// ---------------------------------------------------------------- drawing

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		this.partial = partial;
		this.updateMix();
		float now = this.now();
		int w = this.width;
		int h = this.height;
		// Its own backdrop, not the world blurred: the street on the night, far behind and out of focus.
		g.fill(0, 0, w, h, this.pal(BG0));
		float cover = Math.max(w / 1024f, h / 512f) * 1.06f;
		float bw = 1024 * cover;
		float bh = 512 * cover;
		float drift = (mouseX - w / 2f) / w * -10f;
		g.pose().pushMatrix();
		g.pose().translate((w - bw) / 2f + drift, (h - bh) / 2f - h * 0.04f);
		g.pose().scale(cover / 4f, cover / 4f);
		blit(g, BACKDROP, 0, 0, 1024 * 4, 512 * 4, ARGB.multiplyAlpha(this.pal(STREET), 0.85f));
		g.pose().popMatrix();
		// The lantern's light on the hero, flickering a little; a cone of it from above.
		float flicker = 0.9f + 0.06f * Mth.sin(now * 0.21f) + 0.04f * Mth.sin(now * 0.73f + 1.3f);
		int cx = (this.stageX0 + this.stageX1) / 2;
		int sw = this.stageX1 - this.stageX0;
		int acc2 = this.pal(ACCENT2);
		blitAt(g, GLOW, cx - sw * 0.75f, this.top - h * 0.1f, sw * 1.5f, h * 0.95f, ARGB.color(0.20f * flicker, acc2));
		blitAt(g, BEAM, cx - sw * 0.42f, this.top - 6, sw * 0.84f, this.bottom - this.top - 30, ARGB.color(0.09f * flicker, acc2));
		// Fog low down, two layers drifting opposite ways.
		int fy = (int) (h * 0.52f);
		int fogColor = ARGB.srgbLerp(this.mix, 0xFFB8C4D0, 0xFFC8A0A8);
		g.blit(RenderPipelines.GUI_TEXTURED, FOG.id, 0, fy, (now * 0.5f) % FOG.w, 0, w, h - fy, FOG.w, FOG.h, FOG.w, FOG.h, ARGB.color(0.10f, fogColor));
		g.blit(RenderPipelines.GUI_TEXTURED, FOG.id, 0, fy + h / 10, FOG.w - (now * 0.3f) % FOG.w, 0, w, h - fy - h / 10, FOG.w * 3 / 2, FOG.h, FOG.w, FOG.h,
			ARGB.color(0.08f, fogColor));
		// Embers rising, and dust turning in the light.
		for (int i = 0; i < 22; i++) {
			float speed = 0.25f + rnd(i, 1) * 0.35f;
			float life = h * 0.85f / speed;
			float t = (now + rnd(i, 2) * life) % life;
			float f = t / life;
			float x = w * (0.04f + 0.92f * rnd(i, 3)) + Mth.sin(now * 0.03f + i) * 12 * f;
			float y = h * 0.98f - t * speed;
			float a = Mth.sin(f * Mth.PI) * (0.55f + 0.45f * Mth.sin(now * 0.31f + i * 1.7f)) * 0.75f;
			float s = 1.6f + rnd(i, 4) * 2.2f;
			blitAt(g, GLOW, x - s, y - s, s * 2, s * 2, ARGB.color(a, acc2));
			blitAt(g, GLOW, x - s * 0.3f, y - s * 0.3f, s * 0.6f, s * 0.6f, ARGB.color(a, 0xFFFFE6C0));
		}
		for (int i = 0; i < 16; i++) {
			float x = cx + (rnd(i, 5) - 0.5f) * sw * 0.8f + Mth.sin(now * 0.011f + i * 2.1f) * 14;
			float y = this.top + 20 + rnd(i, 6) * (this.bottom - this.top - 60) + Mth.cos(now * 0.009f + i) * 10;
			float a = 0.25f + 0.2f * Mth.sin(now * 0.05f + i * 3.3f);
			blitAt(g, GLOW, x - 1, y - 1, 2, 2, ARGB.color(a * flicker, 0xFFF2E4CC));
		}
		// The dark at the edges.
		blit(g, VIGNETTE, 0, 0, w, h, 0xF0FFFFFF);
	}

	private void updateMix() {
		float k = ease((this.now() - this.tabAt) / 8f);
		this.mix = Mth.lerp(k, this.mixFrom, this.tab);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		this.hits.clear();
		this.partial = partial;
		this.updateMix();
		if (this.index >= this.list().size()) {
			this.index = 0;
		}
		this.cardAlpha = ease((this.now() - this.switchedAt) / 5f);
		this.header(g, mouseX, mouseY);
		this.heroList(g, mouseX, mouseY);
		this.stage(g, mouseX, mouseY, partial);
		this.card(g);
		this.bottomBar(g, mouseX, mouseY);
		super.extractRenderState(g, mouseX, mouseY, partial);
		// Film grain over it all, moving on every other tick.
		int seed = this.ticks / 2;
		g.blit(RenderPipelines.GUI_TEXTURED, GRAIN.id, 0, 0, rnd(seed, 7) * GRAIN.w, rnd(seed, 8) * GRAIN.h, this.width, this.height, this.width * 2,
			this.height * 2, GRAIN.w, GRAIN.h, 0x0DFFFFFF);
	}

	/** A panel of dark glass: cut corners, a thin edge, the brackets in the accent. */
	private void panel(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1) {
		nine(g, PANEL_FILL, x0, y0, x1 - x0, y1 - y0, 9, this.pal(PANEL));
		nine(g, PANEL_LINE, x0, y0, x1 - x0, y1 - y0, 9, this.pal(LINE2));
		nine(g, PANEL_CORNER, x0, y0, x1 - x0, y1 - y0, 9, ARGB.multiplyAlpha(this.pal(ACCENT), 0.9f));
	}

	/** A small title, its letters spaced, a rule running on from it to the right edge. */
	private void label(GuiGraphicsExtractor g, Component text, int x, int y, int right, float a) {
		String s = text.getString().toUpperCase(Locale.ROOT);
		float w = this.tracked(g, s, LABEL, x, y, fade(TEXT3, a), 0.6f);
		int rx = Math.round(x + w + 4);
		if (right - rx > 6) {
			g.fill(rx, y + 4, right, y + 5, fade(this.pal(LINE2), a * 0.8f));
		}
	}

	/** The kinds of button: the minor ones under the model, the pick, the one that matters most (Ready). */
	private enum Kind {
		MINOR, PICK, PICKED, BUY, MAIN
	}

	/** A slanted button; returns whether the mouse is on it. */
	private boolean button(GuiGraphicsExtractor g, int x, int y, int w, int h, Component text, boolean on, Kind kind, int mouseX, int mouseY,
		Runnable action) {
		boolean hover = on && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		int acc = this.pal(ACCENT);
		int acc2 = this.pal(ACCENT2);
		int fill;
		int line;
		int color;
		switch (kind) {
			case MAIN -> {
				fill = !on ? 0xFF2A2420 : hover ? ARGB.srgbLerp(0.45f, acc, acc2) : acc;
				line = !on ? this.pal(LINE2) : acc2;
				color = !on ? TEXT3 : DARK_TEXT;
			}
			case PICK, BUY -> {
				int edge = kind == Kind.BUY ? acc2 : acc;
				fill = hover ? ARGB.multiplyAlpha(edge, 0.3f) : 0xE00E1118;
				line = !on ? this.pal(LINE2) : edge;
				color = !on ? TEXT3 : hover ? TEXT : kind == Kind.BUY ? acc2 : ARGB.srgbLerp(0.35f, acc2, TEXT);
			}
			case PICKED -> {
				fill = hover ? ARGB.multiplyAlpha(COOL, 0.25f) : 0xE00E1118;
				line = COOL;
				color = hover ? TEXT : COOL;
			}
			default -> {
				fill = hover ? 0xF0202634 : 0xE00E1118;
				line = hover ? acc : this.pal(LINE2);
				color = !on ? TEXT3 : hover ? TEXT : TEXT2;
			}
		}
		three(g, BTN_FILL, x, y, w, h, fill);
		three(g, BTN_LINE, x, y, w, h, line);
		Component label = kind == Kind.MAIN ? caps(DISPLAY, text) : caps(LABEL, text);
		int tw = this.font.width(label);
		int ty = y + Math.round((h - (kind == Kind.MAIN ? 8 : 6)) / 2f);
		int tx = x + (w - tw) / 2;
		if (kind == Kind.BUY && on) {
			blit(g, LOCK, tx - 9, y + (h - 8) / 2, 6, 8, color);
			tx += 4;
		}
		g.text(this.font, label, tx, ty, color, false);
		if (on) {
			this.hits.add(new Hit(x, y, x + w, y + h, action));
		}
		return hover;
	}

	private void header(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int x = this.listX0;
		int acc = this.pal(ACCENT);
		for (int t = 0; t < 2; t++) {
			Component name = caps(DISPLAY, Component.translatable(t == 0 ? "halloween.select.tab.survivors" : "halloween.select.tab.killers"));
			int w = this.font.width(name) + 22;
			boolean on = this.tab == t;
			boolean hover = !on && mouseX >= x && mouseX < x + w && mouseY >= 6 && mouseY < 22;
			int y = 6;
			if (on) {
				blitAt(g, GLOW, x - 10, y - 8, w + 20, 32, ARGB.multiplyAlpha(acc, 0.35f));
			}
			three(g, BTN_FILL, x, y, w, 16, on ? acc : hover ? 0xF0202634 : 0xD00E1118);
			three(g, BTN_LINE, x, y, w, 16, on ? this.pal(ACCENT2) : this.pal(LINE2));
			g.text(this.font, name, x + 11, y + 4, on ? DARK_TEXT : hover ? TEXT : TEXT2, false);
			int tt = t;
			this.hits.add(new Hit(x, 4, x + w, 24, () -> this.setTab(tt)));
			x += w + 2;
		}
		g.text(this.font, in(LABEL, Component.literal("TAB")), x + 5, 11, TEXT3, false);
		x += 5 + this.font.width(in(LABEL, Component.literal("TAB"))) + 12;
		// The player's level: a diamond with the number and the bar under the word; opens the level road.
		if (LevelScreen.available()) {
			int level = dev.halloween.game.PlayerLevel.of(ClientState.earned);
			Component word = caps(LABEL, Component.translatable("halloween.level.short"));
			int pw = 17 + this.font.width(word) + 8;
			boolean hover = mouseX >= x && mouseX < x + pw && mouseY >= 4 && mouseY < 24;
			blitAt(g, GLOW, x - 6, 1, 26, 26, ARGB.multiplyAlpha(acc, hover ? 0.6f : 0.35f));
			blit(g, DIAMOND_FILL, x, 6, 15, 15, ARGB.srgbLerp(0.25f, acc, 0xFF2A1406));
			blit(g, DIAMOND_LINE, x, 6, 15, 15, hover ? TEXT : this.pal(ACCENT2));
			Component num = in(LABEL, Component.literal(Integer.toString(level)));
			g.text(this.font, num, x + 8 - this.font.width(num) / 2, 10, TEXT, false);
			g.text(this.font, word, x + 18, 8, hover ? TEXT : TEXT2, false);
			int bw = pw - 18 - 4;
			g.fill(x + 18, 17, x + 18 + bw, 19, 0xC0000000);
			g.fill(x + 18, 17, x + 18 + Math.round(bw * dev.halloween.game.PlayerLevel.progress(ClientState.earned)), 19, acc);
			this.hits.add(new Hit(x - 2, 4, x + pw, 24, () -> {
				click();
				Minecraft.getInstance().gui.setScreen(new LevelScreen(this));
			}));
		}
		// The lobby: a pip each, lit for those ready; how many; the countdown, large.
		HwNet.HeroLobby l = ClientState.heroLobby;
		int ready = 0;
		for (boolean r : l.ready()) {
			ready += r ? 1 : 0;
		}
		int rx = this.cardX1;
		// Settings, in the top right corner: the /hw commands as buttons.
		{
			Component word = caps(LABEL, Component.translatable("halloween.settings.title"));
			int sw = this.font.width(word) + 22;
			rx -= sw;
			boolean hover = mouseX >= rx && mouseX < rx + sw && mouseY >= 6 && mouseY < 22;
			three(g, BTN_FILL, rx, 6, sw, 16, hover ? 0xF0202634 : 0xD00E1118);
			three(g, BTN_LINE, rx, 6, sw, 16, hover ? acc : this.pal(LINE2));
			blit(g, DIAMOND_LINE, rx + 7, 10, 8, 8, hover ? this.pal(ACCENT2) : TEXT3);
			g.text(this.font, word, rx + 17, 10, hover ? TEXT : TEXT2, false);
			this.hits.add(new Hit(rx, 4, rx + sw, 24, () -> {
				click();
				Minecraft.getInstance().gui.setScreen(new SettingsScreen(this));
			}));
			rx -= 10;
		}
		if (between() && l.countdown() >= 0) {
			Component time = in(DISPLAY, Component.literal(String.format("0:%02d", l.countdown())));
			int tw = this.font.width(time);
			rx -= tw;
			blitAt(g, GLOW, rx - 8, 0, tw + 16, 26, ARGB.multiplyAlpha(this.pal(ACCENT2), 0.25f));
			g.text(this.font, time, rx, 10, this.pal(ACCENT2), false);
			rx -= 5;
			String in = Component.translatable("halloween.select.start_in").getString().toUpperCase(Locale.ROOT);
			rx -= Math.round(this.trackedWidth(in, LABEL, 0.6f));
			this.tracked(g, in, LABEL, rx, 11, TEXT2, 0.6f);
			rx -= 8;
		}
		String status = (between() ? Component.translatable("halloween.select.ready_of", ready, l.names().size())
			: Component.translatable("halloween.select.in_match")).getString().toUpperCase(Locale.ROOT);
		rx -= Math.round(this.trackedWidth(status, LABEL, 0.6f));
		this.tracked(g, status, LABEL, rx, 11, between() ? TEXT : TEXT2, 0.6f);
		if (between()) {
			rx -= 4;
			for (int i = l.ready().size() - 1; i >= 0; i--) {
				rx -= 6;
				boolean r = l.ready().get(i);
				blit(g, r ? DIAMOND_FILL : DIAMOND_LINE, rx, 11, 6, 6, r ? COOL : this.pal(LINE2));
			}
		}
		// The rule under it all, lit in the middle.
		g.fill(this.listX0, 25, this.cardX1, 26, ARGB.multiplyAlpha(this.pal(LINE), 0.9f));
		blit(g, GLOW_LINE, this.listX0, 23, this.cardX1 - this.listX0, 5, ARGB.multiplyAlpha(acc, 0.5f));
	}

	private int listRows() {
		// Between the panel's title and the chance row at its foot.
		return Math.max(1, (this.bottom - this.top - 18 - ROW - 12) / ROW);
	}

	private void heroList(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int x0 = this.listX0;
		int x1 = this.listX1;
		int y0 = this.top + 2;
		int acc = this.pal(ACCENT);
		int acc2 = this.pal(ACCENT2);
		this.panel(g, x0, y0, x1, this.bottom);
		List<String> heroes = this.list();
		this.label(g, Component.translatable(this.tab == 0 ? "halloween.select.list.survivors" : "halloween.select.list.killers", heroes.size()), x0 + 8,
			y0 + 6, x1 - 8, 1f);
		int rows = this.listRows();
		this.listScroll = Mth.clamp(this.listScroll, 0, Math.max(0, heroes.size() - rows));
		int y = y0 + 17;
		int hovered = -1;
		for (int r = 0; r < rows && this.listScroll + r < heroes.size(); r++) {
			int i = this.listScroll + r;
			String hero = heroes.get(i);
			boolean shown = i == this.index;
			boolean hover = mouseX >= x0 && mouseX < x1 && mouseY >= y && mouseY < y + ROW;
			if (hover) {
				hovered = i;
			}
			boolean mine = hero.equals(this.tab == 0 ? myPick() : myKiller());
			boolean have = owned(hero);
			String other = this.tab == 0 ? takenBy(hero) : null;
			int tx0 = x0 + 4;
			int tx1 = x1 - 4;
			if (shown) {
				// The one shown: lit in the accent, a bar down its left, its frame glowing.
				g.fillGradient(tx0, y, tx1, y + ROW - 1, ARGB.multiplyAlpha(acc, 0.30f), ARGB.multiplyAlpha(acc, 0.12f));
				g.fill(tx0, y, tx0 + 2, y + ROW - 1, acc2);
				blitAt(g, GLOW, tx0 - 4, y - 7, 30, 30, ARGB.multiplyAlpha(acc, 0.6f));
			} else if (hover) {
				g.fill(tx0, y, tx1, y + ROW - 1, 0x18FFFFFF);
				g.fill(tx0, y, tx0 + 1, y + ROW - 1, this.pal(LINE2));
			}
			boolean dim = !have || other != null;
			int fx = tx0 + 5;
			int fy = y + 1;
			g.fill(fx + 2, fy + 2, fx + 12, fy + 12, 0xFF0A0C12);
			this.face(g, hero, fx + 2, fy + 2, 10, !dim);
			blit(g, FRAME, fx, fy, 14, 14, shown ? acc2 : mine ? COOL : dim ? this.pal(LINE) : this.pal(LINE2));
			int color = shown ? (dim ? TEXT2 : TEXT) : dim ? TEXT3 : hover ? TEXT : TEXT2;
			String nameText = Component.translatable("halloween.hero.name." + hero).getString();
			// Its tag on the right: mine, taken, how many want him, locked with the price.
			int tagW = 0;
			if (mine) {
				g.text(this.font, "✔", tx1 - 10, y + 5, COOL, false);
				tagW = 12;
			} else if (!have) {
				Component price = in(LABEL, Component.literal(String.valueOf(HeroInfo.price(hero))));
				int pw = this.font.width(price);
				boolean afford = ClientState.perkPoints >= HeroInfo.price(hero);
				g.text(this.font, price, tx1 - 3 - pw, y + 5, afford ? acc2 : TEXT3, false);
				blit(g, LOCK, tx1 - 10 - pw, y + 4, 6, 8, afford ? acc2 : TEXT3);
				tagW = pw + 13;
			} else if (other != null) {
				Component taken = caps(LABEL, Component.translatable("halloween.select.taken_" + pronoun(hero)));
				g.text(this.font, taken, tx1 - 3 - this.font.width(taken), y + 5, TEXT3, false);
				tagW = this.font.width(taken) + 5;
			} else if (this.tab == 1 && keen(hero) > 0) {
				Component k = caps(LABEL, Component.translatable("halloween.select.keen", keen(hero)));
				g.text(this.font, k, tx1 - 3 - this.font.width(k), y + 5, TEXT2, false);
				tagW = this.font.width(k) + 5;
			}
			int nameW = tx1 - (fx + 18) - tagW - 2;
			g.text(this.font, this.cut(nameText, nameW), fx + 18, y + 4, color, false);
			int ii = i;
			this.hits.add(new Hit(x0, y, x1, y + ROW, () -> this.show(ii)));
			y += ROW;
		}
		if (hovered != this.hoverRow) {
			this.hoverRow = hovered;
			if (hovered >= 0) {
				Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.8f, 0.12f));
			}
		}
		if (heroes.size() > rows) {
			int track = rows * ROW;
			int thumb = Math.max(10, track * rows / heroes.size());
			int at = y0 + 17 + (track - thumb) * this.listScroll / Math.max(1, heroes.size() - rows);
			g.fill(x1 - 3, y0 + 17, x1 - 2, y0 + 17 + track, ARGB.multiplyAlpha(this.pal(LINE), 0.8f));
			g.fill(x1 - 3, at, x1 - 2, at + thumb, ARGB.multiplyAlpha(acc, 0.85f));
		}
		// Chance, or no killer for me: a seal of its own at the foot.
		int ry = this.bottom - ROW - 6;
		g.fill(x0 + 8, ry - 4, x1 - 8, ry - 3, this.pal(LINE));
		boolean hover = mouseX >= x0 && mouseX < x1 && mouseY >= ry && mouseY < ry + ROW && between();
		if (hover) {
			g.fill(x0 + 4, ry, x1 - 4, ry + ROW - 1, 0x18FFFFFF);
		}
		boolean on = this.tab == 0 ? HeroPicks.RANDOM.equals(myPick()) : myKiller().isEmpty();
		if (on) {
			blitAt(g, GLOW, x0 + 4, ry - 4, 24, 24, ARGB.multiplyAlpha(COOL, 0.4f));
		}
		blit(g, DIAMOND_LINE, x0 + 9, ry + 1, 14, 14, on ? COOL : hover ? acc : TEXT3);
		int tagW = 0;
		if (on) {
			g.text(this.font, "✔", x1 - 14, ry + 4, COOL, false);
			tagW = 14;
		} else if (this.tab == 0) {
			Component bonus = in(LABEL, Component.literal("+" + dev.halloween.HwConfig.get().heroRandomBonus));
			int bw = this.font.width(bonus);
			g.text(this.font, bonus, x1 - 7 - bw, ry + 5, acc2, false);
			tagW = bw + 9;
		}
		String text = Component.translatable(this.tab == 0 ? "halloween.select.random" : "halloween.select.no_killer").getString().toUpperCase(Locale.ROOT);
		this.tracked(g, text, LABEL, x0 + 27, ry + 5, on || hover ? TEXT : TEXT2, 0.4f);
		if (this.tab == 0) {
			this.hits.add(new Hit(x0, ry, x1, ry + ROW, () -> {
				if (between()) {
					this.send(HeroPicks.SURVIVOR, on ? "" : HeroPicks.RANDOM);
					click();
				}
			}));
		} else {
			this.hits.add(new Hit(x0, ry, x1, ry + ROW, () -> {
				if (between()) {
					this.send(HeroPicks.KILLER, "");
					click();
				}
			}));
		}
	}

	/** A flat ellipse, row by row. */
	private static void ellipse(GuiGraphicsExtractor g, int cx, int cy, int rx, int ry, int color) {
		for (int dy = -ry; dy <= ry; dy++) {
			float k = dy / (float) (ry + 0.5f);
			int w = Math.round(rx * Mth.sqrt(Math.max(0f, 1 - k * k)));
			if (w > 0) {
				g.fill(cx - w, cy + dy, cx + w, cy + dy + 1, color);
			}
		}
	}

	/** The hero's face for the list: a survivor's from their skin, a killer's head in 3D. */
	private void face(GuiGraphicsExtractor g, String hero, int x, int y, int size, boolean bright) {
		if (!HeroInfo.killer(hero)) {
			PlayerFaceExtractor.extractRenderState(g, ClientSkins.skin(hero), x, y, size, bright ? 0xFFFFFFFF : 0xFF5A5A5A);
			return;
		}
		int slot = 10 + HeroInfo.KILLERS.indexOf(hero);
		AvatarRenderState s = HeroPreview.state(slot, hero, -25f, false, 0, -1, net.minecraft.world.item.ItemStack.EMPTY, 0f);
		if (s == null) {
			return;
		}
		g.fill(x, y, x + size, y + size, 0xFF201A16);
		// Close on the head: the model drawn large, the box only the face's size.
		float height = hero.equals("michael") ? 2.1f : hero.equals("demogorgon") ? 2.3f : 2.0f;
		float scale = size * 2.4f;
		Vector3f translation = new Vector3f(0f, height * 0.88f, 0f);
		g.entity(s, scale, translation, new Quaternionf().rotateZ(Mth.PI), null, x, y, x + size, y + size);
	}

	private void stage(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		int x0 = this.stageX0;
		int x1 = this.stageX1;
		int y0 = this.top + 2;
		int y1 = this.bottom;
		String hero = this.current();
		float now = this.now();
		int acc = this.pal(ACCENT);
		int acc2 = this.pal(ACCENT2);
		boolean scene = this.sceneStart >= 0 && now - this.sceneStart < SCENE_TICKS;
		if (!scene) {
			this.sceneStart = -1;
		}
		float f = scene ? (now - this.sceneStart) / SCENE_TICKS : 0f;
		float flicker = 0.88f + 0.08f * Mth.sin(now * 0.21f) + 0.04f * Mth.sin(now * 0.73f + 1.3f);
		int cx = (x0 + x1) / 2;
		int floor = y1 - 46;
		float k = Mth.clamp((now - this.switchedAt) / 6f, 0f, 1f);
		int slide = Math.round((1 - k) * (1 - k) * 28 * this.switchDir);
		// The pedestal they stand on, its rim catching the lantern; the light behind them.
		int pw = Math.min(124, (x1 - x0) * 3 / 4);
		int ph = pw / 4;
		blitAt(g, GLOW, cx - pw * 0.55f + slide * 0.5f, y0 + 34, pw * 1.1f, floor - y0 - 20, ARGB.color(0.16f * flicker, acc2));
		blit(g, PEDESTAL, cx - pw / 2, floor - ph * 11 / 30, pw, ph, 0xFFFFFFFF);
		blit(g, PEDESTAL_RIM, cx - pw / 2, floor - ph * 11 / 30, pw, ph, ARGB.color(0.85f * flicker, acc));
		blitAt(g, GLOW, cx - pw * 0.4f, floor - ph * 0.3f, pw * 0.8f, ph * 0.6f, ARGB.color(0.22f * flicker, acc2));
		ellipse(g, cx + slide, floor, 20, 3, 0x60000000);
		// The name, large, flaring as they come on; their role under it.
		Component name = caps(BIG, Component.translatable("halloween.hero.name." + hero));
		if (!scene) {
			float flare = 1 - ease((now - this.switchedAt) / 10f);
			int nw = this.font.width(name);
			int ny = y0 + 4;
			// A long name made smaller to stay on the stage.
			float fitScale = Math.min(1f, (x1 - x0 - 16f) / nw);
			int full = nw;
			nw = Math.round(nw * fitScale);
			float gw = Math.min(nw * 1.6f, x1 - x0);
			blitAt(g, GLOW, cx - gw / 2, ny - 14, gw, 46, ARGB.color(0.16f + 0.35f * flare, acc));
			g.pose().pushMatrix();
			g.pose().translate(cx, ny + 18 * (1 - fitScale));
			g.pose().scale(fitScale, fitScale);
			g.text(this.font, name, -full / 2, 0, ARGB.srgbLerp(flare, TEXT, acc2), false);
			g.pose().popMatrix();
			int lw = Math.min(nw + 52, x1 - x0);
			blit(g, GLOW_LINE, cx - lw / 2, ny + 20, lw, 4, ARGB.color(0.9f, acc));
			String role = Component.translatable(HeroInfo.killer(hero) ? "halloween.select.role.killer" : "halloween.select.role.survivor").getString()
				.toUpperCase(Locale.ROOT);
			float rw = this.trackedWidth(role, LABEL, 1.4f);
			this.tracked(g, role, LABEL, cx - rw / 2, ny + 27, TEXT2, 1.4f);
			// Down the side of the hero, small: where and when.
			String side = Component.translatable("halloween.select.place").getString().toUpperCase(Locale.ROOT);
			if (this.trackedWidth(side, LABEL, 0.5f) < floor - y0 - 38) {
				g.pose().pushMatrix();
				g.pose().translate(x0 + 24, floor - 2);
				g.pose().rotate(-Mth.HALF_PI);
				this.tracked(g, side, LABEL, 0, 0, ARGB.multiplyAlpha(TEXT3, 0.8f), 0.5f);
				g.pose().popMatrix();
			}
		}
		int ay = (y0 + floor) / 2 - 7;
		this.arrow(g, x0 + 6, ay, CHEV_L, mouseX, mouseY, () -> this.show(this.index - 1));
		this.arrow(g, x1 - 14, ay, CHEV_R, mouseX, mouseY, () -> this.show(this.index + 1));
		// The model.
		int my0 = y0 + 42;
		int my1 = floor + 4;
		boolean walk = !scene && this.poseMode == 2 || scene && HeroInfo.killer(hero);
		float sceneTime = HeroInfo.killer(hero) ? -1 : scene ? now - this.sceneStart : this.poseMode == 0 ? now : -1;
		float turn = scene ? 0f : this.yaw;
		AvatarRenderState s = HeroPreview.state(0, hero, turn, walk, now, sceneTime, HeroPreview.item(hero, this.itemChoice), partial);
		int boxH = my1 - my0;
		float height = hero.equals("michael") ? 2.1f : hero.equals("demogorgon") ? 2.35f : hero.equals("vecna") ? 2.1f : 1.8f;
		float scale = boxH / (height * 1.12f);
		if (scene && !HeroInfo.killer(hero) && f > 0.38f && f < 0.78f) {
			// Him, behind them for a moment, a little off to the side.
			AvatarRenderState ghost = HeroPreview.state(1, "michael", 0f, false, now, -1, HeroPreview.item("michael", 0), partial);
			if (ghost != null) {
				int gx = slide + (x1 - x0) / 4;
				g.entity(ghost, scale * 0.68f, new Vector3f(0f, 2.1f / 2f + 0.1f, 0f), new Quaternionf().rotateZ(Mth.PI), null, x0 + gx, my0, x1 + gx,
					my1 - 16);
			}
		}
		if (s != null) {
			Quaternionf tilt = new Quaternionf().rotateX(-4f * Mth.DEG_TO_RAD);
			Quaternionf rotation = new Quaternionf().rotateZ(Mth.PI).mul(tilt);
			g.entity(s, scale, new Vector3f(0f, height / 2f, 0f), rotation, tilt, x0 + slide, my0, x1 + slide, my1);
		}
		this.hits.add(new Hit(x0, my0, x1, my1, () -> this.dragging = true));
		if (scene) {
			this.sceneOverlay(g, hero, f, x0, y0, x1, floor + 10);
		} else if (now < this.lineUntil) {
			// Their line as they are picked.
			Component line = Component.translatable("halloween.heroinfo." + hero + ".quote").withStyle(ChatFormatting.ITALIC);
			float a = Mth.clamp(Math.min((this.lineUntil - now) / 10f, 1f), 0f, 1f);
			List<FormattedCharSequence> lines = this.font.split(line, x1 - x0 - 40);
			int ly = my0 + 4;
			for (FormattedCharSequence l : lines) {
				g.centeredText(this.font, l, cx, ly, Cutscene.alpha(a, 0xE8DCC8));
				ly += 10;
			}
		}
		// Under the model: the pose, the thing in hand, the scene; then the pick.
		int by = floor + 10;
		int bw = Math.min(72, (x1 - x0 - 16) / 3);
		int bx = cx - (bw * 3 + 6) / 2;
		Component pose = this.fit(Component.translatable("halloween.select.pose." + (HeroInfo.killer(hero) ? Math.max(1, this.poseMode) : this.poseMode)),
			Component.translatable("halloween.select.pose_short"), bw);
		this.button(g, bx, by, bw, 13, pose, true, Kind.MINOR, mouseX, mouseY, () -> {
			this.poseMode = HeroInfo.killer(hero) ? (this.poseMode == 2 ? 1 : 2) : (this.poseMode + 1) % 3;
			click();
		});
		int items = HeroPreview.items(hero);
		Component item = Component.translatable("halloween.select.item." + (hero.equals("michael") ? "m" : "s") + Math.min(this.itemChoice, Math.max(0, items - 1)));
		item = this.fit(items == 0 ? Component.translatable("halloween.select.item.none") : item, Component.translatable("halloween.select.item_short"), bw);
		this.button(g, bx + bw + 3, by, bw, 13, item, items > 0, Kind.MINOR, mouseX, mouseY,
			() -> {
				this.itemChoice = (this.itemChoice + 1) % Math.max(1, items);
				click();
			});
		this.button(g, bx + 2 * (bw + 3), by, bw, 13, Component.translatable(scene ? "halloween.select.scene_stop" : "halloween.select.scene"), true, Kind.MINOR,
			mouseX, mouseY, () -> {
				this.sceneStart = this.sceneStart >= 0 ? -1 : this.now();
				click();
			});
		int ppw = Math.min(170, x1 - x0 - 24);
		Kind kind = !owned(hero) ? Kind.BUY : (this.tab == 0 ? hero.equals(myPick()) : hero.equals(myKiller())) ? Kind.PICKED : Kind.PICK;
		this.button(g, cx - ppw / 2, by + 16, ppw, 15, this.primaryLabel(hero), this.primaryOn(hero), kind, mouseX, mouseY, this::primary);
	}

	/** The text cut to the width, an ellipsis where it was cut. */
	private String cut(String text, int w) {
		if (this.font.width(text) <= w) {
			return text;
		}
		return this.font.plainSubstrByWidth(text, w - this.font.width("…")).stripTrailing() + "…";
	}

	/** The label if it fits the button, else the short one. */
	private Component fit(Component label, Component shorter, int w) {
		return this.font.width(caps(LABEL, label)) <= w - 10 ? label : shorter;
	}

	private void arrow(GuiGraphicsExtractor g, int x, int y, Tex chevron, int mouseX, int mouseY, Runnable action) {
		boolean hover = mouseX >= x - 4 && mouseX < x + 12 && mouseY >= y - 4 && mouseY < y + 18;
		if (hover) {
			blitAt(g, GLOW, x - 10, y - 8, 28, 30, ARGB.multiplyAlpha(this.pal(ACCENT), 0.5f));
		}
		blit(g, chevron, x, y, 8, 14, hover ? this.pal(ACCENT2) : TEXT3);
		this.hits.add(new Hit(x - 4, y - 4, x + 12, y + 18, action));
	}

	private boolean primaryOn(String hero) {
		if (!between()) {
			return false;
		}
		if (!owned(hero)) {
			return ClientState.perkPoints >= HeroInfo.price(hero);
		}
		return this.tab == 1 || hero.equals(myPick()) || takenBy(hero) == null;
	}

	private Component primaryLabel(String hero) {
		if (!between()) {
			return Component.translatable("halloween.select.after_match");
		}
		if (!owned(hero)) {
			if (this.buyConfirmUntil >= this.now()) {
				return Component.translatable("halloween.select.buy_confirm", HeroInfo.price(hero));
			}
			return Component.translatable(ClientState.perkPoints >= HeroInfo.price(hero) ? "halloween.select.buy" : "halloween.select.buy_short",
				HeroInfo.price(hero));
		}
		if (this.tab == 1) {
			return Component.translatable(hero.equals(myKiller()) ? "halloween.select.killer_on" : "halloween.select.killer_want");
		}
		if (hero.equals(myPick())) {
			return Component.translatable("halloween.select.picked");
		}
		String other = takenBy(hero);
		return other != null ? Component.translatable("halloween.select.taken_by", other) : Component.translatable("halloween.select.pick");
	}

	/** Their opening scene in the stage: bars, the name, the line; him behind them. */
	private void sceneOverlay(GuiGraphicsExtractor g, String hero, float f, int x0, int y0, int x1, int y1) {
		float bars = f < 0.08f ? f / 0.08f : f > 0.92f ? (1 - f) / 0.08f : 1f;
		int bar = Math.round((y1 - y0) * 0.12f * bars);
		g.fill(x0, y0, x1, y0 + bar, 0xFF000000);
		g.fill(x0, y1 - bar, x1, y1, 0xFF000000);
		int cx = (x0 + x1) / 2;
		Minecraft mc = Minecraft.getInstance();
		float t1 = Cutscene.fade(f, 0.1f, 0.6f, 0.06f);
		if (t1 > 0) {
			Component name = caps(BIG, Component.translatable("halloween.hero.name." + hero));
			g.text(this.font, name, cx - this.font.width(name) / 2, y1 - bar - 40, Cutscene.alpha(t1, 0xE8E2D4), false);
		}
		float t2 = Cutscene.fade(f, 0.16f, 0.62f, 0.06f);
		if (t2 > 0) {
			Component line = HeroInfo.killer(hero) ? Component.translatable("halloween.heroinfo." + hero + ".quote")
				: Component.translatable("halloween.hero." + hero);
			Cutscene.bigText(g, mc, line, cx, y1 - bar - 12, Math.min(1f, (x1 - x0 - 10f) / this.font.width(line)), Cutscene.alpha(t2, 0xC8C0B0));
		}
		float t3 = Cutscene.fade(f, 0.68f, 0.9f, 0.05f);
		if (t3 > 0) {
			Component end = Component.translatable(HeroInfo.killer(hero) ? "halloween.select.scene_hunt" : "halloween.cutscene.survivor");
			Cutscene.bigText(g, mc, end, cx, y1 - bar - 12, Math.min(1f, (x1 - x0 - 10f) / this.font.width(end)), Cutscene.alpha(t3, 0xE8E2D4));
		}
	}

	private void card(GuiGraphicsExtractor g) {
		int x0 = this.cardX0;
		int x1 = this.cardX1;
		int y0 = this.top + 2;
		int y1 = this.bottom;
		this.panel(g, x0, y0, x1, y1);
		String hero = this.current();
		float a = this.cardAlpha;
		int acc = this.pal(ACCENT);
		int acc2 = this.pal(ACCENT2);
		int w = x1 - x0 - 18;
		int x = x0 + 9;
		int right = x1 - 9;
		this.cardScroll = Mth.clamp(this.cardScroll, 0, Math.max(0, this.cardHeight - (y1 - y0 - 12)));
		g.enableScissor(x0 + 1, y0 + 1, x1 - 1, y1 - 1);
		// Coming in: from a little lower, fading up.
		int start = y0 + 8 - this.cardScroll + Math.round((1 - a) * 6);
		int y = start;
		String key = "halloween.heroinfo." + hero;
		y = this.section(g, Component.translatable(HeroInfo.killer(hero) ? "halloween.select.card.who_killer" : "halloween.select.card.who_" + pronoun(hero)),
			Component.translatable(key + ".story"), x, y, w, TEXT2);
		if (HeroInfo.killer(hero)) {
			this.label(g, Component.translatable("halloween.select.card.abilities"), x, y, right, a);
			y += 12;
			for (String[] ab : abilities(hero)) {
				KeyMapping km = keyFor(ab[1]);
				Component head = in(DISPLAY, Component.translatable("halloween.heroinfo." + hero + "." + ab[0]));
				g.text(this.font, head, x, y, fade(TEXT, a), false);
				if (km != null) {
					this.keyBadge(g, km.getTranslatedKeyMessage().getString(), right, y - 2, a);
				}
				y += 12;
				y = this.wrap(g, Component.translatable("halloween.heroinfo." + hero + "." + ab[0] + ".desc"), x, y, w, fade(TEXT2, a)) + 4;
			}
			y += 3;
			// The weak spot in a box of its own, in blood.
			y = this.boxed(g, Component.translatable("halloween.select.card.weakness"), Component.translatable(key + ".weakness"), x, y, w, 0xFFC42A36);
			y = this.section(g, Component.translatable("halloween.select.card.tip"), Component.translatable(key + ".tip"), x, y, w, TEXT2);
			y = this.section(g, Component.translatable("halloween.select.card.prey"), Component.translatable(key + ".prey"), x, y, w, TEXT2);
			// Their words, set off with a bar.
			int qy = y;
			y = this.wrap(g, Component.literal("«").append(Component.translatable(key + ".quote")).append("»").withStyle(ChatFormatting.ITALIC), x + 6, y + 1,
				w - 6, fade(TEXT, a)) + 1;
			g.fill(x, qy, x + 2, y, fade(acc, a));
			y += 8;
		} else {
			Heroes.Skill skill = Heroes.skill(hero);
			if (skill != Heroes.Skill.NONE) {
				this.label(g, Component.translatable("halloween.select.card.skill_head"), x, y, right, a);
				y += 12;
				g.text(this.font, in(DISPLAY, Component.translatable("halloween.hero.skill." + skill.name().toLowerCase(Locale.ROOT))), x, y - 1, fade(TEXT, a),
					false);
				this.keyBadge(g, HalloweenClient.KEY_SKILL.getTranslatedKeyMessage().getString(), right, y - 2, a);
				y += 13;
				y = this.wrap(g, Component.translatable(key + ".skill"), x, y, w, fade(TEXT2, a)) + 1;
				int[] secs = HeroInfo.skillSeconds(hero);
				Component time = secs[0] > 0 ? Component.translatable("halloween.select.card.skill_time", secs[0], secs[1])
					: Component.translatable("halloween.select.card.skill_rest", secs[1]);
				g.text(this.font, caps(LABEL, time), x, y + 1, fade(acc2, a), false);
				y += 15;
			}
			if (hasTrait(hero)) {
				y = this.section(g, Component.translatable("halloween.select.card.trait"), Component.translatable(key + ".trait"), x, y, w, TEXT2);
			}
		}
		// The bars: lit segment by segment as the hero comes on.
		this.label(g, Component.translatable("halloween.select.card.stats"), x, y, right, a);
		y += 13;
		List<String> stats = HeroInfo.killer(hero) ? HeroInfo.KILLER_STATS : HeroInfo.SURVIVOR_STATS;
		int[] values = HeroInfo.stats(hero);
		int segs = 10;
		int segW = Math.max(4, Math.min(8, w / 2 / segs));
		int barW = segW * segs;
		float now = this.now();
		for (int i = 0; i < stats.size(); i++) {
			g.text(this.font, Component.translatable("halloween.select.stat." + stats.get(i)), x, y, fade(TEXT2, a), false);
			int bx = right - barW;
			float lit = values[i] / 10f * ease((now - this.switchedAt - 2 - i * 1.5f) / 10f);
			for (int sgi = 0; sgi < segs; sgi++) {
				float on = Mth.clamp(lit - sgi, 0f, 1f);
				int sx = bx + sgi * segW;
				blit(g, SEG, sx, y + 1, segW - 1, 6, fade(this.pal(LINE), a));
				if (on > 0) {
					blit(g, SEG, sx, y + 1, segW - 1, 6, fade(sgi == (int) Math.ceil(lit) - 1 ? acc2 : acc, a * on));
				}
			}
			y += 12;
		}
		y += 5;
		// Their record.
		this.label(g, Component.translatable("halloween.select.card.record"), x, y, right, a);
		y += 12;
		List<Integer> r = ClientState.heroRecords.get(hero);
		Component record = r == null || r.isEmpty() || r.get(0) == 0 ? Component.translatable("halloween.select.record.none")
			: Component.translatable(HeroInfo.killer(hero) ? "halloween.select.record.killer" : "halloween.select.record.survivor", r.get(0), r.get(1), r.get(2));
		y = this.wrap(g, record, x, y, w, fade(TEXT2, a)) + 6;
		// What the team lacks.
		if (this.tab == 0) {
			List<String> picked = new ArrayList<>();
			for (String p : ClientState.heroLobby.survivors()) {
				if (!p.isEmpty() && !p.equals(HeroPicks.RANDOM)) {
					picked.add(p);
				}
			}
			String hint = HeroInfo.teamHint(picked);
			if (hint != null) {
				y = this.boxed(g, Component.translatable("halloween.select.card.team"), Component.translatable("halloween.select.hint." + hint), x, y, w, COOL);
			}
		}
		if (!owned(hero)) {
			// Sealed: the lock and the price, above all else here.
			int ly = y;
			g.fill(x, ly, right, ly + 22, fade(ARGB.multiplyAlpha(acc2, 0.14f), a));
			g.fill(x, ly, x + 2, ly + 22, fade(acc2, a));
			blit(g, LOCK, x + 7, ly + 7, 7, 9, fade(acc2, a));
			Component price = in(DISPLAY, Component.translatable("halloween.select.purse", HeroInfo.price(hero)));
			g.text(this.font, price, x + 19, ly + 3, fade(acc2, a), false);
			Component have = caps(LABEL, Component.translatable("halloween.select.have", ClientState.perkPoints));
			g.text(this.font, have, x + 19, ly + 14, fade(TEXT3, a), false);
			y += 28;
		}
		g.disableScissor();
		this.cardHeight = y - start;
		if (this.cardHeight > y1 - y0 - 12) {
			// More below (or above): the text sinks into the glass.
			int glass = ARGB.color(0.97f, this.pal(PANEL));
			if (this.cardScroll < this.cardHeight - (y1 - y0 - 12)) {
				g.fillGradient(x0 + 2, y1 - 18, x1 - 2, y1 - 3, ARGB.transparent(glass), glass);
			}
			if (this.cardScroll > 0) {
				g.fillGradient(x0 + 2, y0 + 3, x1 - 2, y0 + 14, glass, ARGB.transparent(glass));
			}
			int track = y1 - y0 - 16;
			int thumb = Math.max(12, track * track / this.cardHeight);
			int at = y0 + 8 + (track - thumb) * this.cardScroll / Math.max(1, this.cardHeight - (y1 - y0 - 12));
			g.fill(x1 - 4, y0 + 8, x1 - 3, y0 + 8 + track, ARGB.multiplyAlpha(this.pal(LINE), 0.8f));
			g.fill(x1 - 4, at, x1 - 3, at + thumb, ARGB.multiplyAlpha(acc, 0.85f));
		}
	}

	/** The key that works it, in a small box at the right. */
	private void keyBadge(GuiGraphicsExtractor g, String key, int right, int y, float a) {
		Component k = in(LABEL, Component.literal(key.toUpperCase(Locale.ROOT)));
		int kw = Math.max(9, this.font.width(k) + 6);
		int acc = this.pal(ACCENT);
		g.fill(right - kw, y, right, y + 11, fade(ARGB.multiplyAlpha(acc, 0.18f), a));
		g.outline(right - kw, y, kw, 11, fade(acc, a));
		g.text(this.font, k, right - kw + (kw - this.font.width(k)) / 2, y + 2, fade(this.pal(ACCENT2), a), false);
	}

	private static boolean hasTrait(String hero) {
		return Heroes.innate(hero) != null || hero.equals("alexis") || hero.equals("marcus");
	}

	/** A killer's powers: the lang key under halloween.heroinfo.{killer}, and which key works it. */
	private static List<String[]> abilities(String hero) {
		return switch (hero) {
			case "demogorgon" -> List.<String[]>of(new String[]{"upside", "jump"}, new String[]{"shred", "sprint"}, new String[]{"impulse", "impulse"});
			case "vecna" -> List.<String[]>of(new String[]{"grip", "sense"}, new String[]{"eyes", "jump"}, new String[]{"anchor", "impulse"});
			default -> List.<String[]>of(new String[]{"sense", "sense"}, new String[]{"jump", "jump"}, new String[]{"dash", "sprint"},
				new String[]{"impulse", "impulse"});
		};
	}

	private static KeyMapping keyFor(String which) {
		return switch (which) {
			case "sense" -> HalloweenClient.KEY_SENSE;
			case "jump" -> HalloweenClient.KEY_SHAPE_JUMP;
			case "impulse" -> HalloweenClient.KEY_IMPULSE;
			case "sprint" -> Minecraft.getInstance().options.keySprint;
			default -> null;
		};
	}

	private int section(GuiGraphicsExtractor g, Component title, Component body, int x, int y, int w, int color) {
		this.label(g, title, x, y, x + w, this.cardAlpha);
		return this.wrap(g, body, x, y + 12, w, fade(color, this.cardAlpha)) + 8;
	}

	/** A section in a tinted box with a bar down its left: the weak spot, what the team lacks. */
	private int boxed(GuiGraphicsExtractor g, Component title, Component body, int x, int y, int w, int color) {
		float a = this.cardAlpha;
		List<FormattedCharSequence> lines = this.font.split(body, w - 10);
		int h = 16 + lines.size() * 10 + 2;
		g.fill(x, y, x + w, y + h, fade(ARGB.multiplyAlpha(color, 0.14f), a));
		g.fill(x, y, x + 2, y + h, fade(color, a));
		this.tracked(g, title.getString().toUpperCase(Locale.ROOT), LABEL, x + 7, y + 4, fade(color, a), 0.6f);
		int ly = y + 15;
		for (FormattedCharSequence line : lines) {
			g.text(this.font, line, x + 7, ly, fade(TEXT, a), false);
			ly += 10;
		}
		return y + h + 8;
	}

	private int wrap(GuiGraphicsExtractor g, Component text, int x, int y, int w, int color) {
		for (FormattedCharSequence line : this.font.split(text, w)) {
			g.text(this.font, line, x, y, color, false);
			y += 10;
		}
		return y;
	}

	private void bottomBar(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int y0 = this.barY;
		int y1 = this.height - 7;
		int acc = this.pal(ACCENT);
		int acc2 = this.pal(ACCENT2);
		int bw = Math.min(130, Math.max(96, this.width / 6));
		int px1 = this.cardX1 - bw - 8;
		this.panel(g, this.listX0, y0, px1, y1);
		// The perks: how many, and the purse, in the accent.
		String head = Component.translatable("halloween.select.perks", ClientState.perkEquipped.size(), ClientState.perkSlots).getString()
			.toUpperCase(Locale.ROOT);
		float hw = this.tracked(g, head, LABEL, this.listX0 + 9, y0 + 5, TEXT3, 0.6f);
		Component purse = caps(DISPLAY, Component.translatable("halloween.select.purse", ClientState.perkPoints));
		int pxs = Math.round(this.listX0 + 9 + hw + 6);
		g.text(this.font, purse, pxs, y0 + 3, acc2, false);
		int rule = pxs + this.font.width(purse) + 5;
		if (px1 - 9 - rule > 6) {
			g.fill(rule, y0 + 9, px1 - 9, y0 + 10, ARGB.multiplyAlpha(this.pal(LINE2), 0.8f));
		}
		int slots = Math.max(1, ClientState.perkSlots);
		int sx = this.listX0 + 9;
		int sw = (px1 - this.listX0 - 18 - (slots - 1) * 5) / slots;
		int sy = y0 + 15;
		int sh = y1 - sy - 5;
		for (int i = 0; i < slots; i++) {
			int x = sx + i * (sw + 5);
			boolean hover = mouseX >= x && mouseX < x + sw && mouseY >= sy && mouseY < sy + sh;
			boolean has = i < ClientState.perkEquipped.size();
			g.fill(x, sy, x + sw, sy + sh, hover ? 0x30FFFFFF : 0x60000000);
			g.outline(x, sy, sw, sh, hover ? acc : has ? ARGB.multiplyAlpha(acc, 0.45f) : this.pal(LINE));
			// The medallion: lit when there's a perk in it, dashes and a cross when not.
			int ms = Math.min(sh - 2, 14);
			int mx = x + 3;
			int my = sy + (sh - ms) / 2;
			if (has) {
				blitAt(g, GLOW, mx - 5, my - 5, ms + 10, ms + 10, ARGB.multiplyAlpha(acc, 0.5f));
				blit(g, DIAMOND_FILL, mx, my, ms, ms, acc);
				blit(g, DIAMOND_LINE, mx, my, ms, ms, acc2);
			} else {
				blit(g, DIAMOND_EMPTY, mx, my, ms, ms, hover ? acc2 : TEXT3);
			}
			Component name = has ? Component.translatable("halloween.perk." + ClientState.perkEquipped.get(i)) : Component.translatable("halloween.select.slot_empty");
			String cut = this.font.plainSubstrByWidth(name.getString(), sw - ms - 9);
			g.text(this.font, cut, mx + ms + 4, sy + (sh - 8) / 2, has ? TEXT : TEXT3, false);
			this.hits.add(new Hit(x, sy, x + sw, sy + sh, () -> {
				click();
				Minecraft.getInstance().gui.setScreen(new PerkScreen(this));
			}));
		}
		// Ready: the brightest thing on the screen, breathing; once ready it offers to take it back.
		int bx = this.cardX1 - bw;
		boolean ready = myReady();
		int rh = 21;
		float pulse = 0.5f + 0.5f * Mth.sin(this.now() * 0.15f);
		if (between() && !ready) {
			blitAt(g, GLOW, bx - 18, y0 - 14, bw + 36, rh + 28, ARGB.color(0.30f + 0.22f * pulse, acc));
		}
		this.button(g, bx, y0, bw, rh, Component.translatable(ready ? "halloween.select.ready_on" : "halloween.select.ready"), between(),
			ready ? Kind.PICK : Kind.MAIN, mouseX, mouseY, () -> {
				this.send(HeroPicks.READY, "");
				click();
			});
		this.button(g, bx + 8, y0 + rh + 3, bw - 16, y1 - y0 - rh - 3, Component.translatable("halloween.select.practice"), between(), Kind.MINOR, mouseX,
			mouseY, () -> {
				this.send(HeroPicks.PRACTICE, "");
				click();
				this.onClose();
			});
	}

	// ---------------------------------------------------------------- input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		for (int i = this.hits.size() - 1; i >= 0; i--) {
			Hit h = this.hits.get(i);
			if (h.in(event.x(), event.y())) {
				h.action().run();
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (this.dragging) {
			this.yaw = Mth.wrapDegrees(this.yaw - (float) dx * 2.2f);
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		this.dragging = false;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		int step = (int) Math.signum(scrollY);
		if (x >= this.cardX0) {
			this.cardScroll -= step * 20;
		} else if (x < this.listX1) {
			this.listScroll -= step;
		} else {
			this.show(this.index - step);
		}
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		switch (event.key()) {
			case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_W -> {
				this.show(this.index - 1);
				return true;
			}
			case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_D, GLFW.GLFW_KEY_S -> {
				this.show(this.index + 1);
				return true;
			}
			case GLFW.GLFW_KEY_TAB -> {
				this.setTab(1 - this.tab);
				return true;
			}
			case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> {
				this.primary();
				return true;
			}
			default -> {
			}
		}
		if (HalloweenClient.KEY_HEROES.matches(event)) {
			this.onClose();
			return true;
		}
		return super.keyPressed(event);
	}
}
