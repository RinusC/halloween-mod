package dev.halloween.client;

import dev.halloween.game.Perks;
import dev.halloween.net.HwNet;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

/**
 * Survivor perks (key P): the purse, every perk with what it does; buy one (it stays yours for good), put up to
 * three of yours into the build for the next match, take one out. The list scrolls with the wheel.
 */
public final class PerkScreen extends Screen {
	private static final int ROW = 34;
	private static final int BUTTON_W = 112;
	private static final int BUTTON_H = 18;
	private int x0;
	private int y0;
	private int x1;
	private int y1;
	private int scroll;

	public PerkScreen() {
		super(Component.translatable("halloween.perk.title"));
	}

	@Override
	protected void init() {
		int w = Math.min(this.width - 20, 420);
		int h = this.height - 20;
		this.x0 = (this.width - w) / 2;
		this.x1 = this.x0 + w;
		this.y0 = 10;
		this.y1 = this.y0 + h;
	}

	/** New points or a new build from the server. */
	public void refresh() {
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private int listTop() {
		return this.y0 + 36;
	}

	private int listBottom() {
		return this.y1 - 18;
	}

	private int maxScroll() {
		return Math.max(0, Perks.Perk.values().length * ROW - (this.listBottom() - this.listTop()));
	}

	private static boolean between() {
		return ClientState.match.state() == 0;
	}

	private enum Action {
		BUY, EQUIP, UNEQUIP
	}

	private static Action action(Perks.Perk perk) {
		if (ClientState.perkEquipped.contains(perk.key())) {
			return Action.UNEQUIP;
		}
		return ClientState.perkOwned.contains(perk.key()) ? Action.EQUIP : Action.BUY;
	}

	private static boolean enabled(Perks.Perk perk) {
		return switch (action(perk)) {
			case BUY -> ClientState.perkPoints >= perk.cost();
			case EQUIP -> between() && ClientState.perkEquipped.size() < ClientState.perkSlots;
			case UNEQUIP -> between();
		};
	}

	private static Component label(Perks.Perk perk) {
		return switch (action(perk)) {
			case BUY -> Component.translatable("halloween.perk.buy", perk.cost());
			case EQUIP -> Component.translatable("halloween.perk.equip");
			case UNEQUIP -> Component.translatable("halloween.perk.unequip");
		};
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		this.scroll = Mth.clamp(this.scroll, 0, this.maxScroll());
		g.fill(this.x0, this.y0, this.x1, this.y1, 0xE0100C0A);
		g.fill(this.x0, this.y0, this.x1, this.y0 + 2, 0xFFB8A888);
		g.centeredText(this.font, this.title, this.width / 2, this.y0 + 7, 0xFFFFFFFF);
		g.centeredText(this.font, Component.translatable("halloween.perk.purse", ClientState.perkPoints, ClientState.perkEquipped.size(), ClientState.perkSlots),
			this.width / 2, this.y0 + 20, 0xFFE0C060);
		int top = this.listTop();
		int bottom = this.listBottom();
		g.enableScissor(this.x0, top, this.x1, bottom);
		for (Perks.Perk perk : Perks.Perk.values()) {
			int y = top + perk.ordinal() * ROW - this.scroll;
			if (y + ROW < top || y > bottom) {
				continue;
			}
			boolean owned = ClientState.perkOwned.contains(perk.key());
			boolean equipped = ClientState.perkEquipped.contains(perk.key());
			if (equipped) {
				g.fill(this.x0 + 4, y, this.x1 - 4, y + ROW - 3, 0x40407A40);
			} else if (owned) {
				g.fill(this.x0 + 4, y, this.x1 - 4, y + ROW - 3, 0x18FFFFFF);
			}
			Component name = Component.translatable("halloween.perk." + perk.key());
			if (equipped) {
				name = Component.literal("✔ ").append(name);
			}
			g.text(this.font, name, this.x0 + 10, y + 3, owned ? 0xFFFFFFFF : 0xFFC8C0B0, true);
			int dy = y + 14;
			List<FormattedCharSequence> lines = this.font.split(Component.translatable("halloween.perk." + perk.key() + ".desc"), this.x1 - this.x0 - BUTTON_W - 30);
			for (int i = 0; i < lines.size() && i < 2; i++) {
				g.text(this.font, lines.get(i), this.x0 + 10, dy, owned ? 0xFFB8B2A6 : 0xFF8A847A, false);
				dy += 9;
			}
			int bx = this.x1 - BUTTON_W - 10;
			int by = y + (ROW - 3 - BUTTON_H) / 2;
			boolean on = enabled(perk);
			boolean hover = on && mouseX >= bx && mouseX < bx + BUTTON_W && mouseY >= by && mouseY < by + BUTTON_H && mouseY >= top && mouseY < bottom;
			g.fill(bx, by, bx + BUTTON_W, by + BUTTON_H, on ? (hover ? 0xFF6A6A6A : 0xFF4A4A4A) : 0xFF2A2A2A);
			g.fill(bx, by + BUTTON_H - 1, bx + BUTTON_W, by + BUTTON_H, 0xFF1A1A1A);
			g.centeredText(this.font, label(perk), bx + BUTTON_W / 2, by + 5, on ? 0xFFFFFFFF : 0xFF7A766E);
		}
		g.disableScissor();
		if (this.maxScroll() > 0) {
			int track = bottom - top;
			int thumb = Math.max(12, track * track / (track + this.maxScroll()));
			int at = top + (track - thumb) * this.scroll / this.maxScroll();
			g.fill(this.x1 - 4, at, this.x1 - 2, at + thumb, 0x80FFFFFF);
		}
		Component hint = Component.translatable(between() ? "halloween.perk.hint" : "halloween.perk.in_match");
		g.centeredText(this.font, hint, this.width / 2, this.y1 - 13, 0xFF8A847A);
		super.extractRenderState(g, mouseX, mouseY, partial);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x();
		double my = event.y();
		if (my >= this.listTop() && my < this.listBottom()) {
			for (Perks.Perk perk : Perks.Perk.values()) {
				int y = this.listTop() + perk.ordinal() * ROW - this.scroll;
				int bx = this.x1 - BUTTON_W - 10;
				int by = y + (ROW - 3 - BUTTON_H) / 2;
				if (mx >= bx && mx < bx + BUTTON_W && my >= by && my < by + BUTTON_H && enabled(perk)) {
					int action = switch (action(perk)) {
						case BUY -> Perks.BUY;
						case EQUIP -> Perks.EQUIP;
						case UNEQUIP -> Perks.UNEQUIP;
					};
					ClientPlayNetworking.send(new HwNet.PerkAction(perk.key(), action));
					Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f));
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		this.scroll = Mth.clamp(this.scroll - (int) Math.signum(scrollY) * ROW, 0, this.maxScroll());
		return true;
	}
}
