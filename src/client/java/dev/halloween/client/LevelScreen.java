package dev.halloween.client;

import static dev.halloween.client.CharacterSelectScreen.*;

import dev.halloween.game.PlayerLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

/**
 * The player's level, like a battle pass (key J in the lobby, or the level plate on the character select): the level
 * large in a lit diamond, the bar to the next one, and the road of all ten levels with what each one gives (a thing to wear for the
 * survivors and one for Michael, see game.Cosmetics). Dressed as the character select, on the survivors' side: the street behind, fog and embers,
 * the dark glass panels with cut corners, pumpkin orange.
 */
public final class LevelScreen extends Screen {
	private static final int ACC = ACCENT[0];
	private static final int ACC2 = ACCENT2[0];
	private static final int LN = LINE[0];
	private static final int LN2 = LINE2[0];
	/** The narrowest a level's card gets before the road scrolls. */
	private static final int CARD_MIN = 58;

	/** Where it goes back to on closing (the character select, if it came from there). */
	private final Screen parent;
	private int ticks;
	private float partial;
	private float scroll;
	private float scrollTo = -1;
	private int maxScroll;
	private final List<Hit> hits = new ArrayList<>();
	/** The level whose card is under the mouse (0: none). */
	private int hoverLevel;
	/** For tests: this level's window shown as if hovered. */
	public int testHover;

	private record Hit(int x0, int y0, int x1, int y1, Runnable action) {
		boolean in(double x, double y) {
			return x >= this.x0 && x < this.x1 && y >= this.y0 && y < this.y1;
		}
	}

	public LevelScreen(Screen parent) {
		super(Component.translatable("halloween.level.title"));
		this.parent = parent;
	}

	/** Only in the lobby between matches. */
	public static boolean available() {
		return !ClientState.inMatch() && ClientState.lobby.inLobby() && ClientState.match.state() == 0;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void tick() {
		this.ticks++;
		if (!available() && this.parent == null) {
			this.onClose();
		}
	}

	@Override
	public void removed() {
		CosmeticRenderer.tryOn = null;
	}

	@Override
	public void onClose() {
		CosmeticRenderer.tryOn = null;
		Minecraft.getInstance().gui.setScreen(this.parent);
	}

	private float now() {
		return this.ticks + this.partial;
	}

	/** 12400 as "12 400". */
	static String number(int n) {
		return String.format(Locale.ROOT, "%,d", n).replace(',', ' ');
	}

	// ---------------------------------------------------------------- drawing

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		this.partial = partial;
		street(g, this.width, this.height, this.now(), mouseX);
	}

	/** The select's night street out of focus, fog, embers and the vignette (also behind the settings). */
	static void street(GuiGraphicsExtractor g, int w, int h, float now, int mouseX) {
		// The select's night street, far behind and out of focus.
		g.fill(0, 0, w, h, BG0[0]);
		float cover = Math.max(w / 1024f, h / 512f) * 1.06f;
		float drift = (mouseX - w / 2f) / w * -10f;
		g.pose().pushMatrix();
		g.pose().translate((w - 1024 * cover) / 2f + drift, (h - 512 * cover) / 2f - h * 0.04f);
		g.pose().scale(cover / 4f, cover / 4f);
		blit(g, BACKDROP, 0, 0, 1024 * 4, 512 * 4, ARGB.multiplyAlpha(STREET[0], 0.85f));
		g.pose().popMatrix();
		// The lantern's light over the emblem, flickering a little.
		float flicker = 0.9f + 0.06f * Mth.sin(now * 0.21f) + 0.04f * Mth.sin(now * 0.73f + 1.3f);
		blitAt(g, GLOW, w * 0.5f - w * 0.6f, -h * 0.25f, w * 1.2f, h * 0.9f, ARGB.color(0.14f * flicker, ACC2));
		int fy = (int) (h * 0.55f);
		g.blit(RenderPipelines.GUI_TEXTURED, FOG.id(), 0, fy, (now * 0.5f) % FOG.w(), 0, w, h - fy, FOG.w(), FOG.h(), FOG.w(), FOG.h(),
			ARGB.color(0.10f, 0xFFB8C4D0));
		g.blit(RenderPipelines.GUI_TEXTURED, FOG.id(), 0, fy + h / 10, FOG.w() - (now * 0.3f) % FOG.w(), 0, w, h - fy - h / 10, FOG.w() * 3 / 2,
			FOG.h(), FOG.w(), FOG.h(), ARGB.color(0.08f, 0xFFB8C4D0));
		// Embers rising.
		for (int i = 0; i < 22; i++) {
			float speed = 0.25f + rnd(i, 1) * 0.35f;
			float life = h * 0.85f / speed;
			float t = (now + rnd(i, 2) * life) % life;
			float f = t / life;
			float x = w * (0.04f + 0.92f * rnd(i, 3)) + Mth.sin(now * 0.03f + i) * 12 * f;
			float y = h * 0.98f - t * speed;
			float a = Mth.sin(f * Mth.PI) * (0.55f + 0.45f * Mth.sin(now * 0.31f + i * 1.7f)) * 0.75f;
			float s = 1.6f + rnd(i, 4) * 2.2f;
			blitAt(g, GLOW, x - s, y - s, s * 2, s * 2, ARGB.color(a, ACC2));
			blitAt(g, GLOW, x - s * 0.3f, y - s * 0.3f, s * 0.6f, s * 0.6f, ARGB.color(a, 0xFFFFE6C0));
		}
		blit(g, VIGNETTE, 0, 0, w, h, 0xF0FFFFFF);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		this.hits.clear();
		this.hoverLevel = 0;
		this.partial = partial;
		int m = 8;
		int earned = ClientState.earned;
		int level = PlayerLevel.of(earned);
		this.header(g, m, earned);
		int y = this.summary(g, m, 32, earned, level, mouseX, mouseY);
		this.road(g, m, y + 4, this.height - 40, earned, level, mouseX, mouseY);
		this.bottomBar(g, m, mouseX, mouseY);
		super.extractRenderState(g, mouseX, mouseY, partial);
		if (this.testHover > 0) {
			this.tooltip(g, this.testHover, this.width / 3, this.height / 2, partial);
		} else if (this.hoverLevel > 0) {
			this.tooltip(g, this.hoverLevel, mouseX, mouseY, partial);
		} else {
			CosmeticRenderer.tryOn = null;
		}
		// Film grain over it all, as on the select.
		int seed = this.ticks / 2;
		g.blit(RenderPipelines.GUI_TEXTURED, GRAIN.id(), 0, 0, rnd(seed, 7) * GRAIN.w(), rnd(seed, 8) * GRAIN.h(), this.width, this.height,
			this.width * 2, this.height * 2, GRAIN.w(), GRAIN.h(), 0x0DFFFFFF);
	}

	private void panel(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1) {
		nine(g, PANEL_FILL, x0, y0, x1 - x0, y1 - y0, 9, PANEL[0]);
		nine(g, PANEL_LINE, x0, y0, x1 - x0, y1 - y0, 9, LN2);
		nine(g, PANEL_CORNER, x0, y0, x1 - x0, y1 - y0, 9, ARGB.multiplyAlpha(ACC, 0.9f));
	}

	/** Letters spaced out (the small titles); returns the width. */
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

	/** A small title with a rule running on to the right. */
	private void label(GuiGraphicsExtractor g, Component text, int x, int y, int right) {
		float w = this.tracked(g, text.getString().toUpperCase(Locale.ROOT), LABEL, x, y, TEXT3, 0.6f);
		int rx = Math.round(x + w + 4);
		if (right - rx > 6) {
			g.fill(rx, y + 4, right, y + 5, ARGB.multiplyAlpha(LN2, 0.8f));
		}
	}

	private void header(GuiGraphicsExtractor g, int m, int earned) {
		// The title as the select's lit tab.
		Component name = caps(DISPLAY, Component.translatable("halloween.level.title"));
		int w = this.font.width(name) + 22;
		blitAt(g, GLOW, m - 10, -2, w + 20, 32, ARGB.multiplyAlpha(ACC, 0.35f));
		three(g, BTN_FILL, m, 6, w, 16, ACC);
		three(g, BTN_LINE, m, 6, w, 16, ACC2);
		g.text(this.font, name, m + 11, 10, DARK_TEXT, false);
		// On the right: every point earned, and the purse (what is left to spend).
		int rx = this.width - m;
		Component total = in(DISPLAY, Component.literal(number(earned)));
		rx -= this.font.width(total);
		g.text(this.font, total, rx, 9, ACC2, false);
		String tl = Component.translatable("halloween.level.earned").getString().toUpperCase(Locale.ROOT);
		rx -= 5 + Math.round(this.trackedWidth(tl, LABEL, 0.6f));
		this.tracked(g, tl, LABEL, rx, 11, TEXT2, 0.6f);
		Component purse = in(DISPLAY, Component.literal(number(ClientState.perkPoints)));
		rx -= 14 + this.font.width(purse);
		g.text(this.font, purse, rx, 9, TEXT, false);
		String pl = Component.translatable("halloween.level.purse").getString().toUpperCase(Locale.ROOT);
		rx -= 5 + Math.round(this.trackedWidth(pl, LABEL, 0.6f));
		this.tracked(g, pl, LABEL, rx, 11, TEXT2, 0.6f);
		g.fill(m, 25, this.width - m, 26, ARGB.multiplyAlpha(LN, 0.9f));
		blit(g, GLOW_LINE, m, 23, this.width - 2 * m, 5, ARGB.multiplyAlpha(ACC, 0.5f));
	}

	/** The level large in its diamond, and the bar to the next one. Returns the panel's bottom. */
	private int summary(GuiGraphicsExtractor g, int m, int y0, int earned, int level, int mouseX, int mouseY) {
		int x0 = m;
		int x1 = this.width - m;
		// Roomier on a tall screen; on a small one the road needs the height.
		boolean roomy = this.height >= 300;
		int y1 = y0 + (roomy ? 62 : 46);
		this.panel(g, x0, y0, x1, y1);
		float now = this.now();
		float open = ease(now / 14f);
		// The emblem: a lit diamond breathing, the number in it.
		int ds = roomy ? 50 : 38;
		int dx = x0 + 12;
		int dy = y0 + (y1 - y0 - ds) / 2;
		float pulse = 0.5f + 0.5f * Mth.sin(now * 0.12f);
		blitAt(g, GLOW, dx - 22, dy - 22, ds + 44, ds + 44, ARGB.color(0.35f + 0.2f * pulse, ACC));
		blit(g, DIAMOND_FILL, dx, dy, ds, ds, ARGB.srgbLerp(0.25f, ACC, 0xFF2A1406));
		blit(g, DIAMOND_LINE, dx, dy, ds, ds, ACC2);
		blit(g, DIAMOND_LINE, dx + 5, dy + 5, ds - 10, ds - 10, ARGB.multiplyAlpha(ACC2, 0.45f));
		Component num = in(BIG, Component.literal(Integer.toString(level)));
		float ns = roomy ? 1f : 0.8f;
		g.pose().pushMatrix();
		g.pose().translate(dx + ds / 2f, dy + ds / 2f - 10 * ns);
		g.pose().scale(ns, ns);
		g.text(this.font, num, -this.font.width(num) / 2, 0, TEXT, false);
		g.pose().popMatrix();
		// The name of it and the bar.
		int tx = dx + ds + 14;
		int tw = x1 - 14 - tx;
		int ty = y0 + 8;
		if (roomy) {
			this.tracked(g, Component.translatable("halloween.level.player").getString().toUpperCase(Locale.ROOT), LABEL, tx, y0 + 9, TEXT3, 0.6f);
			ty = y0 + 17;
		}
		Component title = caps(DISPLAY, Component.translatable("halloween.level.n", level, PlayerLevel.MAX));
		g.text(this.font, title, tx, ty, TEXT, false);
		boolean max = level >= PlayerLevel.MAX;
		Component right = max ? caps(DISPLAY, Component.translatable("halloween.level.max"))
			: in(DISPLAY, Component.translatable("halloween.level.of", number(earned - PlayerLevel.total(level)), number(PlayerLevel.cost(level + 1))));
		g.text(this.font, right, x1 - 14 - this.font.width(right), ty, max ? ACC2 : TEXT2, false);
		int by = ty + 16;
		int bh = roomy ? 8 : 6;
		float k = PlayerLevel.progress(earned) * open;
		g.fill(tx, by, tx + tw, by + bh, 0xC0000000);
		g.outline(tx - 1, by - 1, tw + 2, bh + 2, LN2);
		int fw = Math.round(tw * k);
		if (fw > 0) {
			g.fill(tx, by, tx + fw, by + bh, ACC);
			g.fill(tx, by, tx + fw, by + 2, ACC2);
			// A sheen running along the filled part.
			float run = (now * 2.2f) % (fw + 40) - 20;
			blitAt(g, GLOW, tx + run - 14, by - 6, 28, bh + 12, ARGB.color(0.5f, 0xFFFFE6C0));
			blitAt(g, GLOW, tx + fw - 9, by - 8, 18, bh + 16, ARGB.color(0.55f + 0.3f * pulse, ACC2));
		}
		// Ticks every tenth, as on the select's bars.
		for (int i = 1; i < 10; i++) {
			int x = tx + tw * i / 10;
			g.fill(x, by, x + 1, by + bh, 0x50000000);
		}
		Component sub = max ? Component.translatable("halloween.level.max_sub")
			: Component.translatable("halloween.level.to_next", number(PlayerLevel.total(level + 1) - earned), level + 1);
		g.text(this.font, sub, tx, by + bh + (roomy ? 6 : 4), TEXT2, false);
		return y1;
	}

	/** The ten levels in a row, a track under them lit as far as one has come. */
	private void road(GuiGraphicsExtractor g, int m, int y0, int y1, int earned, int level, int mouseX, int mouseY) {
		int x0 = m;
		int x1 = this.width - m;
		this.panel(g, x0, y0, x1, y1);
		this.label(g, Component.translatable("halloween.level.road"), x0 + 8, y0 + 6, x1 - 8);
		int n = PlayerLevel.MAX;
		int ix0 = x0 + 8;
		int ix1 = x1 - 8;
		int gap = 5;
		int cw = Math.max(CARD_MIN, (ix1 - ix0 - (n - 1) * gap) / n);
		int full = n * cw + (n - 1) * gap;
		this.maxScroll = Math.max(0, full - (ix1 - ix0));
		if (this.scrollTo < 0) {
			// Opened on the level being worked towards.
			int focus = Math.min(level, n - 1);
			this.scrollTo = Mth.clamp(focus * (cw + gap) - (ix1 - ix0 - cw) / 2f, 0, this.maxScroll);
			this.scroll = this.scrollTo;
		}
		this.scrollTo = Mth.clamp(this.scrollTo, 0, this.maxScroll);
		this.scroll = Mth.lerp(0.35f, this.scroll, this.scrollTo);
		int cy0 = y0 + 18;
		int trackY = y1 - 22;
		int cy1 = trackY - 10;
		float now = this.now();
		float pulse = 0.5f + 0.5f * Mth.sin(now * 0.15f);
		g.enableScissor(ix0, y0 + 14, ix1, y1 - 4);
		int base = ix0 - Math.round(this.scroll);
		// The track: dark all along, lit to the reached levels and part way to the next.
		int first = base + cw / 2;
		int last = base + (n - 1) * (cw + gap) + cw / 2;
		g.fill(first, trackY, last, trackY + 2, 0xFF000000 | (LN2 & 0xFFFFFF));
		float lit = (level - 1) + (level >= n ? 0 : PlayerLevel.progress(earned));
		int litX = Math.round(first + (cw + gap) * lit * ease(now / 14f));
		if (litX > first) {
			g.fill(first, trackY, litX, trackY + 2, ACC);
			blit(g, GLOW_LINE, first, trackY - 2, litX - first, 6, ARGB.multiplyAlpha(ACC, 0.6f));
		}
		for (int i = 0; i < n; i++) {
			int lv = i + 1;
			int cx0 = base + i * (cw + gap);
			int cx1 = cx0 + cw;
			if (cx1 < ix0 - 4 || cx0 > ix1 + 4) {
				continue;
			}
			// Coming in one after another on opening.
			float in = ease((now - i * 1.2f) / 8f);
			int lift = Math.round((1 - in) * 10);
			boolean got = lv <= level;
			boolean next = lv == level + 1;
			boolean hover = mouseX >= Math.max(cx0, ix0) && mouseX < Math.min(cx1, ix1) && mouseY >= cy0 && mouseY < cy1;
			this.card(g, cx0, cy0 + lift, cx1, cy1 + lift, lv, got, next, hover, pulse, in);
			if (hover) {
				this.hoverLevel = lv;
			}
			// The node on the track.
			int ns = got ? 9 : next ? 9 : 7;
			int nx = cx0 + cw / 2 - ns / 2;
			int ny = trackY + 1 - ns / 2;
			if (got) {
				blitAt(g, GLOW, nx - 5, ny - 5, ns + 10, ns + 10, ARGB.multiplyAlpha(ACC, 0.5f));
				blit(g, DIAMOND_FILL, nx, ny, ns, ns, ACC);
				blit(g, DIAMOND_LINE, nx, ny, ns, ns, ACC2);
			} else {
				blit(g, DIAMOND_FILL, nx, ny, ns, ns, 0xFF0E1118);
				blit(g, DIAMOND_LINE, nx, ny, ns, ns, next ? ARGB.srgbLerp(pulse, LN2, ACC2) : LN2);
			}
			Component need = in(LABEL, Component.literal(lv == 1 ? "0" : number(PlayerLevel.total(lv))));
			g.text(this.font, need, cx0 + cw / 2 - this.font.width(need) / 2, trackY + 9, got ? TEXT2 : TEXT3, false);
		}
		g.disableScissor();
		// Shade at the edges when there's more to scroll to, and arrows to it.
		if (this.scroll > 1) {
			g.fillGradient(ix0, y0 + 14, ix0 + 16, y1 - 4, 0xC0000000, 0x00000000);
			this.arrow(g, ix0 + 1, (cy0 + cy1) / 2 - 7, CHEV_L, mouseX, mouseY, -1);
		}
		if (this.scroll < this.maxScroll - 1) {
			this.arrow(g, ix1 - 9, (cy0 + cy1) / 2 - 7, CHEV_R, mouseX, mouseY, 1);
		}
	}

	private void arrow(GuiGraphicsExtractor g, int x, int y, Tex chevron, int mouseX, int mouseY, int dir) {
		boolean hover = mouseX >= x - 2 && mouseX < x + 10 && mouseY >= y - 2 && mouseY < y + 16;
		blit(g, chevron, x, y, 8, 14, hover ? ACC2 : TEXT2);
		this.hits.add(new Hit(x - 2, y - 2, x + 10, y + 16, () -> {
			click();
			this.scrollTo += dir * (CARD_MIN + 5) * 3;
		}));
	}

	/** One level's card: its number, the reward (a placeholder for now) and whether it is had, next or ahead. */
	private void card(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int lv, boolean got, boolean next, boolean hover, float pulse, float a) {
		int w = x1 - x0;
		int h = y1 - y0;
		if (next) {
			blitAt(g, GLOW, x0 - 12, y0 - 12, w + 24, h + 24, ARGB.color((0.25f + 0.25f * pulse) * a, ACC));
		}
		int fill = got ? 0xE81A1410 : 0xE00E1118;
		int edge = got ? ARGB.multiplyAlpha(ACC, 0.8f) : next ? ARGB.srgbLerp(pulse, LN2, ACC2) : LN2;
		if (hover) {
			fill = got ? 0xF0261C14 : 0xF0202634;
			edge = ACC2;
		}
		nine(g, PANEL_FILL, x0, y0, w, h, 6, fade(fill, a));
		nine(g, PANEL_LINE, x0, y0, w, h, 6, fade(edge, a));
		if (got || next) {
			nine(g, PANEL_CORNER, x0, y0, w, h, 6, fade(got ? ACC : ACC2, a));
		}
		// The ribbon at the top: the level's number.
		int rh = 13;
		g.fill(x0 + 3, y0 + 3, x1 - 3, y0 + rh, fade(got ? ARGB.multiplyAlpha(ACC, 0.85f) : 0xA0000000, a));
		String head = Component.translatable("halloween.level.card", lv).getString().toUpperCase(Locale.ROOT);
		float hw = this.trackedWidth(head, LABEL, 0.6f);
		this.tracked(g, head, LABEL, x0 + (w - hw) / 2f, y0 + 5, fade(got ? DARK_TEXT : next ? ACC2 : TEXT2, a), 0.6f);
		// The reward: a frame with the names of the two things it opens; locked ones under a lock.
		int fs = Math.min(w - 16, h - rh - 25);
		int fx = x0 + (w - fs) / 2;
		int fy = y0 + rh + 3 + (h - rh - 24 - fs) / 2;
		if (fs > 8) {
			if (got) {
				blitAt(g, GLOW, fx - 6, fy - 6, fs + 12, fs + 12, ARGB.color(0.3f * a, ACC2));
			}
			// The two things it opens, the survivors' and Michael's: their names on a dark plate the width of the card.
			int px0 = x0 + 4;
			int px1 = x1 - 4;
			g.fill(px0, fy, px1, fy + fs, fade(got ? 0x70000000 : 0x90000000, a));
			g.outline(px0, fy, px1 - px0, fs, fade(got ? ARGB.multiplyAlpha(ACC2, 0.7f) : next ? ARGB.srgbLerp(0.5f, LN2, ACC2) : LN2, a));
			dev.halloween.game.Cosmetics.Item si = dev.halloween.game.Cosmetics.at(dev.halloween.game.Cosmetics.SURVIVOR, lv);
			dev.halloween.game.Cosmetics.Item ki = dev.halloween.game.Cosmetics.at(dev.halloween.game.Cosmetics.KILLER, lv);
			if (lv == PlayerLevel.MAX) {
				blitAt(g, GLOW, fx - 4, fy - 4, fs + 8, fs + 8, ARGB.color((0.25f + 0.2f * pulse) * a, 0xFFF2C14E));
			}
			// Smaller on a narrow card.
			float ts = w < 100 ? 0.75f : 1f;
			int lines = 0;
			for (dev.halloween.game.Cosmetics.Item it : new dev.halloween.game.Cosmetics.Item[] {si, ki}) {
				if (it != null) {
					lines += Math.min(2, this.font.split(caps(LABEL, Component.translatable("halloween.cosmetic." + it.id())), Math.round((px1 - px0 - 6) / ts)).size());
				}
			}
			int lh = Math.round(9 * ts);
			int ny = fy + (fs - lines * lh - 3) / 2;
			for (dev.halloween.game.Cosmetics.Item it : new dev.halloween.game.Cosmetics.Item[] {si, ki}) {
				if (it == null) {
					continue;
				}
				List<net.minecraft.util.FormattedCharSequence> rows = this.font.split(caps(LABEL, Component.translatable("halloween.cosmetic." + it.id())),
					Math.round((px1 - px0 - 6) / ts));
				int col = it.track() == 0 ? (got ? ACC2 : TEXT2) : (got ? ACCENT2[1] : TEXT3);
				for (int r = 0; r < Math.min(2, rows.size()); r++) {
					g.pose().pushMatrix();
					g.pose().translate((px0 + px1) / 2f, ny);
					g.pose().scale(ts, ts);
					g.centeredText(this.font, rows.get(r), 0, 0, fade(col, a));
					g.pose().popMatrix();
					ny += lh;
				}
				ny += 3;
			}
			if (!got) {
				int ls = Math.max(5, Math.min(9, fs / 5));
				blit(g, LOCK, px1 - ls - 2, fy + 2, ls, ls * 9 / 7, fade(next ? ACC2 : TEXT3, a));
			}
		}
		// Under it: what kind (legendary at the top), and its state.
		Component what = caps(LABEL, Component.translatable("halloween.level.reward_two"));
		String cut = this.font.plainSubstrByWidth(what.getString(), w - 6);
		Component line = in(LABEL, Component.literal(cut));
		g.text(this.font, line, x0 + (w - this.font.width(line)) / 2, y1 - 19, fade(got ? TEXT : TEXT2, a), false);
		Component state = caps(LABEL, Component.translatable(got ? "halloween.level.got" : next ? "halloween.level.next" : "halloween.level.locked"));
		g.text(this.font, state, x0 + (w - this.font.width(state)) / 2, y1 - 10, fade(got ? COOL : next ? ACC2 : TEXT3, a), false);
	}

	/**
	 * The level under the mouse: a small window by the cursor with its two things on the one wearing them (a
	 * survivor and Michael), turning slowly, each named in its colour (gold for the legendary) with its slot.
	 */
	private void tooltip(GuiGraphicsExtractor g, int lv, int mouseX, int mouseY, float partial) {
		dev.halloween.game.Cosmetics.Item si = dev.halloween.game.Cosmetics.at(dev.halloween.game.Cosmetics.SURVIVOR, lv);
		dev.halloween.game.Cosmetics.Item ki = dev.halloween.game.Cosmetics.at(dev.halloween.game.Cosmetics.KILLER, lv);
		if (si == null || ki == null) {
			return;
		}
		CosmeticRenderer.tryOn = List.of(si.id(), ki.id());
		boolean legend = lv == PlayerLevel.MAX;
		int gold = 0xFFF2C14E;
		int cw = 84;
		int w = cw * 2 + 12;
		int h = 128;
		int x0 = mouseX + 12;
		int y0 = mouseY - h / 2;
		if (x0 + w > this.width - 4) {
			x0 = mouseX - 12 - w;
		}
		y0 = Mth.clamp(y0, 30, this.height - h - 4);
		g.pose().pushMatrix();
		blitAt(g, GLOW, x0 - 10, y0 - 10, w + 20, h + 20, ARGB.color(0.35f, legend ? gold : ACC));
		g.fill(x0 + 2, y0 + 2, x0 + w - 2, y0 + h - 2, 0xFF0A0C12);
		nine(g, PANEL_FILL, x0, y0, w, h, 9, 0xF40C0E16);
		nine(g, PANEL_LINE, x0, y0, w, h, 9, legend ? gold : LN2);
		nine(g, PANEL_CORNER, x0, y0, w, h, 9, legend ? gold : ACC);
		String head = Component.translatable("halloween.level.card", lv).getString().toUpperCase(Locale.ROOT);
		this.tracked(g, head, LABEL, x0 + 8, y0 + 6, legend ? gold : TEXT3, 0.6f);
		float now = this.now();
		dev.halloween.game.Cosmetics.Item[] items = {si, ki};
		for (int i = 0; i < 2; i++) {
			dev.halloween.game.Cosmetics.Item it = items[i];
			int cx0 = x0 + 6 + i * (cw + 0);
			int cy0 = y0 + 16;
			int cy1 = y0 + h - 28;
			int col = it.legendary() ? gold : it.track() == 0 ? ACC2 : ACCENT2[1];
			g.fill(cx0, cy0, cx0 + cw - 2, cy1, 0x90000000);
			blitAt(g, GLOW, cx0, cy1 - 30, cw - 2, 34, ARGB.color(0.25f, it.track() == 0 ? ACC : ACCENT[1]));
			String who = it.track() == 0 ? "laurie" : "michael";
			// Turned to show it: the back for what goes on the back.
			boolean behind = it.slot() == dev.halloween.game.Cosmetics.Slot.BACK || it.slot() == dev.halloween.game.Cosmetics.Slot.CLOAK;
			float yaw = (behind ? 150f : 30f) + Mth.sin(now * 0.04f) * 35f;
			net.minecraft.client.renderer.entity.state.AvatarRenderState s = HeroPreview.state(2 + i, who, yaw, false, now, -1,
				HeroPreview.item(who, who.equals("michael") ? 0 : 3), partial);
			if (s != null) {
				float height = who.equals("michael") ? 2.1f : 1.8f;
				int bh = cy1 - cy0;
				float scale = (bh - 8) / (height * 1.3f);
				org.joml.Quaternionf tilt = new org.joml.Quaternionf().rotateX(-6f * Mth.DEG_TO_RAD);
				g.entity(s, scale, new org.joml.Vector3f(0f, (bh / 2f - 3f) / scale, 0f), new org.joml.Quaternionf().rotateZ(Mth.PI).mul(tilt), tilt,
					cx0, cy0, cx0 + cw - 2, cy1);
			}
			List<net.minecraft.util.FormattedCharSequence> rows = this.font.split(caps(LABEL, Component.translatable("halloween.cosmetic." + it.id())),
				Math.round((cw - 6) / 0.75f));
			int ny = cy1 + 3;
			for (int r = 0; r < Math.min(2, rows.size()); r++) {
				g.pose().pushMatrix();
				g.pose().translate(cx0 + (cw - 2) / 2f, ny);
				g.pose().scale(0.75f, 0.75f);
				g.centeredText(this.font, rows.get(r), 0, 0, col);
				g.pose().popMatrix();
				ny += 7;
			}
			Component slot = caps(LABEL, Component.translatable(it.legendary() ? "halloween.cosmetic.legendary" : "halloween.cosmetic.slot." + it.slot().key()));
			g.pose().pushMatrix();
			g.pose().translate(cx0 + (cw - 2) / 2f, y0 + h - 9);
			g.pose().scale(0.7f, 0.7f);
			g.centeredText(this.font, slot, 0, 0, it.legendary() ? gold : TEXT3);
			g.pose().popMatrix();
		}
		g.pose().popMatrix();
	}

	private void bottomBar(GuiGraphicsExtractor g, int m, int mouseX, int mouseY) {
		int y0 = this.height - 36;
		int y1 = this.height - 7;
		int bw = Math.min(130, Math.max(96, this.width / 6));
		int px1 = this.width - m - bw - 8;
		this.panel(g, m, y0, px1, y1);
		// How to raise it, wrapped to two lines; a little flame on the left.
		blitAt(g, GLOW, m + 3, y0 + 5, 18, 18, ARGB.multiplyAlpha(ACC, 0.6f));
		blit(g, DIAMOND_FILL, m + 8, y0 + 10, 8, 8, ACC);
		List<net.minecraft.util.FormattedCharSequence> lines = this.font.split(Component.translatable("halloween.level.hint"), px1 - m - 30);
		int ly = y0 + (lines.size() > 1 ? 5 : 10);
		for (int i = 0; i < Math.min(2, lines.size()); i++) {
			g.text(this.font, lines.get(i), m + 22, ly + i * 10, TEXT2, false);
		}
		// Close: the select's minor slanted button.
		int bx = this.width - m - bw;
		boolean hover = mouseX >= bx && mouseX < bx + bw && mouseY >= y0 && mouseY < y1;
		three(g, BTN_FILL, bx, y0, bw, y1 - y0, hover ? 0xF0202634 : 0xE00E1118);
		three(g, BTN_LINE, bx, y0, bw, y1 - y0, hover ? ACC : LN2);
		Component label = caps(DISPLAY, Component.translatable(this.parent != null ? "halloween.level.back" : "halloween.level.close"));
		g.text(this.font, label, bx + (bw - this.font.width(label)) / 2, y0 + (y1 - y0 - 8) / 2, hover ? TEXT : TEXT2, false);
		this.hits.add(new Hit(bx, y0, bx + bw, y1, () -> {
			click();
			this.onClose();
		}));
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
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		this.scrollTo -= (float) (scrollY + scrollX) * 40;
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		switch (event.key()) {
			case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_A -> {
				this.scrollTo -= CARD_MIN + 5;
				return true;
			}
			case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_D -> {
				this.scrollTo += CARD_MIN + 5;
				return true;
			}
			default -> {
			}
		}
		if (HalloweenClient.KEY_LEVEL.matches(event)) {
			this.onClose();
			return true;
		}
		return super.keyPressed(event);
	}
}
