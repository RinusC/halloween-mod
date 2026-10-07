package dev.halloween.client;

import dev.halloween.game.Perks;
import dev.halloween.net.HwNet;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Survivor perks (key P): the points earned over all matches, each perk with what it does, and whether it is
 * open yet. One is picked for the next match; between matches only.
 */
public final class PerkScreen extends Screen {
	private static final int ROW = 38;
	private int x0;
	private int y0;
	private int x1;

	public PerkScreen() {
		super(Component.translatable("halloween.perk.title"));
	}

	@Override
	protected void init() {
		int w = Math.min(this.width - 20, 400);
		int h = 52 + Perks.Perk.values().length * ROW + 24;
		this.x0 = (this.width - w) / 2;
		this.x1 = this.x0 + w;
		this.y0 = (this.height - h) / 2;
		boolean between = ClientState.match.state() == 0;
		for (Perks.Perk perk : Perks.Perk.values()) {
			int y = this.y0 + 46 + perk.ordinal() * ROW;
			boolean open = ClientState.perkPoints >= perk.cost();
			boolean chosen = perk.key().equals(ClientState.perkChosen);
			Component label = chosen ? Component.translatable("halloween.perk.take_off")
				: open ? Component.translatable("halloween.perk.choose")
				: Component.translatable("halloween.perk.locked", perk.cost());
			Button b = Button.builder(label, btn -> ClientPlayNetworking.send(new HwNet.ChoosePerk(chosen ? "" : perk.key())))
				.bounds(this.x1 - 122, y + 7, 114, 20).build();
			b.active = between && open;
			this.addRenderableWidget(b);
		}
	}

	/** New points or a new pick from the server: the buttons follow. */
	public void refresh() {
		this.rebuildWidgets();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		int h = 52 + Perks.Perk.values().length * ROW + 24;
		g.fill(this.x0, this.y0, this.x1, this.y0 + h, 0xE0100C0A);
		g.fill(this.x0, this.y0, this.x1, this.y0 + 2, 0xFFB8A888);
		g.centeredText(this.font, this.title, this.width / 2, this.y0 + 8, 0xFFFFFFFF);
		g.centeredText(this.font, Component.translatable("halloween.perk.total", ClientState.perkPoints), this.width / 2, this.y0 + 22, 0xFFE0C060);
		for (Perks.Perk perk : Perks.Perk.values()) {
			int y = this.y0 + 46 + perk.ordinal() * ROW;
			boolean open = ClientState.perkPoints >= perk.cost();
			boolean chosen = perk.key().equals(ClientState.perkChosen);
			if (chosen) {
				g.fill(this.x0 + 4, y, this.x1 - 4, y + ROW - 4, 0x40407A40);
			}
			Component name = Component.translatable("halloween.perk." + perk.key());
			g.text(this.font, chosen ? Component.literal("✔ ").append(name) : name, this.x0 + 10, y + 6, open ? 0xFFFFFFFF : 0xFF8A847A, true);
			int dy = y + 18;
			for (net.minecraft.util.FormattedCharSequence line : this.font.split(Component.translatable("halloween.perk." + perk.key() + ".desc"), this.x1 - this.x0 - 146)) {
				g.text(this.font, line, this.x0 + 10, dy, open ? 0xFFB8B2A6 : 0xFF6A665E, false);
				dy += 9;
			}
		}
		Component hint = Component.translatable(ClientState.match.state() == 0 ? "halloween.perk.hint" : "halloween.perk.in_match");
		g.centeredText(this.font, hint, this.width / 2, this.y0 + h - 16, 0xFF8A847A);
		super.extractRenderState(g, mouseX, mouseY, partial);
	}
}
