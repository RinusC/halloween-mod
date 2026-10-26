package dev.halloween.client;

import dev.halloween.game.HeroInfo;
import dev.halloween.game.HeroPicks;
import dev.halloween.game.Heroes;
import dev.halloween.net.HwNet;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
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
 */
public final class CharacterSelectScreen extends Screen {
	private static final int BG = 0xFF0B0908;
	private static final int PANEL = 0xD0141110;
	private static final int EDGE = 0xFF2B241E;
	private static final int ACCENT = 0xFFE0782C;
	private static final int ACCENT_DIM = 0x38E0782C;
	private static final int TEXT = 0xFFEDE6DA;
	private static final int TEXT2 = 0xFFB4AA9B;
	private static final int TEXT3 = 0xFF7A7166;
	private static final int GOLD = 0xFFE0C060;
	private static final int GREEN = 0xFF7CC266;
	private static final int ROW = 14;
	/** The opening scene on the spot: this long (ticks). */
	private static final float SCENE_TICKS = 140f;

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
		int at = lastHero == null ? -1 : this.list().indexOf(lastHero);
		this.index = Math.max(0, at);
		if (at < 0) {
			// Opened afresh: on the one picked, if any.
			String mine = this.tab == 0 ? myPick() : myKiller();
			this.index = Math.max(0, this.list().indexOf(mine));
		}
	}

	@Override
	protected void init() {
		int m = 8;
		this.top = 26;
		this.barY = this.height - 44;
		this.bottom = this.barY - 6;
		int w = this.width - 2 * m;
		int listW = Mth.clamp(w / 5, 104, 150);
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
		// Half way through (the clock moved on so the start isn't before it opened).
		this.ticks += (int) SCENE_TICKS;
		this.sceneStart = scene ? this.now() - SCENE_TICKS * 0.5f : -1;
	}

	// ---------------------------------------------------------------- doing things

	private void send(int action, String hero) {
		ClientPlayNetworking.send(new HwNet.HeroPick(action, hero));
	}

	private static void click() {
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
			if (ClientState.perkPoints < ClientState.heroPrice) {
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

	/** Picked: their line, and a low note under it. */
	private void voice() {
		this.lineUntil = this.now() + 70;
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL.value(), 0.6f, 0.35f));
	}

	// ---------------------------------------------------------------- drawing

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		// Its own backdrop, not the world blurred: a dark gym at night.
		g.fill(0, 0, this.width, this.height, BG);
		g.fillGradient(0, 0, this.width, this.height / 2, 0xFF17110D, BG);
		g.fillGradient(0, this.height * 3 / 4, this.width, this.height, BG, 0xFF050404);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		this.hits.clear();
		this.partial = partial;
		if (this.index >= this.list().size()) {
			this.index = 0;
		}
		this.header(g, mouseX, mouseY);
		this.heroList(g, mouseX, mouseY);
		this.stage(g, mouseX, mouseY, partial);
		this.card(g);
		this.bottomBar(g, mouseX, mouseY);
		super.extractRenderState(g, mouseX, mouseY, partial);
	}

	private void panel(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1) {
		g.fill(x0, y0, x1, y1, PANEL);
		g.outline(x0, y0, x1 - x0, y1 - y0, EDGE);
	}

	private void label(GuiGraphicsExtractor g, Component text, int x, int y) {
		g.text(this.font, text.copy().withStyle(ChatFormatting.BOLD), x, y, TEXT3, false);
	}

	/** A flat button; returns whether the mouse is on it. */
	private boolean button(GuiGraphicsExtractor g, int x, int y, int w, int h, Component text, boolean on, boolean primary, int mouseX, int mouseY,
		Runnable action) {
		boolean hover = on && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		int fill = !on ? 0xFF1C1816 : primary ? (hover ? 0xFFC9662A : 0xFFA9521F) : (hover ? 0xFF3A322B : 0xFF27211C);
		g.fill(x, y, x + w, y + h, fill);
		if (primary && on) {
			g.fill(x, y, x + w, y + 1, 0xFFE0874A);
			g.fill(x, y + h - 1, x + w, y + h, 0xFF6E3412);
		} else {
			g.outline(x, y, w, h, hover ? 0xFF5A4C40 : EDGE);
		}
		g.centeredText(this.font, text, x + w / 2, y + (h - 8) / 2, !on ? TEXT3 : primary ? 0xFFFFF4E6 : TEXT);
		if (on) {
			this.hits.add(new Hit(x, y, x + w, y + h, action));
		}
		return hover;
	}

	private void header(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int x = this.listX0;
		for (int t = 0; t < 2; t++) {
			Component name = Component.translatable(t == 0 ? "halloween.select.tab.survivors" : "halloween.select.tab.killers");
			int w = this.font.width(name.copy().withStyle(ChatFormatting.BOLD)) + 16;
			boolean on = this.tab == t;
			boolean hover = mouseX >= x && mouseX < x + w && mouseY >= 6 && mouseY < 22;
			g.text(this.font, name.copy().withStyle(ChatFormatting.BOLD), x + 8, 10, on ? TEXT : hover ? TEXT2 : TEXT3, false);
			if (on) {
				g.fill(x + 4, 21, x + w - 4, 23, ACCENT);
			}
			int tt = t;
			this.hits.add(new Hit(x, 4, x + w, 24, () -> this.setTab(tt)));
			x += w + 4;
		}
		// The lobby: how many are ready, the countdown.
		HwNet.HeroLobby l = ClientState.heroLobby;
		int ready = 0;
		for (boolean r : l.ready()) {
			ready += r ? 1 : 0;
		}
		Component status;
		if (!between()) {
			status = Component.translatable("halloween.select.in_match");
		} else if (l.countdown() >= 0) {
			status = Component.translatable("halloween.select.starting", ready, l.names().size(), String.format("0:%02d", l.countdown()));
		} else {
			status = Component.translatable("halloween.select.ready_count", ready, l.names().size());
		}
		int sw = this.font.width(status);
		g.text(this.font, status, this.cardX1 - sw, 10, l.countdown() >= 0 ? GOLD : TEXT2, false);
		g.fill(this.listX0, 25, this.cardX1, 26, EDGE);
	}

	private int listRows() {
		// Between the panel's title and the chance row at its foot.
		return Math.max(1, (this.bottom - this.top - 20 - ROW - 12) / ROW);
	}

	private void heroList(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int x0 = this.listX0;
		int x1 = this.listX1;
		int y0 = this.top + 4;
		this.panel(g, x0, y0, x1, this.bottom);
		List<String> heroes = this.list();
		this.label(g, Component.translatable(this.tab == 0 ? "halloween.select.list.survivors" : "halloween.select.list.killers", heroes.size()), x0 + 6, y0 + 5);
		int rows = this.listRows();
		this.listScroll = Mth.clamp(this.listScroll, 0, Math.max(0, heroes.size() - rows));
		int y = y0 + 16;
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
			if (shown) {
				g.fill(x0 + 1, y, x1 - 1, y + ROW, ACCENT_DIM);
				g.fill(x0 + 1, y, x0 + 3, y + ROW, ACCENT);
			} else if (hover) {
				g.fill(x0 + 1, y, x1 - 1, y + ROW, 0x16FFFFFF);
			}
			this.face(g, hero, x0 + 6, y + 2, 10, have);
			int color = !have ? TEXT3 : shown ? TEXT : TEXT2;
			g.text(this.font, Component.translatable("halloween.hero.name." + hero), x0 + 20, y + 3, color, false);
			// Its tag on the right: mine, taken, how many want him, locked with the price.
			String other = this.tab == 0 ? takenBy(hero) : null;
			if (mine) {
				g.text(this.font, "✔", x1 - 12, y + 3, GREEN, false);
			} else if (!have) {
				String price = String.valueOf(ClientState.heroPrice);
				int pw = this.font.width(price);
				g.text(this.font, price, x1 - 6 - pw, y + 3, ClientState.perkPoints >= ClientState.heroPrice ? GOLD : TEXT3, false);
				lock(g, x1 - 14 - pw, y + 3, TEXT3);
			} else if (other != null) {
				Component taken = Component.translatable("halloween.select.taken_" + pronoun(hero));
				g.text(this.font, taken, x1 - 6 - this.font.width(taken), y + 3, TEXT3, false);
			} else if (this.tab == 1 && keen(hero) > 0) {
				Component k = Component.translatable("halloween.select.keen", keen(hero));
				g.text(this.font, k, x1 - 6 - this.font.width(k), y + 3, TEXT3, false);
			}
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
			int at = y0 + 16 + (track - thumb) * this.listScroll / Math.max(1, heroes.size() - rows);
			g.fill(x1 - 3, at, x1 - 1, at + thumb, 0x60FFFFFF);
		}
		// Chance, or no killer for me.
		int ry = this.bottom - ROW - 6;
		g.fill(x0 + 6, ry - 3, x1 - 6, ry - 2, EDGE);
		boolean chance = this.tab == 0 && HeroPicks.RANDOM.equals(myPick());
		boolean hover = mouseX >= x0 && mouseX < x1 && mouseY >= ry && mouseY < ry + ROW && between();
		if (hover) {
			g.fill(x0 + 1, ry, x1 - 1, ry + ROW, 0x16FFFFFF);
		}
		if (this.tab == 0) {
			g.text(this.font, Component.translatable("halloween.select.random"), x0 + 8, ry + 3, chance ? TEXT : TEXT2, false);
			String bonus = chance ? "✔" : "+" + dev.halloween.HwConfig.get().heroRandomBonus;
			g.text(this.font, bonus, x1 - 6 - this.font.width(bonus), ry + 3, chance ? GREEN : GOLD, false);
			this.hits.add(new Hit(x0, ry, x1, ry + ROW, () -> {
				if (between()) {
					this.send(HeroPicks.SURVIVOR, chance ? "" : HeroPicks.RANDOM);
					click();
				}
			}));
		} else {
			boolean none = myKiller().isEmpty();
			g.text(this.font, Component.translatable("halloween.select.no_killer"), x0 + 8, ry + 3, none ? TEXT : TEXT2, false);
			if (none) {
				g.text(this.font, "✔", x1 - 12, ry + 3, GREEN, false);
			}
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

	/** A small padlock, 6 by 7. */
	private static void lock(GuiGraphicsExtractor g, int x, int y, int color) {
		g.fill(x + 1, y, x + 5, y + 1, color);
		g.fill(x + 1, y, x + 2, y + 3, color);
		g.fill(x + 4, y, x + 5, y + 3, color);
		g.fill(x, y + 3, x + 6, y + 7, color);
		g.fill(x + 2, y + 4, x + 4, y + 6, 0xFF141110);
	}

	/** The hero's face for the list: a survivor's from their skin, a killer's head in 3D. */
	private void face(GuiGraphicsExtractor g, String hero, int x, int y, int size, boolean bright) {
		if (!HeroInfo.killer(hero)) {
			PlayerFaceExtractor.extractRenderState(g, ClientSkins.skin(hero), x, y, size, bright ? 0xFFFFFFFF : 0xFF707070);
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
		int y0 = this.top + 4;
		int y1 = this.bottom;
		String hero = this.current();
		float now = this.now();
		boolean scene = this.sceneStart >= 0 && now - this.sceneStart < SCENE_TICKS;
		if (!scene) {
			this.sceneStart = -1;
		}
		float f = scene ? (now - this.sceneStart) / SCENE_TICKS : 0f;
		// A spot of warm light on the floor where they stand, their shadow in it.
		int cx = (x0 + x1) / 2;
		int floor = y1 - 50;
		int spot = (x1 - x0) * 2 / 5;
		for (int r = 4; r >= 1; r--) {
			ellipse(g, cx, floor, spot * r / 4, Math.max(2, spot * r / 16), 0x0EE09A5C);
		}
		ellipse(g, cx, floor, 22, 4, 0x50000000);
		// The name, arrows either side.
		Component name = Component.translatable("halloween.hero.name." + hero);
		if (!scene) {
			g.pose().pushMatrix();
			g.pose().translate(cx, y0 + 12);
			g.pose().scale(1.6f, 1.6f);
			g.centeredText(this.font, name.copy().withStyle(ChatFormatting.BOLD), 0, -4, TEXT);
			g.pose().popMatrix();
			Component role = Component.translatable(HeroInfo.killer(hero) ? "halloween.select.role.killer" : "halloween.select.role.survivor");
			g.centeredText(this.font, role, cx, y0 + 24, TEXT3);
		}
		this.arrow(g, x0 + 4, y0 + 6, "◀", mouseX, mouseY, () -> this.show(this.index - 1));
		this.arrow(g, x1 - 20, y0 + 6, "▶", mouseX, mouseY, () -> this.show(this.index + 1));
		// The model.
		int my0 = y0 + 36;
		int my1 = floor + 6;
		float k = Mth.clamp((now - this.switchedAt) / 6f, 0f, 1f);
		int slide = Math.round((1 - k) * (1 - k) * 28 * this.switchDir);
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
			List<FormattedCharSequence> lines = this.font.split(line, x1 - x0 - 30);
			int ly = my0 + 4;
			for (FormattedCharSequence l : lines) {
				g.centeredText(this.font, l, cx, ly, Cutscene.alpha(a, 0xE8DCC8));
				ly += 10;
			}
		}
		// Under the model: the pose, the thing in hand, the scene; then the pick.
		int by = floor + 12;
		int bw = Math.min(70, (x1 - x0 - 16) / 3);
		int bx = cx - (bw * 3 + 8) / 2;
		Component pose = this.fit(Component.translatable("halloween.select.pose." + (HeroInfo.killer(hero) ? Math.max(1, this.poseMode) : this.poseMode)),
			Component.translatable("halloween.select.pose_short"), bw);
		this.button(g, bx, by, bw, 14, pose, true, false, mouseX, mouseY, () -> {
			this.poseMode = HeroInfo.killer(hero) ? (this.poseMode == 2 ? 1 : 2) : (this.poseMode + 1) % 3;
			click();
		});
		int items = HeroPreview.items(hero);
		Component item = Component.translatable("halloween.select.item." + (hero.equals("michael") ? "m" : "s") + Math.min(this.itemChoice, Math.max(0, items - 1)));
		item = this.fit(items == 0 ? Component.translatable("halloween.select.item.none") : item, Component.translatable("halloween.select.item_short"), bw);
		this.button(g, bx + bw + 4, by, bw, 14, item, items > 0, false, mouseX, mouseY,
			() -> {
				this.itemChoice = (this.itemChoice + 1) % Math.max(1, items);
				click();
			});
		this.button(g, bx + 2 * (bw + 4), by, bw, 14, Component.translatable(scene ? "halloween.select.scene_stop" : "halloween.select.scene"), true, false,
			mouseX, mouseY, () -> {
				this.sceneStart = this.sceneStart >= 0 ? -1 : this.now();
				click();
			});
		int pw = Math.min(180, x1 - x0 - 16);
		this.button(g, cx - pw / 2, by + 18, pw, 18, this.primaryLabel(hero), this.primaryOn(hero), true, mouseX, mouseY, this::primary);
	}

	/** The label if it fits the button, else the short one. */
	private Component fit(Component label, Component shorter, int w) {
		return this.font.width(label) <= w - 6 ? label : shorter;
	}

	private void arrow(GuiGraphicsExtractor g, int x, int y, String glyph, int mouseX, int mouseY, Runnable action) {
		boolean hover = mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 14;
		g.centeredText(this.font, glyph, x + 8, y + 3, hover ? ACCENT : TEXT3);
		this.hits.add(new Hit(x, y, x + 16, y + 14, action));
	}

	private boolean primaryOn(String hero) {
		if (!between()) {
			return false;
		}
		if (!owned(hero)) {
			return ClientState.perkPoints >= ClientState.heroPrice;
		}
		return this.tab == 1 || hero.equals(myPick()) || takenBy(hero) == null;
	}

	private Component primaryLabel(String hero) {
		if (!between()) {
			return Component.translatable("halloween.select.after_match");
		}
		if (!owned(hero)) {
			if (this.buyConfirmUntil >= this.now()) {
				return Component.translatable("halloween.select.buy_confirm", ClientState.heroPrice);
			}
			return Component.translatable(ClientState.perkPoints >= ClientState.heroPrice ? "halloween.select.buy" : "halloween.select.buy_short",
				ClientState.heroPrice);
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
			Cutscene.bigText(g, mc, Component.translatable("halloween.hero.name." + hero), cx, y1 - bar - 30, 1.6f, Cutscene.alpha(t1, 0xE8E2D4));
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
		int y0 = this.top + 4;
		int y1 = this.bottom;
		this.panel(g, x0, y0, x1, y1);
		String hero = this.current();
		int w = x1 - x0 - 16;
		int x = x0 + 8;
		this.cardScroll = Mth.clamp(this.cardScroll, 0, Math.max(0, this.cardHeight - (y1 - y0 - 12)));
		g.enableScissor(x0 + 1, y0 + 1, x1 - 1, y1 - 1);
		int start = y0 + 7 - this.cardScroll;
		int y = start;
		String key = "halloween.heroinfo." + hero;
		y = this.section(g, Component.translatable(HeroInfo.killer(hero) ? "halloween.select.card.who_killer" : "halloween.select.card.who_" + pronoun(hero)),
			Component.translatable(key + ".story"), x, y, w, TEXT2);
		if (HeroInfo.killer(hero)) {
			this.label(g, Component.translatable("halloween.select.card.abilities"), x, y);
			y += 11;
			for (String[] a : abilities(hero)) {
				KeyMapping km = keyFor(a[1]);
				Component head = Component.translatable("halloween.heroinfo." + hero + "." + a[0]).withStyle(ChatFormatting.BOLD);
				g.text(this.font, head, x, y, TEXT, false);
				if (km != null) {
					Component k = km.getTranslatedKeyMessage();
					String ks = "[" + k.getString().toUpperCase(java.util.Locale.ROOT) + "]";
					g.text(this.font, ks, x1 - 8 - this.font.width(ks), y, ACCENT, false);
				}
				y += 10;
				y = this.wrap(g, Component.translatable("halloween.heroinfo." + hero + "." + a[0] + ".desc"), x, y, w, TEXT2) + 3;
			}
			y += 3;
			y = this.section(g, Component.translatable("halloween.select.card.weakness"), Component.translatable(key + ".weakness"), x, y, w, TEXT2);
			y = this.section(g, Component.translatable("halloween.select.card.tip"), Component.translatable(key + ".tip"), x, y, w, TEXT2);
			y = this.section(g, Component.translatable("halloween.select.card.prey"), Component.translatable(key + ".prey"), x, y, w, TEXT2);
			y = this.wrap(g, Component.literal("«").append(Component.translatable(key + ".quote")).append("»").withStyle(ChatFormatting.ITALIC), x, y, w, TEXT3) + 6;
		} else {
			Heroes.Skill skill = Heroes.skill(hero);
			if (skill != Heroes.Skill.NONE) {
				Component k = HalloweenClient.KEY_SKILL.getTranslatedKeyMessage();
				this.label(g, Component.translatable("halloween.select.card.skill", k.getString().toUpperCase(java.util.Locale.ROOT)), x, y);
				y += 11;
				g.text(this.font, Component.translatable("halloween.hero.skill." + skill.name().toLowerCase(java.util.Locale.ROOT)).withStyle(ChatFormatting.BOLD),
					x, y, TEXT, false);
				y += 10;
				y = this.wrap(g, Component.translatable(key + ".skill"), x, y, w, TEXT2);
				int[] secs = HeroInfo.skillSeconds(hero);
				g.text(this.font, Component.translatable("halloween.select.card.skill_time", secs[0], secs[1]), x, y, TEXT3, false);
				y += 14;
			}
			if (hasTrait(hero)) {
				y = this.section(g, Component.translatable("halloween.select.card.trait"), Component.translatable(key + ".trait"), x, y, w, TEXT2);
			}
		}
		// The bars.
		this.label(g, Component.translatable("halloween.select.card.stats"), x, y);
		y += 12;
		List<String> stats = HeroInfo.killer(hero) ? HeroInfo.KILLER_STATS : HeroInfo.SURVIVOR_STATS;
		int[] values = HeroInfo.stats(hero);
		int barW = Math.min(80, w / 2);
		for (int i = 0; i < stats.size(); i++) {
			g.text(this.font, Component.translatable("halloween.select.stat." + stats.get(i)), x, y, TEXT2, false);
			int bx = x1 - 8 - barW;
			g.fill(bx, y + 2, bx + barW, y + 6, 0xFF2A2420);
			g.fill(bx, y + 2, bx + barW * values[i] / 100, y + 6, ACCENT);
			y += 11;
		}
		y += 4;
		// Their record.
		this.label(g, Component.translatable("halloween.select.card.record"), x, y);
		y += 11;
		List<Integer> r = ClientState.heroRecords.get(hero);
		Component record = r == null || r.isEmpty() || r.get(0) == 0 ? Component.translatable("halloween.select.record.none")
			: Component.translatable(HeroInfo.killer(hero) ? "halloween.select.record.killer" : "halloween.select.record.survivor", r.get(0), r.get(1), r.get(2));
		y = this.wrap(g, record, x, y, w, TEXT2) + 4;
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
				y += 2;
				this.label(g, Component.translatable("halloween.select.card.team"), x, y);
				y += 11;
				y = this.wrap(g, Component.translatable("halloween.select.hint." + hint), x, y, w, GOLD) + 4;
			}
		}
		if (!owned(hero)) {
			y += 2;
			lock(g, x, y, GOLD);
			g.text(this.font, Component.translatable("halloween.select.locked", ClientState.heroPrice, ClientState.perkPoints), x + 10, y, GOLD, false);
			y += 12;
		}
		g.disableScissor();
		this.cardHeight = y - start;
		if (this.cardHeight > y1 - y0 - 12) {
			int track = y1 - y0 - 8;
			int thumb = Math.max(12, track * track / this.cardHeight);
			int at = y0 + 4 + (track - thumb) * this.cardScroll / Math.max(1, this.cardHeight - (y1 - y0 - 12));
			g.fill(x1 - 3, at, x1 - 1, at + thumb, 0x60FFFFFF);
		}
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
		this.label(g, title, x, y);
		return this.wrap(g, body, x, y + 11, w, color) + 7;
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
		int y1 = this.height - 8;
		int bw = Math.min(120, Math.max(90, this.width / 6));
		int px1 = this.cardX1 - bw - 6;
		this.panel(g, this.listX0, y0, px1, y1);
		this.label(g, Component.translatable("halloween.select.perks", ClientState.perkEquipped.size(), ClientState.perkSlots, ClientState.perkPoints),
			this.listX0 + 8, y0 + 5);
		int slots = Math.max(1, ClientState.perkSlots);
		int sx = this.listX0 + 8;
		int sw = (px1 - this.listX0 - 16 - (slots - 1) * 6) / slots;
		int sy = y0 + 16;
		int sh = y1 - sy - 5;
		for (int i = 0; i < slots; i++) {
			int x = sx + i * (sw + 6);
			boolean hover = mouseX >= x && mouseX < x + sw && mouseY >= sy && mouseY < sy + sh;
			boolean has = i < ClientState.perkEquipped.size();
			g.fill(x, sy, x + sw, sy + sh, hover ? 0xFF2E2620 : 0xFF1C1816);
			g.outline(x, sy, sw, sh, has ? 0xFF5A4630 : EDGE);
			Component name = has ? Component.translatable("halloween.perk." + ClientState.perkEquipped.get(i)) : Component.translatable("halloween.select.slot_empty");
			String cut = this.font.plainSubstrByWidth(name.getString(), sw - 6);
			g.centeredText(this.font, cut, x + sw / 2, sy + (sh - 8) / 2, has ? TEXT : TEXT3);
			this.hits.add(new Hit(x, sy, x + sw, sy + sh, () -> {
				click();
				Minecraft.getInstance().gui.setScreen(new PerkScreen(this));
			}));
		}
		int bx = this.cardX1 - bw;
		boolean ready = myReady();
		this.button(g, bx, y0, bw, 18, Component.translatable(ready ? "halloween.select.ready_on" : "halloween.select.ready"), between(), true, mouseX, mouseY,
			() -> {
				this.send(HeroPicks.READY, "");
				click();
			});
		this.button(g, bx, y0 + 21, bw, y1 - y0 - 21, Component.translatable("halloween.select.practice"), between(), false, mouseX, mouseY, () -> {
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
