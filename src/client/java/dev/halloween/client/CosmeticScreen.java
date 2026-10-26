package dev.halloween.client;

import static dev.halloween.client.CharacterSelectScreen.*;

import dev.halloween.game.Cosmetics;
import dev.halloween.game.HeroInfo;
import dev.halloween.game.PlayerLevel;
import dev.halloween.net.HwNet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

/**
 * What one wears (game.Cosmetics), from the character select's "Look" button: the survivors' things or Michael's
 * (the tab), the slots on the left with what is in each, the model in the middle wearing it all (and whatever the
 * mouse is over, to try it on), the ten things on the right, one a level, each to put on or take off; those not
 * reached yet are shut, with the level that opens them. Dressed as the select: the street, the dark glass panels,
 * pumpkin orange for the survivors and blood red for Michael; the legendary ones in gold.
 */
public final class CosmeticScreen extends Screen {
	private static final int GOLD = 0xFFF2C14E;
	private static final int GOLD2 = 0xFFFFE6A0;
	private static int lastTrack;

	private final Screen parent;
	private final String survivor;
	private int track;
	/** Which slot the list shows (-1: all of this track's). */
	private Cosmetics.Slot filter;
	private int ticks;
	private float partial;
	private float yaw = 20f;
	private boolean dragging;
	private boolean walk;
	/** For tests: not turning by itself. */
	private boolean still;
	private String hover;
	private String testHover;
	private float switchedAt;
	private final List<Hit> hits = new ArrayList<>();

	private record Hit(int x0, int y0, int x1, int y1, Runnable action) {
		boolean in(double x, double y) {
			return x >= this.x0 && x < this.x1 && y >= this.y0 && y < this.y1;
		}
	}

	public CosmeticScreen(Screen parent, String hero) {
		super(Component.translatable("halloween.cosmetic.title"));
		this.parent = parent;
		this.survivor = hero != null && !HeroInfo.killer(hero) && !hero.equals(dev.halloween.game.HeroPicks.RANDOM) ? hero : "laurie";
		this.track = hero != null && hero.equals("michael") ? Cosmetics.KILLER : lastTrack;
	}

	/** For tests: show this track, maybe trying this thing on. */
	public void showForTest(int track, String tryOn) {
		this.setTrack(track);
		this.testHover = tryOn;
		Cosmetics.Item i = Cosmetics.byId(tryOn);
		boolean behind = i != null && (i.slot() == Cosmetics.Slot.BACK || i.slot() == Cosmetics.Slot.CLOAK || i.slot() == Cosmetics.Slot.AURA);
		this.yaw = behind ? 150f : 35f;
		this.still = true;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void tick() {
		this.ticks++;
		if (!this.dragging && !this.still) {
			this.yaw += 0.6f;
		}
	}

	@Override
	public void onClose() {
		CosmeticRenderer.tryOn = null;
		Minecraft.getInstance().gui.setScreen(this.parent);
	}

	@Override
	public void removed() {
		CosmeticRenderer.tryOn = null;
	}

	private float now() {
		return this.ticks + this.partial;
	}

	private void setTrack(int t) {
		if (t != this.track) {
			this.track = t;
			lastTrack = t;
			this.filter = null;
			this.switchedAt = this.now();
			click();
		}
	}

	private int acc() {
		return ACCENT[this.track];
	}

	private int acc2() {
		return ACCENT2[this.track];
	}

	private static int level() {
		return PlayerLevel.of(ClientState.earned);
	}

	private static boolean unlocked(Cosmetics.Item i) {
		return level() >= i.level();
	}

	private static boolean wearing(Cosmetics.Item i) {
		return CosmeticRenderer.mine().contains(i.id());
	}

	private List<Cosmetics.Slot> slots() {
		return this.track == Cosmetics.SURVIVOR ? List.of(Cosmetics.Slot.HEAD, Cosmetics.Slot.BACK, Cosmetics.Slot.PET)
			: List.of(Cosmetics.Slot.HEAD, Cosmetics.Slot.HAND, Cosmetics.Slot.BACK, Cosmetics.Slot.BELT, Cosmetics.Slot.PET, Cosmetics.Slot.CLOAK,
				Cosmetics.Slot.AURA);
	}

	private List<Cosmetics.Item> items() {
		List<Cosmetics.Item> l = new ArrayList<>();
		for (Cosmetics.Item i : Cosmetics.ITEMS) {
			if (i.track() == this.track && (this.filter == null || i.slot() == this.filter)) {
				l.add(i);
			}
		}
		return l;
	}

	private Cosmetics.Item inSlot(Cosmetics.Slot slot) {
		for (String id : CosmeticRenderer.mine()) {
			Cosmetics.Item i = Cosmetics.byId(id);
			if (i != null && i.track() == this.track && i.slot() == slot) {
				return i;
			}
		}
		return null;
	}

	private void toggle(Cosmetics.Item i) {
		if (!unlocked(i)) {
			return;
		}
		click();
		ClientPlayNetworking.send(new HwNet.CosmeticPick(i.id(), !wearing(i)));
	}

	/** What the model shows: what one wears, the thing under the mouse put on in its slot. */
	private List<String> shown() {
		List<String> l = new ArrayList<>(CosmeticRenderer.mine());
		Cosmetics.Item h = this.hover == null ? null : Cosmetics.byId(this.hover);
		if (h != null && !l.contains(h.id())) {
			l.removeIf(id -> {
				Cosmetics.Item o = Cosmetics.byId(id);
				return o != null && o.track() == h.track() && o.slot() == h.slot();
			});
			l.add(h.id());
		}
		return l;
	}

	// ---------------------------------------------------------------- drawing

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		this.partial = partial;
		LevelScreen.street(g, this.width, this.height, this.now(), mouseX);
		if (this.track == Cosmetics.KILLER) {
			g.fill(0, 0, this.width, this.height, 0x30400008);
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		this.hits.clear();
		this.partial = partial;
		this.hover = null;
		int m = 8;
		this.header(g, m, mouseX, mouseY);
		int top = 32;
		int bottom = this.height - 40;
		int leftW = Mth.clamp(this.width / 5, 104, 150);
		int rightW = Mth.clamp(this.width * 2 / 7, 150, 230);
		int lx1 = m + leftW;
		int rx0 = this.width - m - rightW;
		this.slotPanel(g, m, top, lx1, bottom, mouseX, mouseY);
		this.itemPanel(g, rx0, top, this.width - m, bottom, mouseX, mouseY);
		if (this.hover == null) {
			this.hover = this.testHover;
		}
		CosmeticRenderer.tryOn = this.shown();
		this.stage(g, lx1 + 6, top, rx0 - 6, bottom, mouseX, mouseY, partial);
		this.bottomBar(g, m, mouseX, mouseY);
		super.extractRenderState(g, mouseX, mouseY, partial);
		int seed = this.ticks / 2;
		g.blit(RenderPipelines.GUI_TEXTURED, GRAIN.id(), 0, 0, rnd(seed, 7) * GRAIN.w(), rnd(seed, 8) * GRAIN.h(), this.width, this.height,
			this.width * 2, this.height * 2, GRAIN.w(), GRAIN.h(), 0x0DFFFFFF);
	}

	private void panel(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1) {
		nine(g, PANEL_FILL, x0, y0, x1 - x0, y1 - y0, 9, PANEL[this.track]);
		nine(g, PANEL_LINE, x0, y0, x1 - x0, y1 - y0, 9, LINE2[this.track]);
		nine(g, PANEL_CORNER, x0, y0, x1 - x0, y1 - y0, 9, ARGB.multiplyAlpha(this.acc(), 0.9f));
	}

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

	private void label(GuiGraphicsExtractor g, Component text, int x, int y, int right) {
		float w = this.tracked(g, text.getString().toUpperCase(Locale.ROOT), LABEL, x, y, TEXT3, 0.6f);
		int rx = Math.round(x + w + 4);
		if (right - rx > 6) {
			g.fill(rx, y + 4, right, y + 5, ARGB.multiplyAlpha(LINE2[this.track], 0.8f));
		}
	}

	private void header(GuiGraphicsExtractor g, int m, int mouseX, int mouseY) {
		Component name = caps(DISPLAY, Component.translatable("halloween.cosmetic.title"));
		int w = this.font.width(name) + 22;
		blitAt(g, GLOW, m - 10, -2, w + 20, 32, ARGB.multiplyAlpha(this.acc(), 0.35f));
		three(g, BTN_FILL, m, 6, w, 16, this.acc());
		three(g, BTN_LINE, m, 6, w, 16, this.acc2());
		g.text(this.font, name, m + 11, 10, DARK_TEXT, false);
		int x = m + w + 10;
		// The two tracks.
		for (int t = 0; t < 2; t++) {
			Component tn = caps(LABEL, Component.translatable(t == 0 ? "halloween.cosmetic.track.survivors" : "halloween.cosmetic.track.michael"));
			int tw = this.font.width(tn) + 18;
			boolean on = this.track == t;
			boolean hov = !on && mouseX >= x && mouseX < x + tw && mouseY >= 6 && mouseY < 22;
			three(g, BTN_FILL, x, 7, tw, 14, on ? ARGB.srgbLerp(0.55f, ACCENT[t], 0xFF120A06) : hov ? 0xF0202634 : 0xD00E1118);
			three(g, BTN_LINE, x, 7, tw, 14, on ? ACCENT2[t] : LINE2[this.track]);
			g.text(this.font, tn, x + 9, 11, on ? TEXT : hov ? TEXT : TEXT2, false);
			int tt = t;
			this.hits.add(new Hit(x, 5, x + tw, 23, () -> this.setTrack(tt)));
			x += tw + 2;
		}
		// On the right: the level, and how many of the ten are open.
		int lv = level();
		int open = Math.min(lv, PlayerLevel.MAX);
		Component count = in(DISPLAY, Component.literal(open + " / " + PlayerLevel.MAX));
		int rx = this.width - m - this.font.width(count);
		g.text(this.font, count, rx, 9, this.acc2(), false);
		String ol = Component.translatable("halloween.cosmetic.open").getString().toUpperCase(Locale.ROOT);
		rx -= 5 + Math.round(this.trackedWidth(ol));
		this.tracked(g, ol, LABEL, rx, 11, TEXT2, 0.6f);
		Component lvl = in(DISPLAY, Component.literal(Integer.toString(lv)));
		rx -= 16 + this.font.width(lvl);
		blit(g, DIAMOND_FILL, rx - 4, 6, 16, 16, ARGB.srgbLerp(0.25f, this.acc(), 0xFF2A1406));
		blit(g, DIAMOND_LINE, rx - 4, 6, 16, 16, this.acc2());
		g.text(this.font, lvl, rx + 4 - this.font.width(lvl) / 2, 10, TEXT, false);
		g.fill(m, 25, this.width - m, 26, ARGB.multiplyAlpha(LINE[this.track], 0.9f));
		blit(g, GLOW_LINE, m, 23, this.width - 2 * m, 5, ARGB.multiplyAlpha(this.acc(), 0.5f));
	}

	private float trackedWidth(String text) {
		float w = 0;
		for (int i = 0; i < text.length(); ) {
			int cp = text.codePointAt(i);
			w += this.font.width(Component.literal(new String(Character.toChars(cp))).withStyle(s -> s.withFont(LABEL))) + 0.6f;
			i += Character.charCount(cp);
		}
		return Math.max(0, w - 0.6f);
	}

	/** The slots, each with what is in it; one picked shows only its things on the right. */
	private void slotPanel(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int mouseX, int mouseY) {
		this.panel(g, x0, y0, x1, y1);
		this.label(g, Component.translatable("halloween.cosmetic.slots"), x0 + 8, y0 + 6, x1 - 8);
		int y = y0 + 18;
		int rh = Mth.clamp((y1 - y0 - 22) / (this.slots().size() + 1), 12, 30);
		// "All".
		{
			boolean on = this.filter == null;
			boolean hov = mouseX >= x0 + 4 && mouseX < x1 - 4 && mouseY >= y && mouseY < y + rh - 3;
			this.slotRow(g, x0 + 4, y, x1 - 4, y + rh - 3, Component.translatable("halloween.cosmetic.all"), null, on, hov);
			this.hits.add(new Hit(x0 + 4, y, x1 - 4, y + rh - 3, () -> {
				this.filter = null;
				click();
			}));
			y += rh;
		}
		for (Cosmetics.Slot slot : this.slots()) {
			if (y + rh - 3 > y1 - 4) {
				break;
			}
			boolean on = this.filter == slot;
			boolean hov = mouseX >= x0 + 4 && mouseX < x1 - 4 && mouseY >= y && mouseY < y + rh - 3;
			Cosmetics.Item in = this.inSlot(slot);
			this.slotRow(g, x0 + 4, y, x1 - 4, y + rh - 3, Component.translatable("halloween.cosmetic.slot." + slot.key()),
				in == null ? Component.translatable("halloween.cosmetic.empty") : Component.translatable("halloween.cosmetic." + in.id()), on, hov);
			this.hits.add(new Hit(x0 + 4, y, x1 - 4, y + rh - 3, () -> {
				this.filter = this.filter == slot ? null : slot;
				click();
			}));
			y += rh;
		}
	}

	private void slotRow(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, Component name, Component worn, boolean on, boolean hov) {
		int fill = on ? ARGB.srgbLerp(0.7f, this.acc(), 0xFF140C08) : hov ? 0xF0202634 : 0xA0000000;
		nine(g, PANEL_FILL, x0, y0, x1 - x0, y1 - y0, 6, fill);
		nine(g, PANEL_LINE, x0, y0, x1 - x0, y1 - y0, 6, on ? this.acc2() : hov ? this.acc() : LINE2[this.track]);
		blit(g, on ? DIAMOND_FILL : DIAMOND_LINE, x0 + 6, y0 + (y1 - y0) / 2 - 3, 7, 7, on ? this.acc2() : TEXT3);
		Component n = caps(LABEL, name);
		if (worn == null || y1 - y0 < 20) {
			// Low on room: the slot on the left, what is in it on the right.
			g.text(this.font, n, x0 + 17, y0 + (y1 - y0 - 8) / 2, on ? TEXT : TEXT2, false);
			if (worn != null) {
				boolean empty = worn.getString().equals(Component.translatable("halloween.cosmetic.empty").getString());
				String w = worn.getString();
				int max = x1 - x0 - 26 - this.font.width(n);
				if (this.font.width(w) > max) {
					w = this.font.plainSubstrByWidth(w, Math.max(0, max - this.font.width("…"))) + "…";
				}
				g.text(this.font, Component.literal(w), x1 - 5 - this.font.width(w), y0 + (y1 - y0 - 8) / 2, empty ? TEXT3 : this.acc2(), false);
			}
			return;
		}
		g.text(this.font, n, x0 + 17, y0 + 3, on ? TEXT : TEXT2, false);
		String w = worn.getString();
		int max = x1 - x0 - 21;
		if (this.font.width(w) > max) {
			w = this.font.plainSubstrByWidth(w, max - this.font.width("…")) + "…";
		}
		g.text(this.font, Component.literal(w), x0 + 17, y1 - 11, worn.getString().equals(Component.translatable("halloween.cosmetic.empty").getString())
			? TEXT3 : this.acc2(), false);
	}

	/** The things, one a level: a card each to put on or take off; under the mouse, it is tried on. */
	private void itemPanel(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int mouseX, int mouseY) {
		this.panel(g, x0, y0, x1, y1);
		this.label(g, Component.translatable(this.track == 0 ? "halloween.cosmetic.list.survivors" : "halloween.cosmetic.list.michael"), x0 + 8, y0 + 6,
			x1 - 8);
		List<Cosmetics.Item> items = this.items();
		int y = y0 + 18;
		int ch = Mth.clamp((y1 - y - 4) / Math.max(1, items.size()), 12, 30);
		float now = this.now();
		for (int i = 0; i < items.size(); i++) {
			Cosmetics.Item it = items.get(i);
			int cy0 = y + i * ch;
			int cy1 = cy0 + ch - 3;
			if (cy1 > y1 - 4) {
				break;
			}
			float in = ease((now - this.switchedAt - i * 0.8f) / 6f);
			int slide = Math.round((1 - in) * 14);
			boolean hov = mouseX >= x0 + 4 && mouseX < x1 - 4 && mouseY >= cy0 && mouseY < cy1;
			if (hov && unlocked(it)) {
				this.hover = it.id();
			}
			this.card(g, x0 + 4 + slide, cy0, x1 - 4 + slide, cy1, it, hov, in);
			this.hits.add(new Hit(x0 + 4, cy0, x1 - 4, cy1, () -> this.toggle(it)));
		}
	}

	private void card(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, Cosmetics.Item it, boolean hov, float a) {
		boolean open = unlocked(it);
		boolean on = wearing(it);
		boolean legend = it.legendary();
		float pulse = 0.5f + 0.5f * Mth.sin(this.now() * 0.15f);
		int acc = legend ? GOLD : this.acc();
		int acc2 = legend ? GOLD2 : this.acc2();
		if (legend && open) {
			blitAt(g, GLOW, x0 - 8, y0 - 6, x1 - x0 + 16, y1 - y0 + 12, ARGB.color((0.18f + 0.14f * pulse) * a, GOLD));
		}
		int fill = on ? ARGB.srgbLerp(0.72f, acc, 0xFF120A06) : hov ? 0xF0202634 : open ? 0xE0141822 : 0xD00A0C12;
		int edge = on ? acc2 : hov ? acc : legend ? ARGB.multiplyAlpha(GOLD, 0.6f) : LINE2[this.track];
		nine(g, PANEL_FILL, x0, y0, x1 - x0, y1 - y0, 6, fade(fill, a));
		nine(g, PANEL_LINE, x0, y0, x1 - x0, y1 - y0, 6, fade(edge, a));
		if (on || legend) {
			nine(g, PANEL_CORNER, x0, y0, x1 - x0, y1 - y0, 6, fade(acc, a));
		}
		// The level in a diamond on the left.
		int ds = y1 - y0 - 6;
		int dx = x0 + 4;
		int dy = y0 + 3;
		blit(g, DIAMOND_FILL, dx, dy, ds, ds, fade(open ? ARGB.srgbLerp(0.3f, acc, 0xFF2A1406) : 0xFF16181E, a));
		blit(g, DIAMOND_LINE, dx, dy, ds, ds, fade(open ? acc2 : TEXT3, a));
		Component lv = in(LABEL, Component.literal(Integer.toString(it.level())));
		g.text(this.font, lv, dx + ds / 2 - this.font.width(lv) / 2 + 1, dy + ds / 2 - 4, fade(open ? TEXT : TEXT3, a), false);
		int tx = dx + ds + 6;
		Component name = caps(LABEL, Component.translatable("halloween.cosmetic." + it.id()));
		String state = Component.translatable(!open ? "halloween.cosmetic.locked" : on ? "halloween.cosmetic.worn" : "halloween.cosmetic.wear", it.level())
			.getString().toUpperCase(Locale.ROOT);
		int sw = this.font.width(in(LABEL, Component.literal(state)));
		String nm = name.getString();
		int max = x1 - 6 - sw - 6 - tx;
		if (this.font.width(name) > max) {
			nm = this.font.plainSubstrByWidth(nm, max - this.font.width("…")) + "…";
		}
		int twoLine = y1 - y0 >= 22 ? 1 : 0;
		g.text(this.font, in(LABEL, Component.literal(nm)), tx, y0 + (twoLine == 1 ? 4 : (y1 - y0 - 8) / 2), fade(open ? TEXT : TEXT3, a), false);
		if (twoLine == 1) {
			Component sub = legend ? Component.translatable("halloween.cosmetic.legendary")
				: Component.translatable("halloween.cosmetic.slot." + it.slot().key());
			g.text(this.font, caps(LABEL, sub), tx, y1 - 11, fade(legend ? GOLD : TEXT3, a), false);
		}
		g.text(this.font, in(LABEL, Component.literal(state)), x1 - 6 - sw, y0 + (y1 - y0 - 8) / 2,
			fade(!open ? TEXT3 : on ? COOL : hov ? acc2 : TEXT2, a), false);
		if (!open) {
			int ls = 6;
			blit(g, LOCK, x1 - 6 - sw - ls - 4, y0 + (y1 - y0) / 2 - 4, ls, ls * 9 / 7, fade(TEXT3, a));
		}
	}

	private void stage(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int mouseX, int mouseY, float partial) {
		float now = this.now();
		int cx = (x0 + x1) / 2;
		int floor = y1 - 30;
		int acc = this.acc();
		int acc2 = this.acc2();
		float flicker = 0.88f + 0.08f * Mth.sin(now * 0.21f) + 0.04f * Mth.sin(now * 0.73f + 1.3f);
		int pw = Math.min(140, (x1 - x0) * 3 / 4);
		int ph = pw / 4;
		blitAt(g, GLOW, cx - pw * 0.6f, y0 + 10, pw * 1.2f, floor - y0, ARGB.color(0.16f * flicker, acc2));
		blit(g, PEDESTAL, cx - pw / 2, floor - ph * 11 / 30, pw, ph, 0xFFFFFFFF);
		blit(g, PEDESTAL_RIM, cx - pw / 2, floor - ph * 11 / 30, pw, ph, ARGB.color(0.85f * flicker, acc));
		blitAt(g, GLOW, cx - pw * 0.4f, floor - ph * 0.3f, pw * 0.8f, ph * 0.6f, ARGB.color(0.22f * flicker, acc2));
		String who = this.track == Cosmetics.KILLER ? "michael" : this.survivor;
		// The one wearing it, turning slowly (drag to turn), walking if asked.
		int my0 = y0 + 4;
		int my1 = floor + 4;
		AvatarRenderState s = HeroPreview.state(0, who, this.yaw, this.walk, now, -1, HeroPreview.item(who, who.equals("michael") ? 0 : 3), partial);
		float height = who.equals("michael") ? 2.1f : 1.8f;
		float scale = (my1 - my0 - 22) / (height * 1.3f);
		if (s != null) {
			Quaternionf tilt = new Quaternionf().rotateX(-6f * Mth.DEG_TO_RAD);
			Quaternionf rotation = new Quaternionf().rotateZ(Mth.PI).mul(tilt);
			// Feet on the pedestal, room above for a tall hat.
			g.entity(s, scale, new Vector3f(0f, ((my1 - my0) / 2f - 4f) / scale, 0f), rotation, tilt, x0, my0, x1, my1);
		}
		this.hits.add(new Hit(x0, my0, x1, my1, () -> this.dragging = true));
		// Over it: the name of what is tried on or shown last put on.
		Cosmetics.Item shown = this.hover != null ? Cosmetics.byId(this.hover) : null;
		if (shown != null) {
			Component n = caps(DISPLAY, Component.translatable("halloween.cosmetic." + shown.id()));
			int nw = this.font.width(n);
			blitAt(g, GLOW, cx - nw * 0.8f, y0 + 2, nw * 1.6f, 26, ARGB.color(0.3f, shown.legendary() ? GOLD : acc));
			g.text(this.font, n, cx - nw / 2, y0 + 8, shown.legendary() ? GOLD2 : TEXT, false);
			Component sub = caps(LABEL, Component.translatable(wearing(shown) ? "halloween.cosmetic.click_off" : "halloween.cosmetic.click_on"));
			g.text(this.font, sub, cx - this.font.width(sub) / 2, y0 + 19, TEXT2, false);
		} else {
			Component n = caps(LABEL, Component.translatable("halloween.cosmetic.hint_hover"));
			g.text(this.font, n, cx - this.font.width(n) / 2, y0 + 10, TEXT3, false);
		}
		// Under the model: walk / stand.
		Component wl = caps(LABEL, Component.translatable(this.walk ? "halloween.cosmetic.stand" : "halloween.cosmetic.walk"));
		int bw = this.font.width(wl) + 18;
		int bx = cx - bw / 2;
		int by = floor + 12;
		boolean hov = mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + 13;
		three(g, BTN_FILL, bx, by, bw, 13, hov ? 0xF0202634 : 0xE00E1118);
		three(g, BTN_LINE, bx, by, bw, 13, hov ? acc : LINE2[this.track]);
		g.text(this.font, wl, bx + 9, by + 3, hov ? TEXT : TEXT2, false);
		this.hits.add(new Hit(bx, by, bx + bw, by + 13, () -> {
			this.walk = !this.walk;
			click();
		}));
	}

	private void bottomBar(GuiGraphicsExtractor g, int m, int mouseX, int mouseY) {
		int y0 = this.height - 34;
		int y1 = this.height - 7;
		int bw = Math.min(130, Math.max(96, this.width / 6));
		int px1 = this.width - m - bw - 8;
		this.panel(g, m, y0, px1, y1);
		blit(g, DIAMOND_FILL, m + 8, y0 + 10, 8, 8, this.acc());
		List<net.minecraft.util.FormattedCharSequence> lines = this.font.split(Component.translatable("halloween.cosmetic.hint"), px1 - m - 30);
		int ly = y0 + (lines.size() > 1 ? 4 : 9);
		for (int i = 0; i < Math.min(2, lines.size()); i++) {
			g.text(this.font, lines.get(i), m + 22, ly + i * 10, TEXT2, false);
		}
		int bx = this.width - m - bw;
		boolean hover = mouseX >= bx && mouseX < bx + bw && mouseY >= y0 && mouseY < y1;
		three(g, BTN_FILL, bx, y0, bw, y1 - y0, hover ? 0xF0202634 : 0xE00E1118);
		three(g, BTN_LINE, bx, y0, bw, y1 - y0, hover ? this.acc() : LINE2[this.track]);
		Component label = caps(DISPLAY, Component.translatable("halloween.level.back"));
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
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (this.dragging) {
			this.yaw -= (float) dx * 2.2f;
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
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == GLFW.GLFW_KEY_TAB) {
			this.setTrack(1 - this.track);
			return true;
		}
		return super.keyPressed(event);
	}
}
