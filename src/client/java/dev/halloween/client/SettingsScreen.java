package dev.halloween.client;

import static dev.halloween.client.CharacterSelectScreen.*;

import dev.halloween.Edition;
import dev.halloween.Role;
import dev.halloween.game.Events;
import dev.halloween.game.HwItems;
import dev.halloween.game.MapPoints;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

/**
 * The game master's settings (the button in the top right corner of the character select): every /hw command as a
 * button, sorted into sections on the left. A button sends its command as the player would type it, so the server
 * still checks the permission; what the server answers shows at the foot. Dressed as the level road.
 */
public final class SettingsScreen extends Screen {
	private static final int ACC = ACCENT[0];
	private static final int ACC2 = ACCENT2[0];
	private static final int LN2 = LINE2[0];
	private static final int BTN_H = 16;

	private record Button(Component label, Supplier<String> command) {
	}

	/** A section: its buttons, and the one value some of them read (a number, a map's name), if any. */
	private record Section(String key, String fieldKey, String fieldDefault, List<Button> buttons) {
	}

	private record Hit(int x0, int y0, int x1, int y1, Runnable action) {
		boolean in(double x, double y) {
			return x >= this.x0 && x < this.x1 && y >= this.y0 && y < this.y1;
		}
	}

	private final Screen parent;
	private final List<Section> sections = new ArrayList<>();
	private final List<Hit> hits = new ArrayList<>();
	private final String[] values;
	private int section;
	private EditBox field;
	private int ticks;
	private float partial;
	private int scroll;
	private int maxScroll;
	private String last = "";

	public SettingsScreen(Screen parent) {
		super(Component.translatable("halloween.settings.title"));
		this.parent = parent;
		this.build();
		this.values = new String[this.sections.size()];
		for (int i = 0; i < this.values.length; i++) {
			this.values[i] = this.sections.get(i).fieldDefault();
		}
	}

	private static Button b(String key, String command) {
		return new Button(Component.translatable(key), () -> command);
	}

	private String value() {
		return this.field != null ? this.field.getValue().trim() : "";
	}

	private void build() {
		List<Button> match = new ArrayList<>();
		match.add(b("halloween.settings.start", "hw start"));
		match.add(b("halloween.settings.stop", "hw stop"));
		match.add(b("halloween.settings.reset", "hw reset"));
		match.add(b("halloween.settings.lobby", "hw lobby"));
		match.add(b("halloween.settings.lobby_tp", "hw lobby tp"));
		match.add(b("halloween.settings.tutorial", "hw tutorial"));
		match.add(new Button(Component.translatable("halloween.settings.timer"), () -> "hw timer " + this.value()));
		match.add(b("halloween.settings.reload", "hw reload"));
		this.sections.add(new Section("halloween.settings.sec.match", "halloween.settings.field.seconds", "300", match));

		List<Button> killer = new ArrayList<>();
		killer.add(b("halloween.settings.michael", "hw killer michael"));
		if (!Edition.MICHAEL_ONLY) {
			killer.add(b("halloween.settings.demogorgon", "hw killer demogorgon"));
			killer.add(b("halloween.settings.vecna", "hw killer vecna"));
		}
		killer.add(b("halloween.settings.shape_bot", "hw shape bot"));
		killer.add(b("halloween.settings.shape_off", "hw shape off"));
		killer.add(b("halloween.settings.runt_bot", "hw runt bot"));
		killer.add(b("halloween.settings.runt_me", "hw runt @s"));
		killer.add(b("halloween.settings.runt_off", "hw runt off"));
		killer.add(new Button(Component.translatable("halloween.settings.bloodthirst"), () -> "hw bloodthirst " + this.value()));
		for (int s = 1; s <= 4; s++) {
			killer.add(new Button(Component.translatable("halloween.settings.curse", s), curse(s)));
		}
		this.sections.add(new Section("halloween.settings.sec.killer", "halloween.settings.field.bloodthirst", "100", killer));

		List<Button> role = new ArrayList<>();
		for (Role r : Role.values()) {
			String name = r.name().toLowerCase(Locale.ROOT);
			role.add(b("halloween.settings.role." + name, "hw role @s " + name));
		}
		this.sections.add(new Section("halloween.settings.sec.role", null, "", role));

		List<Button> map = new ArrayList<>();
		map.add(b("halloween.settings.haddonfield", "hw map haddonfield"));
		map.add(b("halloween.settings.town", "hw map town"));
		map.add(b("halloween.settings.arena", "hw arena"));
		map.add(new Button(Component.translatable("halloween.settings.map_save"), () -> "hw map save " + this.value()));
		map.add(new Button(Component.translatable("halloween.settings.map_load"), () -> "hw map load " + this.value()));
		map.add(b("halloween.settings.mannequin", "hw mannequin"));
		map.add(b("halloween.settings.locker", "hw locker"));
		for (String exit : List.of("cellar", "car", "police", "gate")) {
			map.add(b("halloween.settings.exit." + exit, "hw exit open " + exit));
		}
		this.sections.add(new Section("halloween.settings.sec.map", "halloween.settings.field.map", "town", map));

		List<Button> events = new ArrayList<>();
		for (String id : Events.ids()) {
			events.add(b("halloween.settings.event." + id, "hw event " + id));
		}
		this.sections.add(new Section("halloween.settings.sec.events", null, "", events));

		List<Button> items = new ArrayList<>();
		for (HwItems.Kind kind : HwItems.Kind.values()) {
			items.add(new Button(Component.translatable("halloween.item." + kind.key()), () -> "hw give " + kind.key() + " " + this.count()));
		}
		this.sections.add(new Section("halloween.settings.sec.items", "halloween.settings.field.count", "1", items));

		List<Button> points = new ArrayList<>();
		for (MapPoints.Type type : MapPoints.Type.values()) {
			points.add(new Button(Component.translatable("halloween.settings.point_add", type.key()), () -> "hw point add " + type.key()));
		}
		points.add(b("halloween.settings.point_remove", "hw point remove"));
		points.add(b("halloween.settings.point_list", "hw point list"));
		for (MapPoints.Type type : MapPoints.Type.values()) {
			points.add(new Button(Component.translatable("halloween.settings.point_clear", type.key()), () -> "hw point clear " + type.key()));
		}
		this.sections.add(new Section("halloween.settings.sec.points", null, "", points));
	}

	private static Supplier<String> curse(int stage) {
		return () -> "hw curse " + stage;
	}

	private String count() {
		try {
			return Integer.toString(Mth.clamp(Integer.parseInt(this.value()), 1, 64));
		} catch (NumberFormatException e) {
			return "1";
		}
	}

	// ---------------------------------------------------------------- layout

	private int navX1() {
		return 8 + Mth.clamp(this.width / 5, 100, 140);
	}

	@Override
	protected void init() {
		this.field = null;
		Section s = this.sections.get(this.section);
		if (s.fieldKey() != null) {
			int x = this.navX1() + 6 + 10;
			this.field = new EditBox(this.font, x + 80, 42, 90, 14, Component.translatable(s.fieldKey()));
			this.field.setValue(this.values[this.section]);
			this.field.setResponder(v -> this.values[this.section] = v);
			this.addRenderableWidget(this.field);
		}
	}

	private void select(int i) {
		if (i == this.section) {
			return;
		}
		click();
		this.section = i;
		this.scroll = 0;
		this.rebuildWidgets();
	}

	private void send(Button button) {
		click();
		String command = button.command().get();
		if (command == null || command.endsWith(" ")) {
			return;
		}
		this.last = "/" + command;
		var player = Minecraft.getInstance().player;
		if (player != null) {
			player.connection.sendCommand(command);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void tick() {
		this.ticks++;
	}

	@Override
	public void onClose() {
		Minecraft.getInstance().gui.setScreen(this.parent);
	}

	// ---------------------------------------------------------------- drawing

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		this.partial = partial;
		LevelScreen.street(g, this.width, this.height, this.ticks + partial, mouseX);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		this.hits.clear();
		this.partial = partial;
		int m = 8;
		// The title as the select's lit tab, the rule under it.
		Component name = caps(DISPLAY, this.title);
		int tw = this.font.width(name) + 22;
		blitAt(g, GLOW, m - 10, -2, tw + 20, 32, ARGB.multiplyAlpha(ACC, 0.35f));
		three(g, BTN_FILL, m, 6, tw, 16, ACC);
		three(g, BTN_LINE, m, 6, tw, 16, ACC2);
		g.text(this.font, name, m + 11, 10, DARK_TEXT, false);
		g.fill(m, 25, this.width - m, 26, ARGB.multiplyAlpha(LINE[0], 0.9f));
		blit(g, GLOW_LINE, m, 23, this.width - 2 * m, 5, ARGB.multiplyAlpha(ACC, 0.5f));

		int y0 = 32;
		int y1 = this.height - 42;
		this.nav(g, m, y0, this.navX1(), y1, mouseX, mouseY);
		this.content(g, this.navX1() + 6, y0, this.width - m, y1, mouseX, mouseY);
		this.footer(g, m, mouseX, mouseY);
		super.extractRenderState(g, mouseX, mouseY, partial);
		int seed = this.ticks / 2;
		g.blit(RenderPipelines.GUI_TEXTURED, GRAIN.id(), 0, 0, rnd(seed, 7) * GRAIN.w(), rnd(seed, 8) * GRAIN.h(), this.width, this.height,
			this.width * 2, this.height * 2, GRAIN.w(), GRAIN.h(), 0x0DFFFFFF);
	}

	private void panel(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1) {
		nine(g, PANEL_FILL, x0, y0, x1 - x0, y1 - y0, 9, PANEL[0]);
		nine(g, PANEL_LINE, x0, y0, x1 - x0, y1 - y0, 9, LN2);
		nine(g, PANEL_CORNER, x0, y0, x1 - x0, y1 - y0, 9, ARGB.multiplyAlpha(ACC, 0.9f));
	}

	private void nav(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int mouseX, int mouseY) {
		this.panel(g, x0, y0, x1, y1);
		int y = y0 + 8;
		for (int i = 0; i < this.sections.size(); i++) {
			boolean on = i == this.section;
			boolean hover = !on && mouseX >= x0 && mouseX < x1 && mouseY >= y && mouseY < y + 18;
			if (on) {
				g.fill(x0 + 3, y, x1 - 3, y + 18, ARGB.multiplyAlpha(ACC, 0.22f));
				g.fill(x0 + 3, y, x0 + 5, y + 18, ACC);
			} else if (hover) {
				g.fill(x0 + 3, y, x1 - 3, y + 18, 0x30FFFFFF);
			}
			blit(g, on ? DIAMOND_FILL : DIAMOND_LINE, x0 + 10, y + 5, 8, 8, on ? ACC2 : hover ? ACC : TEXT3);
			Component label = caps(LABEL, Component.translatable(this.sections.get(i).key()));
			g.text(this.font, label, x0 + 23, y + 5, on || hover ? TEXT : TEXT2, false);
			int ii = i;
			this.hits.add(new Hit(x0, y, x1, y + 18, () -> this.select(ii)));
			y += 19;
		}
	}

	private void content(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int mouseX, int mouseY) {
		this.panel(g, x0, y0, x1, y1);
		Section s = this.sections.get(this.section);
		int px = x0 + 10;
		int top = y0 + 10;
		if (this.field != null) {
			g.text(this.font, caps(LABEL, Component.translatable(s.fieldKey())), px, this.field.getY() + 3, TEXT2, false);
			top = this.field.getY() + this.field.getHeight() + 8;
		}
		// The buttons flow in rows, as wide as their words; the rest scrolls.
		int y = top - this.scroll;
		int x = px;
		int right = x1 - 10;
		int bottom = y1 - 8;
		g.enableScissor(x0 + 2, top - 2, x1 - 2, bottom);
		for (Button button : s.buttons()) {
			Component label = caps(DISPLAY, button.label());
			int w = Math.min(right - px, this.font.width(label) + 20);
			if (x + w > right) {
				x = px;
				y += BTN_H + 4;
			}
			boolean visible = y + BTN_H > top && y < bottom;
			boolean hover = visible && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + BTN_H && mouseY >= top && mouseY < bottom;
			three(g, BTN_FILL, x, y, w, BTN_H, hover ? ARGB.srgbLerp(0.45f, ACC, ACC2) : 0xE00E1118);
			three(g, BTN_LINE, x, y, w, BTN_H, hover ? ACC2 : LN2);
			g.text(this.font, label, x + (w - this.font.width(label)) / 2, y + 4, hover ? DARK_TEXT : TEXT, false);
			if (visible) {
				int cy0 = Math.max(y, top);
				int cy1 = Math.min(y + BTN_H, bottom);
				this.hits.add(new Hit(x, cy0, x + w, cy1, () -> this.send(button)));
			}
			x += w + 4;
		}
		g.disableScissor();
		this.maxScroll = Math.max(0, y + this.scroll + BTN_H - bottom);
		this.scroll = Mth.clamp(this.scroll, 0, this.maxScroll);
	}

	private void footer(GuiGraphicsExtractor g, int m, int mouseX, int mouseY) {
		int y0 = this.height - 36;
		int y1 = this.height - 7;
		int bw = Math.min(130, Math.max(96, this.width / 6));
		int px1 = this.width - m - bw - 8;
		this.panel(g, m, y0, px1, y1);
		blitAt(g, GLOW, m + 3, y0 + 5, 18, 18, ARGB.multiplyAlpha(ACC, 0.6f));
		blit(g, DIAMOND_FILL, m + 8, y0 + 10, 8, 8, ACC);
		Component text = this.last.isEmpty() ? Component.translatable("halloween.settings.hint") : Component.literal(this.last);
		var lines = this.font.split(text, px1 - m - 30);
		int ly = y0 + (lines.size() > 1 ? 5 : 10);
		for (int i = 0; i < Math.min(2, lines.size()); i++) {
			g.text(this.font, lines.get(i), m + 22, ly + i * 10, this.last.isEmpty() ? TEXT2 : ACC2, false);
		}
		int bx = this.width - m - bw;
		boolean hover = mouseX >= bx && mouseX < bx + bw && mouseY >= y0 && mouseY < y1;
		three(g, BTN_FILL, bx, y0, bw, y1 - y0, hover ? 0xF0202634 : 0xE00E1118);
		three(g, BTN_LINE, bx, y0, bw, y1 - y0, hover ? ACC : LN2);
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
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (this.field != null) {
			this.field.setFocused(false);
		}
		for (int i = this.hits.size() - 1; i >= 0; i--) {
			Hit h = this.hits.get(i);
			if (h.in(event.x(), event.y())) {
				h.action().run();
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		this.scroll = Mth.clamp(this.scroll - (int) Math.round(scrollY * 20), 0, this.maxScroll);
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		switch (event.key()) {
			case GLFW.GLFW_KEY_UP -> {
				this.select(Math.floorMod(this.section - 1, this.sections.size()));
				return true;
			}
			case GLFW.GLFW_KEY_DOWN -> {
				this.select(Math.floorMod(this.section + 1, this.sections.size()));
				return true;
			}
			default -> {
			}
		}
		return super.keyPressed(event);
	}
}
