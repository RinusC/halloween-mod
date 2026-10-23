package dev.halloween.client;

import dev.halloween.HwConfig;
import dev.halloween.Role;
import dev.halloween.game.FocusPoints;
import dev.halloween.net.HwNet;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Match HUD, laid out like the original: Bloodthirst ring bottom-left, weapon and ability diamonds
 * bottom-right, XP feed on the right under the notices, objectives top-left, world markers projected on screen.
 * Civilians get health and their side of the grab check.
 */
public final class HwHud {
	static final int TEXT = 0xFFE8E2D6;
	static final int DIM = 0xFF8A847A;
	static final int BLOOD = 0xFFB3121B;
	private static final int RED_FILL = 0xE0701015;
	private static final int DARK_FILL = 0xC0181818;
	private static final int GREEN = 0xFF3FE0A0;
	private static final int YELLOW = 0xFFFFD24A;
	private static final int ORANGE = 0xFFFF9A2E;
	private static final net.minecraft.resources.Identifier BADGE = net.minecraft.resources.Identifier.fromNamespaceAndPath("halloween", "textures/gui/wanted_badge.png");

	private HwHud() {
	}

	public static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return;
		}
		VrMarks.begin();
		if (VecnaDeathScene.overlay(g, delta)) {
			// Vecna taking the cursed one, for the two of them.
			return;
		}
		if (KillCam.overlay(g, delta)) {
			// A kill seen from the side.
			return;
		}
		if (Cutscene.overlay(g, delta)) {
			// The opening scene: nothing else on screen.
			return;
		}
		if (!ClientState.inMatch() && ClientState.lobby.inLobby()) {
			lobby(g, mc);
			if (ClientGuns.heldGun(mc) != null && !ClientGuns.aiming()) {
				// At the range from the hip: a small dot (aimed: the sights).
				g.fill(g.guiWidth() / 2, g.guiHeight() / 2, g.guiWidth() / 2 + 1, g.guiHeight() / 2 + 1, 0x90E8E2D6);
			}
		}
		// The whole match HUD is drawn a little smaller than the GUI scale.
		float scale = (float) HwConfig.get().hudScale;
		g.pose().pushMatrix();
		g.pose().scale(scale, scale);
		try {
			renderScaled(g, delta, mc, Math.round(g.guiWidth() / scale), Math.round(g.guiHeight() / scale));
		} finally {
			g.pose().popMatrix();
		}
	}

	/** The lobby: the status line on top, a tip that changes now and then, and the stand's text when at one. */
	private static void lobby(GuiGraphicsExtractor g, Minecraft mc) {
		int w = g.guiWidth();
		int h = g.guiHeight();
		HwNet.LobbyInfo info = ClientState.lobby;
		if (!info.status().getString().isEmpty()) {
			int tw = mc.font.width(info.status());
			g.fill(w / 2 - tw / 2 - 6, 6, w / 2 + tw / 2 + 6, 20, 0xA0000000);
			g.centeredText(mc.font, info.status(), w / 2, 9, 0xFFFFFFFF);
		}
		int tip = (int) (mc.level.getGameTime() / 240 % 12) + 1;
		Component tipText = Component.translatable("halloween.lobby.tip." + tip);
		g.text(mc.font, tipText, 8, 26, 0xFFB8B2A6, true);
		if (info.panel().isEmpty()) {
			return;
		}
		int pw = Math.min(w - 20, 330);
		java.util.List<net.minecraft.util.FormattedCharSequence> lines = new java.util.ArrayList<>();
		for (int i = 1; i < info.panel().size(); i++) {
			lines.addAll(mc.font.split(info.panel().get(i), pw - 16));
		}
		int ph = 22 + lines.size() * 10;
		int x0 = w / 2 - pw / 2;
		int y0 = h - 64 - ph;
		g.fill(x0, y0, x0 + pw, y0 + ph, 0xD0100C0A);
		g.fill(x0, y0, x0 + pw, y0 + 2, 0xFFE0A040);
		g.centeredText(mc.font, info.panel().getFirst(), w / 2, y0 + 7, 0xFFFFFFFF);
		int y = y0 + 20;
		for (net.minecraft.util.FormattedCharSequence line : lines) {
			g.text(mc.font, line, x0 + 8, y, 0xFFE8E2D4, false);
			y += 10;
		}
	}

	private static void renderScaled(GuiGraphicsExtractor g, DeltaTracker delta, Minecraft mc, int w, int h) {
		Role role = ClientState.myRole();
		float partial = delta.getGameTimeDeltaPartialTick(false);
		if (role == Role.SHAPE) {
			HwNet.ShapeHud hud = ClientState.hud;
			if (hud == null) {
				return;
			}
			beams(g, hud, w, h);
			upsideDown(g, mc, w, h, partial);
			impulseSpots(g, mc, w, h, partial);
			runtForMichael(g, mc, w, h, partial);
			noises(g, mc, w, h, partial);
			if (hud.sense()) {
				victimMarkers(g, mc, w, h, partial);
			}
			reticle(g, mc, hud, w, h, partial);
			if (ClientState.qte.active() && ClientState.qte.forShape()) {
				shapeGrab(g, mc, w, h, partial);
			}
			int y = goals(g, mc, true);
			exitLines(g, mc.font, 10, y + 4);
			feed(g, mc.font, w, h);
			bloodthirstRing(g, mc.font, hud, h);
			abilities(g, mc, hud, w, h);
			if (MichaelRenderer.vecna()) {
				vecnaScreen(g, mc, w, h);
			}
			shapeDoorPrompt(g, mc, w, h);
			prompts(g, mc, w, h);
			progressBar(g, mc, w, h);
			stunAndBlind(g, mc, hud, w, h);
		} else if (role == Role.RUNT) {
			runtView(g, mc, w, h, partial);
		} else if (role.survivor()) {
			if (mc.player.isSpectator()) {
				int respawn = ClientState.match.respawnTicks();
				Component line = respawn > 0 ? Component.translatable("halloween.hud.respawn", clock(respawn))
					: Component.translatable("halloween.hud.dead");
				g.centeredText(mc.font, line, w / 2, h - 40, respawn > 0 ? 0xFF7FA7FF : BLOOD);
			} else {
				alerts(g, mc, w, h, partial);
				healthBar(g, mc, w, h);
				curseHud(g, mc, w, h);
				int pushLeft = (int) Math.max(0, ClientState.pushReadyAt - mc.level.getGameTime());
				smallAction(g, mc.font, w - 12, h - 84, Component.translatable("halloween.hud.shove"), mc.options.keyAttack.getTranslatedKeyMessage(), pushLeft);
				inventoryBar(g, mc, w, h);
				gunHud(g, mc, w, h);
				prompts(g, mc, w, h);
				skillCheck(g, mc, w, h, partial);
				progressBar(g, mc, w, h);
				if (ClientState.qte.active() && !ClientState.qte.forShape()) {
					victimQte(g, mc, w, h, partial);
				}
			}
			int gy = goals(g, mc, false);
			// The ways out, each with its steps.
			escapeSteps(g, mc, gy);
			feed(g, mc.font, w, h);
		}
		matchTimer(g, mc, w);
		ClientEvents.render(g, mc, w, h, partialTicks(delta));
		int noticesTop = minimapAndWanted(g, mc, w, partialTicks(delta));
		notices(g, mc.font, w, noticesTop);
		// The match summary is a screen of its own now (MatchEndScreen).
	}

	/** Corner notices (match news, callouts, deaths), newest on top, fading after a few seconds. */
	private static void notices(GuiGraphicsExtractor g, Font font, int w, int top) {
		long now = System.currentTimeMillis();
		ClientState.NOTICES.removeIf(n -> now - n.time() > 7000);
		int y = top;
		for (ClientState.Notice notice : ClientState.NOTICES) {
			long age = now - notice.time();
			int alpha = (int) (255 * Mth.clamp((7000 - age) / 1000f, 0, 1) * Mth.clamp(age / 150f, 0, 1));
			if (alpha < 8) {
				continue;
			}
			int tw = font.width(notice.text());
			int x = w - 10 - tw;
			g.fill(x - 5, y - 3, w - 6, y + 11, (Math.min(alpha, 170) << 24));
			g.fill(w - 7, y - 3, w - 6, y + 11, (alpha << 24) | (BLOOD & 0xFFFFFF));
			g.text(font, notice.text(), x, y, (alpha << 24) | 0xFFFFFF, true);
			y += 17;
		}
	}

	// ---------------------------------------------------------------- match

	private static String clock(int ticks) {
		int s = Math.max(0, (ticks + 19) / 20);
		return String.format("%d:%02d", s / 60, s % 60);
	}

	private static void matchTimer(GuiGraphicsExtractor g, Minecraft mc, int w) {
		HwNet.MatchSync m = ClientState.match;
		if (m.state() == 1) {
			g.centeredText(mc.font, Component.translatable("halloween.hud.countdown", clock(m.countdown())), w / 2, 8, TEXT);
		} else if (m.state() == 2) {
			g.pose().pushMatrix();
			g.pose().translate(w / 2f, 6);
			g.pose().scale(1.5f, 1.5f);
			int color = m.ticksLeft() < 60 * 20 ? BLOOD : TEXT;
			g.centeredText(mc.font, clock(m.ticksLeft()), 0, 0, color);
			g.pose().popMatrix();
			rosterBar(g, w / 2, 22);
			if (ClientState.myRole().survivor()) {
				Component score = Component.translatable("halloween.hud.my_score", m.myScore());
				g.text(mc.font, score, w - 10 - mc.font.width(score), 10, TEXT, true);
			}
		}
	}

	/** Status of every exit of this match, one line each. */
	private static void exitLines(GuiGraphicsExtractor g, Font font, int x, int y) {
		HwNet.MatchSync m = ClientState.match;
		if (m.state() != 2) {
			return;
		}
		int gateSeconds = dev.halloween.HwConfig.get().gateOpenLastSeconds * 20;
		for (HwNet.ExitInfo e : m.exits()) {
			Component line;
			int color = 0xFFB8C2DA;
			switch (e.type()) {
				case 0 -> {
					line = Component.translatable("halloween.hud.exit.cellar." + e.state());
					color = e.state() == 1 ? 0xFF7FE07F : color;
				}
				case 1 -> {
					if ((e.state() & 8) != 0 && (e.state() & 4) == 0) {
						line = Component.translatable("halloween.hud.exit.car.running", e.extra());
						color = 0xFF7FE07F;
					} else if ((e.state() & 4) != 0) {
						line = Component.translatable("halloween.hud.exit.car.gone");
					} else {
						line = Component.translatable("halloween.hud.exit.car", (e.state() & 2) != 0 ? "✔" : "✘", (e.state() & 1) != 0 ? "✔" : "✘");
						color = (e.state() & 3) == 3 ? 0xFF7FE07F : color;
					}
				}
				case 2 -> {
					line = e.state() == 1 || e.state() == 2
						? Component.translatable("halloween.hud.exit.police." + e.state(), clock(e.extra()))
						: Component.translatable("halloween.hud.exit.police." + e.state(), m.reportable());
					color = e.state() == 2 ? 0xFF7FE07F : e.state() == 0 ? 0xFFE8C95A : color;
				}
				default -> {
					if (e.state() == 0) {
						int wait = gateSeconds > 0 ? m.ticksLeft() - gateSeconds : 0;
						line = wait > 0 ? Component.translatable("halloween.hud.exit.gate.0", clock(wait))
							: Component.translatable(e.extra() == 0 ? "halloween.hud.exit.gate.fuse" : "halloween.hud.exit.gate.key");
						color = e.extra() != 0 ? 0xFFE8C95A : color;
					} else {
						line = Component.translatable("halloween.hud.exit.gate." + e.state());
						color = e.state() == 1 ? 0xFF7FE07F : color;
					}
				}
			}
			diamond(g, x + 4, y + 3, 2, color);
			if (e.type() == 2 && e.state() == 2) {
				// The waiting police van: how far, and which way from where you look.
				line = line.copy().append(Component.literal("  " + whereTo(e.pos())).withStyle(net.minecraft.ChatFormatting.GRAY));
			}
			g.text(font, line, x + 12, y, color, true);
			y += 11;
		}
		if (!m.cutPhones().isEmpty()) {
			g.text(font, Component.translatable("halloween.hud.phones_cut", m.cutPhones().size()), x + 12, y, 0xFFE07070, true);
			y += 11;
		}
	}

	/**
	 * Michael's wanted level, top right: a police badge for each call that got through, dim ones for the
	 * calls still to come. Returns where the corner notices can start below it.
	 */
	private static float partialTicks(DeltaTracker delta) {
		return delta.getGameTimeDeltaPartialTick(false);
	}

	/** The minimap in the top right corner (if the match has one) with the wanted badges under it. */
	private static int minimapAndWanted(GuiGraphicsExtractor g, Minecraft mc, int w, float partial) {
		HwNet.MatchSync m = ClientState.match;
		if (m.state() != 2) {
			return 34;
		}
		// Below the survivor's score; the Shape has the corner to himself.
		int y = ClientState.myRole().survivor() ? 22 : 8;
		if (ClientMinimap.active() && !mc.player.isSpectator()) {
			int size = 86;
			int x = w - 10 - size;
			ClientMinimap.render(g, mc, x, y, size, partial);
			return wantedMeter(g, mc, x + size / 2, y + size + 3);
		}
		return wantedMeter(g, mc, -w, y);
	}

	/** The badges centred on {@code centerX} (under the minimap), or right-aligned with a label when -w is passed. */
	private static int wantedMeter(GuiGraphicsExtractor g, Minecraft mc, int centerX, int y) {
		HwNet.MatchSync m = ClientState.match;
		int max = HwConfig.get().policeMaxCalls;
		int size = 18;
		int gap = 3;
		int span = max * size + (max - 1) * gap;
		int x0;
		if (centerX < 0) {
			int w = -centerX;
			x0 = w - 10 - span;
			Component label = Component.translatable("halloween.hud.wanted");
			g.text(mc.font, label, x0 - 6 - mc.font.width(label), y + (size - 8) / 2, m.wanted() > 0 ? TEXT : 0xFFB8C2DA, true);
		} else {
			x0 = centerX - span / 2;
		}
		long t = mc.level.getGameTime();
		for (int i = 0; i < max; i++) {
			int x = x0 + i * (size + gap);
			boolean on = i < m.wanted();
			if (on) {
				// Red and blue like the lights on a patrol car, flashing behind the badge.
				int glow = (t / 8 + i) % 2 == 0 ? 0x70D03A3A : 0x703A6AD8;
				g.fill(x - 2, y - 2, x + size + 2, y + size + 2, glow);
			}
			g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, BADGE, x, y, 0, 0, size, size, 16, 16, 16, 16, on ? 0xFFFFFFFF : 0x80484848);
		}
		// The police van waiting: a small blue arrow under the badges shows survivors which way it is.
		Vec3 van = ClientState.myRole().survivor() && !mc.player.isSpectator() ? waitingVan() : null;
		if (van != null) {
			Vec3 to = van.subtract(mc.player.position());
			double angle = Math.toRadians(Mth.wrapDegrees(Math.toDegrees(Math.atan2(to.z, to.x)) - 90 - mc.player.getYRot()));
			arrow(g, x0 + span / 2, y + size + 10, angle, (t / 10) % 2 == 0 ? 0xFF5A9CFF : 0xFF3A6AD8);
			return y + size + 26;
		}
		return y + size + 10;
	}

	/** Where the police van waits for survivors (null: it doesn't). */
	private static Vec3 waitingVan() {
		for (HwNet.ExitInfo e : ClientState.match.exits()) {
			if (e.type() == 2 && e.state() == 2) {
				return Vec3.atCenterOf(e.pos());
			}
		}
		return null;
	}

	/** A small arrow centred on (cx, cy), pointing {@code angle} radians clockwise from straight up. */
	static void arrow(GuiGraphicsExtractor g, int cx, int cy, double angle, int color) {
		double sin = Math.sin(angle);
		double cos = Math.cos(angle);
		for (int py = -7; py <= 7; py++) {
			for (int px = -7; px <= 7; px++) {
				// Back into the arrow's own frame (tip up at -6, base at +5, a notch in the middle of the base).
				double ax = px * cos + py * sin;
				double ay = -px * sin + py * cos;
				boolean head = ay >= -6 && ay <= 5 && Math.abs(ax) <= (ay + 6) * 0.45;
				boolean notch = ay > 1.5 && Math.abs(ax) < (ay - 1.5) * 1.2;
				if (head && !notch) {
					g.fill(cx + px, cy + py, cx + px + 1, cy + py + 1, color);
				}
			}
		}
	}

	/** Distance and an arrow towards a spot, as seen from where the player looks: "45 m ↗". */
	private static String whereTo(net.minecraft.core.BlockPos pos) {
		Minecraft mc = Minecraft.getInstance();
		Vec3 to = Vec3.atCenterOf(pos).subtract(mc.player.position());
		int dist = (int) Math.round(Math.sqrt(to.x * to.x + to.z * to.z));
		double angle = Math.toDegrees(Math.atan2(to.z, to.x)) - 90 - mc.player.getYRot();
		angle = Mth.wrapDegrees(angle);
		String[] arrows = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
		int sector = (int) Math.floorMod(Math.round(angle / 45.0), 8);
		return Component.translatable("halloween.hud.meters", dist).getString() + " " + arrows[sector];
	}

	/** A hand and three pockets, bottom centre. */
	private static void inventoryBar(GuiGraphicsExtractor g, Minecraft mc, int w, int h) {
		var inv = mc.player.getInventory();
		int slots = dev.halloween.game.Inventory.SLOTS;
		int size = 22;
		int gap = 8;
		int x0 = w / 2 - (slots * size + gap) / 2;
		int y = h - size - 6;
		for (int i = 0; i < slots; i++) {
			// The rounds' pocket stands apart, at the right end.
			int x = x0 + i * size + (i == dev.halloween.game.Inventory.AMMO_SLOT ? gap : 0);
			boolean selected = inv.getSelectedSlot() == i;
			g.fill(x, y, x + size - 2, y + size - 2, selected ? 0xC0E8E2D6 : 0x90000000);
			g.fill(x + 1, y + 1, x + size - 3, y + size - 3, 0xC0181818);
			var stack = inv.getItem(i);
			if (!stack.isEmpty()) {
				g.item(stack, x + 2, y + 2);
				// Guns show the rounds in them; stacks their count; the flashlight its battery.
				dev.halloween.game.HwItems.Kind kind = dev.halloween.game.HwItems.kindOf(stack);
				String count = kind != null && kind.gun() ? String.valueOf(dev.halloween.game.Guns.loaded(stack)) : null;
				g.itemDecorations(mc.font, stack, x + 2, y + 2, count);
			}
		}
		var held = inv.getItem(inv.getSelectedSlot());
		if (!held.isEmpty()) {
			Component name = held.getHoverName();
			g.centeredText(mc.font, name, w / 2, y - 11, TEXT);
		}
	}

	/** With a gun in hand: a small dot from the hip (aimed, only the gun's own sights), and the rounds in it and in the pockets. */
	private static void gunHud(GuiGraphicsExtractor g, Minecraft mc, int w, int h) {
		dev.halloween.game.HwItems.Kind kind = ClientGuns.heldGun(mc);
		if (kind == null || mc.gui.screen() != null) {
			return;
		}
		int cx = w / 2;
		int cy = h / 2;
		if (!ClientGuns.aiming()) {
			g.fill(cx, cy, cx + 1, cy + 1, 0x90E8E2D6);
		}
		var held = mc.player.getMainHandItem();
		int loaded = dev.halloween.game.Guns.loaded(held);
		int cap = dev.halloween.game.Guns.capacity(kind);
		int spare = 0;
		var inv = mc.player.getInventory();
		dev.halloween.game.HwItems.Kind ammo = dev.halloween.game.Guns.ammoFor(kind);
		for (int i = 0; i < dev.halloween.game.Inventory.SLOTS; i++) {
			if (dev.halloween.game.HwItems.is(inv.getItem(i), ammo)) {
				spare += inv.getItem(i).getCount();
			}
		}
		StringBuilder rounds = new StringBuilder();
		for (int i = 0; i < cap; i++) {
			rounds.append(i < loaded ? "●" : "○");
		}
		Component line = Component.translatable("halloween.hud.ammo", rounds.toString(), spare);
		g.text(mc.font, line, w - 10 - mc.font.width(line), h - 48, loaded == 0 ? BLOOD : TEXT, true);
	}

	/** The Shape's stun meter, knock-down and the flashlight glare. */
	private static void stunAndBlind(GuiGraphicsExtractor g, Minecraft mc, HwNet.ShapeHud hud, int w, int h) {
		// In the headset the glare and the daze are in the world's fog (ClientVr.fogTint), not on the panel.
		boolean vr = ClientVr.active();
		if (hud.blind() && !vr) {
			g.fill(0, 0, w, h, 0xF0F4F2EA);
			g.fillGradient(0, 0, w, h, 0x00FFFFFF, 0x60E0D8C0);
		}
		if (hud.stunnedTicks() > 0) {
			if (!vr) {
				g.fill(0, 0, w, h, 0x50000000);
			}
			g.pose().pushMatrix();
			g.pose().translate(w / 2f, h / 2f - 30);
			g.pose().scale(2f, 2f);
			g.centeredText(mc.font, Component.translatable("halloween.hud.stunned"), 0, 0, BLOOD);
			g.pose().popMatrix();
		} else if (hud.stun() > 0.01f) {
			int x = 10;
			int y = h - 70;
			g.text(mc.font, Component.translatable("halloween.hud.stun"), x, y - 10, DIM, true);
			bar(g, x, y, 48, 3, hud.stun(), 0xFFE0C060);
		}
	}

	private static void endScreen(GuiGraphicsExtractor g, Minecraft mc, int w, int h) {
		HwNet.MatchEnd end = ClientState.matchEnd;
		if (end == null || System.currentTimeMillis() - ClientState.matchEndAt > 15000) {
			return;
		}
		int lines = end.lines().size();
		int boxH = 30 + lines * 12;
		int y0 = h / 2 - boxH / 2;
		g.fill(w / 2 - 150, y0, w / 2 + 150, y0 + boxH, 0xD0000000);
		g.centeredText(mc.font, Component.translatable("halloween.hud.end_title"), w / 2, y0 + 8, DIM);
		int y = y0 + 22;
		for (Component line : end.lines()) {
			g.centeredText(mc.font, line, w / 2, y, TEXT);
			y += 12;
		}
	}

	// ---------------------------------------------------------------- world markers

	/** Shape Jump: tall light beams rising over the victims. */
	private static void beams(GuiGraphicsExtractor g, HwNet.ShapeHud hud, int w, int h) {
		if (ClientVr.active()) {
			hud.beams().forEach(VrMarks::beam);
			return;
		}
		for (Vec3 base : hud.beams()) {
			Projection.Point bottom = Projection.toScreen(base, w, h);
			Projection.Point top = Projection.toScreen(base.add(0, 80, 0), w, h);
			if (bottom == null || top == null) {
				continue;
			}
			int half = (int) Mth.clamp(60 / Math.max(4, bottom.distance()), 2, 10);
			int x = (int) bottom.x();
			int y0 = (int) Math.max(-10, top.y());
			int y1 = (int) Math.min(h + 10, bottom.y());
			if (y1 <= y0) {
				continue;
			}
			g.fillGradient(x - half, y0, x + half, y1, 0x00FFD24A, 0xA0FFD24A);
			g.fillGradient(x - half / 3 - 1, y0, x + half / 3 + 1, y1, 0x00FFF2C0, 0xD0FFF2C0);
		}
	}

	/**
	 * Footsteps he hears (game.Noise): rings spreading where they came from, louder ones bigger and paler; out of
	 * view, a small arrow round the crosshair shows which way.
	 */
	private static void noises(GuiGraphicsExtractor g, Minecraft mc, int w, int h, float partial) {
		long now = mc.level.getGameTime();
		ClientState.NOISES.removeIf(n -> now - n.at() > 30);
		for (ClientState.NoiseMark n : ClientState.NOISES) {
			float age = now - n.at() + partial;
			int alpha = (int) (220 * Mth.clamp(1 - age / 30f, 0, 1));
			int color = (alpha << 24) | (n.loud() ? 0xFFE6B0 : 0xC8C8C8);
			if (ClientVr.active()) {
				VrMarks.add(n.pos().add(0, 0.3, 0), "◯", color, (n.loud() ? 2.2f : 1.6f) * (1f + age * 0.03f));
				continue;
			}
			Projection.Point p = Projection.toScreen(n.pos().add(0, 0.3, 0), w, h);
			if (p != null && p.x() >= 0 && p.x() <= w && p.y() >= 0 && p.y() <= h) {
				float base = (float) Mth.clamp(160 / Math.max(4, p.distance()), 4, 22) * (n.loud() ? 1.4f : 1f);
				ring(g, p.x(), p.y(), base + age * 0.6f, 2, 0f, 1f, color);
				ring(g, p.x(), p.y(), base * 0.5f + age * 0.3f, 1, 0f, 1f, color);
				continue;
			}
			Vec3 to = n.pos().subtract(mc.player.position());
			double angle = Math.toRadians(Mth.wrapDegrees(Math.toDegrees(Math.atan2(to.z, to.x)) - 90 - mc.player.getYRot()));
			int r = 36;
			arrow(g, w / 2 + (int) Math.round(Math.sin(angle) * r), h / 2 - (int) Math.round(Math.cos(angle) * r), angle, color);
		}
	}

	/**
	 * The Demogorgon down in the Upside Down: every house with a survivor in it outlined in red through the walls
	 * (the box of it, not where in it), and the murk of the place: a red vignette, lightning now and then.
	 */
	private static void upsideDown(GuiGraphicsExtractor g, Minecraft mc, int w, int h, float partial) {
		if (!MichaelRenderer.demogorgon() || !ClientState.demo.below()) {
			return;
		}
		float flash = ClientUpsideDown.flash(partial);
		// The vignette, pulsing slowly; a flash of lightning over everything.
		int pulse = (int) (70 + 30 * Mth.sin((mc.level.getGameTime() + partial) * 0.05f));
		// In the headset: neither (the panel is not the view); the flash reddens the fog instead (ClientVr.fogTint).
		for (int i = 0; i < (ClientVr.active() ? 0 : 6); i++) {
			int a = Math.max(0, pulse - i * 12);
			int edge = (i + 1) * Math.max(w, h) / 36;
			int c = (a << 24) | 0x3A0006;
			g.fill(0, 0, w, edge, c);
			g.fill(0, h - edge, w, h, c);
			g.fill(0, 0, edge, h, c);
			g.fill(w - edge, 0, w, h, c);
		}
		if (flash > 0.01f && !ClientVr.active()) {
			g.fill(0, 0, w, h, ((int) (flash * 120) << 24) | 0xFF4030);
		}
		for (Vec3 heart : ClientUpsideDown.hearts()) {
			heart(g, heart, w, h, partial);
		}
	}


	private static final net.minecraft.resources.Identifier HEART_TEXTURE = dev.halloween.Halloween.id("textures/gui/heart.png");
	/** The curse on a survivor's screen, by stage (tools/curse_veins.pl). */
	private static final net.minecraft.resources.Identifier[] CURSE_VEINS = {dev.halloween.Halloween.id("textures/gui/curse_veins_1.png"), dev.halloween.Halloween.id("textures/gui/curse_veins_2.png"),
		dev.halloween.Halloween.id("textures/gui/curse_veins_3.png"), dev.halloween.Halloween.id("textures/gui/curse_veins_4.png")};

	/**
	 * A house with someone in it, seen from the Upside Down: a heart hanging over it, beating (a double beat),
	 * glowing, rings of the beat spreading out from it; through any wall, smaller and dimmer far off.
	 */
	private static void heart(GuiGraphicsExtractor g, Vec3 at, int w, int h, float partial) {
		if (ClientVr.active()) {
			VrMarks.add(at, "❤", 0xFFC01818, 3.2f * (1f + 0.18f * ClientUpsideDown.beat(partial)));
			return;
		}
		Projection.Point p = Projection.toScreen(at, w, h);
		if (p == null) {
			return;
		}
		float beat = ClientUpsideDown.beat(partial);
		float near = (float) Mth.clamp(1.0 - p.distance() / 90.0, 0.25, 1.0);
		// The heart drawn for it (tools/heart_texture.pl), swelling with each beat.
		int size = Math.round((22f + 30f * near) * (1f + 0.18f * beat));
		int alpha = (int) (120 + 135 * near);
		int left = Math.round(p.x()) - size / 2;
		int top = Math.round(p.y()) - size / 2;
		// A dark-red haze round it, swelling with the beat.
		int glow = (int) ((25 + 55 * beat) * near);
		int halo = size / 4;
		g.fill(left - halo, top - halo, left + size + halo, top + size + halo, (glow / 3 << 24) | 0x5A0006);
		g.fill(left, top, left + size, top + size, (glow / 2 << 24) | 0x7A0008);
		g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, HEART_TEXTURE, left, top, 0, 0, size, size, 48, 48, 48, 48,
			(alpha << 24) | 0xFFFFFF);
		// The beat going out in rings.
		float phase = ClientUpsideDown.beatPhase(partial);
		int ringAlpha = (int) (alpha * 0.8f * (1f - phase));
		if (ringAlpha > 8) {
			ring(g, p.x(), p.y(), size * (0.6f + 1.6f * phase), 2, 0f, 1f, (ringAlpha << 24) | 0x9A1010);
		}
	}

	/** Detection Impulse: indoor victims only give away a fuzzy spot on their building. */
	private static void impulseSpots(GuiGraphicsExtractor g, Minecraft mc, int w, int h, float partial) {
		if (!ClientState.impulseActive()) {
			return;
		}
		float age = mc.level.getGameTime() - ClientState.impulseStart + partial;
		float life = Math.max(1, ClientState.impulseUntil - ClientState.impulseStart);
		int alpha = (int) (255 * Mth.clamp(1 - age / life, 0, 1));
		for (Vec3 spot : ClientState.impulseSpots) {
			if (ClientVr.active()) {
				VrMarks.add(spot.add(0, 1.5, 0), "◎", (alpha << 24) | (ORANGE & 0xFFFFFF), 2.4f);
				continue;
			}
			Projection.Point p = Projection.toScreen(spot.add(0, 1.5, 0), w, h);
			if (p == null) {
				continue;
			}
			int r = (int) Mth.clamp(240 / Math.max(6, p.distance()), 6, 30);
			int color = (alpha << 24) | (ORANGE & 0xFFFFFF);
			ring(g, p.x(), p.y(), r + (age % 20) * 0.6f, 2, 0f, 1f, color);
			ring(g, p.x(), p.y(), r * 0.5f, 2, 0f, 1f, color);
		}
	}

	/** Survivors: where a townsperson's scream says the Shape was. Off screen, an arrow at the edge. */
	private static void alerts(GuiGraphicsExtractor g, Minecraft mc, int w, int h, float partial) {
		long now = mc.level.getGameTime();
		ClientState.ALERTS.removeIf(a -> a.until() < now);
		for (ClientState.AlertMark alert : ClientState.ALERTS) {
			float left = alert.until() - now - partial;
			int alpha = (int) (255 * Mth.clamp(left / 30f, 0.2f, 1f));
			int color = (alpha << 24) | (BLOOD & 0xFFFFFF);
			Vec3 spot = alert.pos().add(0, 1.6, 0);
			int dist = (int) Math.round(mc.player.position().distanceTo(alert.pos()));
			Component label = Component.translatable("halloween.hud.alert", dist);
			if (ClientVr.active()) {
				VrMarks.add(spot, "!", color, 3f);
				VrMarks.add(spot.add(0, -0.35, 0), label, color, 1f);
				continue;
			}
			Projection.Point p = Projection.toScreen(spot, w, h);
			if (p != null && p.x() >= 0 && p.x() <= w && p.y() >= 0 && p.y() <= h) {
				g.pose().pushMatrix();
				g.pose().translate(p.x(), p.y() - 10);
				g.pose().scale(2.5f, 2.5f);
				g.centeredText(mc.font, Component.literal("!"), 0, 0, color);
				g.pose().popMatrix();
				g.centeredText(mc.font, label, (int) p.x(), (int) p.y() + 12, color);
				continue;
			}
			// Behind or beside: which way to turn.
			Vec3 to = alert.pos().subtract(mc.player.getEyePosition(partial));
			Vec3 look = mc.player.getViewVector(partial);
			double cross = look.x * to.z - look.z * to.x;
			boolean right = cross > 0;
			int x = right ? w - 28 : 28;
			g.centeredText(mc.font, Component.literal(right ? "! ▶" : "◀ !"), x, h / 2 - 20, color);
			g.centeredText(mc.font, label, x, h / 2 - 8, color);
		}
	}

	/** Killer Sense: focus points on each victim, stalk level pips and name above the head. */
	private static void victimMarkers(GuiGraphicsExtractor g, Minecraft mc, int w, int h, float partial) {
		for (HwNet.VictimInfo info : ClientState.VICTIMS.values()) {
			Entity entity = mc.level.getEntity(info.entityId());
			if (!(entity instanceof LivingEntity living) || !ClientState.VISIBLE.contains(info.entityId())) {
				continue;
			}
			if (info.stalk() < 3f) {
				for (int i = 0; i < FocusPoints.COUNT; i++) {
					if ((info.popped() & 1 << i) != 0) {
						continue;
					}
					if (ClientVr.active()) {
						float crack = ((info.cracks() >> (i * 6)) & 63) / 63f;
						VrMarks.add(FocusPoints.get(living, i, partial), "●", crack > 0.01f ? 0xFFDDEEFF : 0xFFF2F2F2, 0.9f + crack);
						continue;
					}
					Projection.Point p = Projection.toScreen(FocusPoints.get(living, i, partial), w, h);
					if (p != null) {
						float crack = ((info.cracks() >> (i * 6)) & 63) / 63f;
						crackedIcon(g, mc, i, p.x(), p.y(), crack, partial);
					}
				}
			}
			Vec3 head = living.getPosition(partial).add(0, living.getBbHeight() + 0.45, 0);
			if (ClientVr.active()) {
				int level = (int) info.stalk();
				VrMarks.add(head.add(0, 0.25, 0), info.name(), info.marked() ? 0xFFE0261F : 0xFFDDDDDD, 1f);
				VrMarks.add(head, "◆".repeat(level) + "◇".repeat(Math.max(0, 3 - level)), info.marked() ? 0xFFE0261F : 0xFFF2F2F2, 0.9f);
				continue;
			}
			Projection.Point p = Projection.toScreen(head, w, h);
			if (p == null) {
				continue;
			}
			int x = (int) p.x();
			int y = (int) p.y();
			g.centeredText(mc.font, info.name(), x, y - 14, info.marked() ? 0xFFE0261F : 0xFFDDDDDD);
			int level = (int) info.stalk();
			float partialLevel = info.stalk() - level;
			for (int i = 0; i < 3; i++) {
				int cx = x - 9 + i * 9;
				int fillColor = i < level ? (info.marked() ? 0xFFE0261F : 0xFFF2F2F2) : 0x80303030;
				diamond(g, cx, y - 2, 3, fillColor);
				if (i == level && partialLevel > 0) {
					diamond(g, cx, y - 2, Math.max(1, (int) (3 * partialLevel)), 0xFFB0B0B0);
				}
			}
		}
	}

	/**
	 * A focus point that is being stalked on: as it nears bursting it trembles harder and harder, whitens, and
	 * cracks spread out of it (it bursts at 1, see MatchManager).
	 */
	private static void crackedIcon(GuiGraphicsExtractor g, Minecraft mc, int index, float fx, float fy, float k, float partial) {
		if (k <= 0.01f) {
			focusIcon(g, index, fx, fy);
			return;
		}
		float t = mc.level.getGameTime() + partial;
		float shake = k * k * 2.2f;
		float x = fx + Mth.sin(t * 2.9f + index) * shake;
		float y = fy + Mth.cos(t * 3.7f + index * 2) * shake;
		focusIcon(g, index, x, y);
		// A pale glow swelling round it.
		int glow = (int) (90 * k);
		ring(g, x, y, 5f + 2f * k, 1, 0f, 1f, (glow << 24) | 0xDDEEFF);
		// The cracks: up to six short jagged lines out from the middle, longer as it goes.
		int lines = 1 + (int) (k * 5.99f);
		float len = 2f + 7f * k;
		int col = ((int) (150 + 100 * k) << 24) | 0xF4FAFF;
		for (int l = 0; l < lines; l++) {
			float a = l * 1.047f + index * 0.7f;
			float px = x;
			float py = y;
			for (int s = 1; s <= 3; s++) {
				float r = len * s / 3f;
				float jag = (s % 2 == 0 ? 0.35f : -0.25f);
				float nx = x + Mth.cos(a + jag) * r;
				float ny = y + Mth.sin(a + jag) * r;
				line(g, px, py, nx, ny, col);
				px = nx;
				py = ny;
			}
		}
	}

	/** A thin line as a row of single pixels. */
	private static void line(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1, int color) {
		int n = (int) Math.max(1, Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0)));
		for (int i = 0; i <= n; i++) {
			int x = Math.round(Mth.lerp(i / (float) n, x0, x1));
			int y = Math.round(Mth.lerp(i / (float) n, y0, y1));
			g.fill(x, y, x + 1, y + 1, color);
		}
	}

	private static void focusIcon(GuiGraphicsExtractor g, int index, float fx, float fy) {
		int x = (int) fx;
		int y = (int) fy;
		int c = 0xF0F4F1E8;
		switch (index) {
			case 0 -> {
				diamond(g, x, y, 4, c);
				diamond(g, x, y, 2, 0xF0202020);
			}
			case 1 -> ring(g, x, y, 3.5f, 1, 0f, 1f, 0xF09CC8FF);
			case 2 -> g.fill(x - 2, y - 2, x + 2, y + 2, 0xF0101010);
			case 3 -> ring(g, x, y, 2.5f, 1, 0f, 1f, c);
			default -> {
				g.fill(x - 2, y - 2, x, y, c);
				g.fill(x, y, x + 2, y + 2, c);
				g.fill(x - 2, y + 2, x, y + 4, c);
			}
		}
	}

	/** Centre reticle: a small dot normally, a ring in Killer Sense that turns green on a focus point. */
	private static void reticle(GuiGraphicsExtractor g, Minecraft mc, HwNet.ShapeHud hud, int w, int h, float partial) {
		// Everything the Shape does follows the ray through the screen centre, so the reticle lives there.
		int cx = w / 2;
		int cy = h / 2;
		if (!hud.sense()) {
			g.fill(cx - 1, cy - 1, cx + 1, cy + 1, 0xB0FFFFFF);
			return;
		}
		boolean onPoint = false;
		Vec3 eye = CameraAim.origin();
		Vec3 look = CameraAim.direction();
		float limit = HwConfig.get().reticleFocusAngleDeg;
		for (HwNet.VictimInfo info : ClientState.VICTIMS.values()) {
			if (mc.level.getEntity(info.entityId()) instanceof LivingEntity living && info.stalk() < 3f && ClientState.VISIBLE.contains(info.entityId())) {
				for (int i = 0; i < FocusPoints.COUNT && !onPoint; i++) {
					if ((info.popped() & 1 << i) != 0) {
						continue;
					}
					Vec3 dir = FocusPoints.get(living, i, partial).subtract(eye).normalize();
					onPoint = Math.toDegrees(Math.acos(Mth.clamp(look.dot(dir), -1, 1))) <= limit;
				}
			}
		}
		ring(g, cx, cy, 8, 2, 0f, 1f, onPoint ? GREEN : 0xC0E8E8E8);
		g.centeredText(mc.font, Component.translatable("halloween.hud.focus", mc.options.keyUse.getTranslatedKeyMessage()), cx, cy + 16, 0xA0E8E2D6);
	}

	// ---------------------------------------------------------------- grab

	/**
	 * A key shown in a small space, centred on (cx, cy): a mouse with the button lit for a mouse button, otherwise the
	 * key's name in a keycap.
	 */
	private static void keyGlyph(GuiGraphicsExtractor g, Font font, net.minecraft.client.KeyMapping key, int cx, int cy) {
		String id = key.saveString();
		if (id.startsWith("key.mouse.")) {
			boolean left = id.equals("key.mouse.left");
			boolean right = id.equals("key.mouse.right");
			int x0 = cx - 5;
			int y0 = cy - 7;
			// The body: rounded, an outline with a dark fill, a line down between the buttons.
			g.fill(x0 + 1, y0, x0 + 9, y0 + 14, 0xFFE8E2D4);
			g.fill(x0, y0 + 1, x0 + 10, y0 + 13, 0xFFE8E2D4);
			g.fill(x0 + 1, y0 + 1, x0 + 9, y0 + 13, 0xFF1A1414);
			g.fill(x0 + 1, y0 + 6, x0 + 9, y0 + 7, 0xFFE8E2D4);
			g.fill(x0 + 4, y0 + 1, x0 + 6, y0 + 6, 0xFFE8E2D4);
			if (left) {
				g.fill(x0 + 1, y0 + 1, x0 + 4, y0 + 6, BLOOD);
			} else if (right) {
				g.fill(x0 + 6, y0 + 1, x0 + 9, y0 + 6, BLOOD);
			} else {
				g.fill(x0 + 4, y0 + 2, x0 + 6, y0 + 5, BLOOD);
			}
			return;
		}
		Component name = key.getTranslatedKeyMessage();
		int tw = font.width(name);
		int half = Math.max(5, tw / 2 + 3);
		g.fill(cx - half, cy - 7, cx + half, cy + 6, 0xFFE8E2D4);
		g.fill(cx - half + 1, cy - 6, cx + half - 1, cy + 5, 0xFF1A1414);
		g.centeredText(font, name, cx, cy - 4, TEXT);
	}

	/** The Shape's side of a grab: a ring with a rotating needle and a target arc. */
	private static void shapeGrab(GuiGraphicsExtractor g, Minecraft mc, int w, int h, float partial) {
		HwNet.Qte qte = ClientState.qte;
		int cx = w / 2;
		int cy = h / 2 + 40;
		float r = 22;
		// Between two checks there is a short pause: only the score shows.
		if (mc.level.getGameTime() - qte.roundStart() + partial >= 0) {
			ring(g, cx, cy, r, 5, 0f, 1f, 0xC0303030);
			ring(g, cx, cy, r, 5, qte.zoneStart(), qte.zoneStart() + qte.zoneWidth(), 0xFFC9C25A);
			double elapsed = Math.max(0, mc.level.getGameTime() - qte.roundStart() + partial);
			float needle = HwNet.needle(elapsed, qte.period());
			double a = needle * Math.PI * 2 - Math.PI / 2;
			for (float t = 0; t <= r + 4; t += 1f) {
				int x = (int) (cx + Math.cos(a) * t);
				int y = (int) (cy + Math.sin(a) * t);
				g.fill(x, y, x + 2, y + 2, 0xFFFFFFFF);
			}
			keyGlyph(g, mc.font, mc.options.keyAttack, cx, cy);
		}
		pips(g, cx, cy + (int) r + 10, qte.points(), qte.needed(), BLOOD);
		pips(g, cx, cy + (int) r + 18, qte.enemyPoints(), qte.enemyNeeded(), 0xFFF2F2F2);
		int y = cy + (int) r + 26;
		if (qte.window()) {
			g.centeredText(mc.font, Component.translatable("halloween.hud.grab.window"), cx, y, TEXT);
			y += 10;
		}
		if (qte.bloody()) {
			g.centeredText(mc.font, Component.translatable("halloween.hud.grab.bloody"), cx, y, BLOOD);
		}
	}

	/** A circular reaction check during a call or a talk; plus how to break a talk off. */
	private static void skillCheck(GuiGraphicsExtractor g, Minecraft mc, int w, int h, float partial) {
		if (ClientState.talking()) {
			Component hint = Component.translatable("halloween.hud.talk_cancel");
			g.centeredText(mc.font, hint, w / 2, h - 60, DIM);
		}
		HwNet.SkillCheck check = ClientState.skill;
		if (!check.active()) {
			return;
		}
		double elapsed = Math.max(0, mc.level.getGameTime() - check.start() + partial);
		drawCheck(g, mc, w / 2, h / 2 + 44, 20, check.kind(), check.zoneStart(), check.zoneWidth(), check.zone2(), check.need(), check.stage(),
			elapsed, check.period(), 0xFFF2F2F2, BLOOD);
	}

	/**
	 * One reaction check of any kind (see HwNet.Check), centred on cx, cy: a needle round a ring (one arc, or two to hit
	 * in turn), a cursor along a bar, a circle closing in on a band, or taps to fill.
	 */
	private static void drawCheck(GuiGraphicsExtractor g, Minecraft mc, int cx, int cy, float r, int kind, float zoneStart, float zoneWidth,
		float zone2, int need, int stage, double elapsed, int period, int zoneColor, int markColor) {
		Component key = mc.options.keyAttack.getTranslatedKeyMessage();
		float mark = HwNet.Check.mark(kind, elapsed, period);
		switch (kind) {
			case HwNet.Check.BAR -> {
				int barW = (int) (r * 8);
				int x = cx - barW / 2;
				g.fill(x, cy - 5, x + barW, cy + 5, 0xFF1A1A1A);
				g.fill(x + (int) (barW * zoneStart), cy - 5, x + (int) (barW * (zoneStart + zoneWidth)), cy + 5, zoneColor);
				int m = x + (int) (barW * mark);
				g.fill(m - 1, cy - 8, m + 1, cy + 8, markColor);
				g.centeredText(mc.font, key, cx, cy - 20, TEXT);
			}
			case HwNet.Check.PULSE -> {
				// The band to catch, and the circle closing in on it.
				ring(g, cx, cy, r * (zoneStart + zoneWidth / 2) + 1, Math.max(2, (int) (r * zoneWidth)), 0f, 1f, zoneColor);
				ring(g, cx, cy, Math.max(1, r * mark), 2, 0f, 1f, markColor);
				g.centeredText(mc.font, key, cx, cy - 4, TEXT);
			}
			case HwNet.Check.MASH -> {
				g.centeredText(mc.font, Component.translatable("halloween.hud.check.mash", key), cx, cy - 12, TEXT);
				pips(g, cx, cy, stage, need, zoneColor);
				int barW = (int) (r * 6);
				bar(g, cx - barW / 2, cy + 8, barW, 2, 1f - mark, DIM);
			}
			default -> {
				ring(g, cx, cy, r, 4, 0f, 1f, 0xC0303030);
				ring(g, cx, cy, r, 4, zoneStart, zoneStart + zoneWidth, kind == HwNet.Check.DOUBLE && stage > 0 ? 0xFF5A5A5A : zoneColor);
				if (kind == HwNet.Check.DOUBLE) {
					ring(g, cx, cy, r, 4, zone2, zone2 + zoneWidth, stage > 0 ? YELLOW : 0xFF8A8A6A);
				}
				double a = mark * Math.PI * 2 - Math.PI / 2;
				for (float t = 0; t <= r + 3; t += 1f) {
					int x = (int) (cx + Math.cos(a) * t);
					int y = (int) (cy + Math.sin(a) * t);
					g.fill(x, y, x + 2, y + 2, markColor);
				}
				g.centeredText(mc.font, key, cx, cy - 4, TEXT);
			}
		}
	}

	private static void victimQte(GuiGraphicsExtractor g, Minecraft mc, int w, int h, float partial) {
		HwNet.Qte qte = ClientState.qte;
		int barW = 180;
		int x = w / 2 - barW / 2;
		int y = (int) (h * 0.62);
		g.fill(x - 6, y - 22, x + barW + 6, y + 34, 0xB0000000);
		g.centeredText(mc.font, Component.translatable("halloween.hud.qte", mc.options.keyAttack.getTranslatedKeyMessage()), w / 2, y - 16, TEXT);

		if (mc.level.getGameTime() - qte.roundStart() + partial >= 0) {
			double elapsed = mc.level.getGameTime() - qte.roundStart() + partial;
			if (qte.kind() == HwNet.Check.BAR) {
				g.fill(x, y, x + barW, y + 10, 0xFF1A1A1A);
				int z0 = x + (int) (barW * qte.zoneStart());
				int z1 = x + (int) (barW * (qte.zoneStart() + qte.zoneWidth()));
				g.fill(z0, y, z1, y + 10, 0xFF3FA34D);
				float cursor = HwNet.qteCursor(Math.max(0, elapsed), qte.period());
				int cx = x + (int) (barW * cursor);
				g.fill(cx - 1, y - 3, cx + 1, y + 13, 0xFFFFFFFF);
			} else {
				// This round's check is another kind: drawn above the bar's place.
				drawCheck(g, mc, w / 2, y - 44, 18, qte.kind(), qte.zoneStart(), qte.zoneWidth(), qte.zone2(), qte.need(), qte.stage(),
					Math.max(0, elapsed), qte.period(), 0xFF3FA34D, 0xFFFFFFFF);
			}
			float timeLeft = 1f - (float) Mth.clamp(elapsed / qte.roundTicks(), 0, 1);
			bar(g, x, y + 13, barW, 2, timeLeft, 0xFF8A847A);
		}

		if (ClientInteractions.hasStab(mc)) {
			g.centeredText(mc.font, Component.translatable("halloween.hud.stab", mc.options.keyUse.getTranslatedKeyMessage()), w / 2, y + 38, YELLOW);
		}
		pips(g, w / 2, y + 20, qte.points(), qte.needed(), 0xFF3FA34D);
		pips(g, w / 2, y + 28, qte.enemyPoints(), qte.enemyNeeded(), BLOOD);
	}

	private static void pips(GuiGraphicsExtractor g, int cx, int y, int filled, int total, int color) {
		for (int i = 0; i < total; i++) {
			int px = cx - total * 6 + i * 12 + 2;
			g.fill(px, y, px + 8, y + 4, i < filled ? color : 0xFF3A3A3A);
		}
	}

	// ---------------------------------------------------------------- panels

	/**
	 * The goals, top left. Michael: kill all the residents; the special targets (the survivors), ticked off as he
	 * gets them. Survivors: help the residents escape; save the special targets (the others), ticked off as they
	 * get out, crossed out if they don't.
	 */
	private static int goals(GuiGraphicsExtractor g, Minecraft mc, boolean shape) {
		Font font = mc.font;
		int y = ClientState.match.state() == 2 ? 26 : 10;
		header(g, font, 10, y, Component.translatable(shape ? "halloween.goal.kill_all" : "halloween.goal.help"), false);
		y += 14;
		HwNet.Roster roster = ClientState.roster;
		java.util.List<Integer> rows = new java.util.ArrayList<>();
		for (int i = 0; i < roster.names().size(); i++) {
			if (shape || !roster.names().get(i).equals(mc.player.getScoreboardName())) {
				rows.add(i);
			}
		}
		if (rows.isEmpty()) {
			return y;
		}
		header(g, font, 10, y, Component.translatable(shape ? "halloween.goal.targets" : "halloween.goal.save"), false);
		y += 13;
		for (int i : rows) {
			int state = roster.states().get(i);
			// Michael ticks off the dead; survivors tick off who got out (and cross out who didn't).
			boolean done = shape ? state == 2 : state == 1;
			boolean lost = !shape && state == 2;
			int cx = 26;
			int cy = y + 4;
			diamond(g, cx, cy, 4, done ? 0xFFB8B2A6 : lost ? 0xFF6A3A3A : 0xFF9A968E);
			diamond(g, cx, cy, 3, 0xF0181818);
			if (done) {
				g.text(font, Component.literal("✔"), cx - 3, cy - 4, 0xFFE8E2D4, false);
			} else if (lost) {
				g.text(font, Component.literal("✘"), cx - 3, cy - 4, 0xFFB04040, false);
			}
			int color = done || lost ? 0xFF7A766E : (shape && state == 1 ? 0xFF6A665E : 0xFFD8D2C4);
			Component name = Component.literal(roster.names().get(i));
			if (shape && state == 1) {
				name = name.copy().append(Component.translatable("halloween.goal.escaped"));
			}
			g.text(font, name, cx + 9, y, color, true);
			y += 12;
		}
		return y;
	}

	/**
	 * Who is left, under the clock: survivors first, then the townsfolk. A red disc: still in it; a door: got
	 * out; a grey diamond: dead.
	 */
	private static void rosterBar(GuiGraphicsExtractor g, int cx, int y) {
		HwNet.Roster r = ClientState.roster;
		java.util.List<int[]> icons = new java.util.ArrayList<>();
		for (int s : r.states()) {
			icons.add(new int[]{s, 1});
		}
		for (int i = 0; i < r.npcAlive(); i++) {
			icons.add(new int[]{0, 0});
		}
		for (int i = 0; i < r.npcRescued(); i++) {
			icons.add(new int[]{1, 0});
		}
		for (int i = 0; i < r.npcDead(); i++) {
			icons.add(new int[]{2, 0});
		}
		int step = 11;
		int x = cx - (icons.size() * step) / 2 + step / 2;
		for (int[] icon : icons) {
			boolean survivor = icon[1] == 1;
			switch (icon[0]) {
				case 0 -> {
					// Still in it: a white diamond.
					diamond(g, x, y, 4, survivor ? 0xFFF2F2F2 : 0xFFC8C8C8);
					diamond(g, x, y, 2, survivor ? 0xFFFFFFFF : 0xFFE0E0E0);
				}
				case 1 -> {
					// Got out: a see-through white door.
					g.fill(x - 3, y - 4, x + 4, y + 5, 0x80FFFFFF);
					g.fill(x - 2, y - 3, x + 3, y + 5, 0x40202020);
					g.fill(x + 1, y, x + 2, y + 1, 0x90FFFFFF);
				}
				default -> skull(g, x, y, survivor ? 0xFFD02020 : 0xFFA02020);
			}
			x += step;
		}
	}

	/** Dead: a little red skull (round top, two eye holes, teeth). */
	private static void skull(GuiGraphicsExtractor g, int x, int y, int c) {
		g.fill(x - 2, y - 4, x + 3, y - 3, c);
		g.fill(x - 3, y - 3, x + 4, y + 2, c);
		g.fill(x - 2, y + 2, x + 3, y + 4, c);
		g.fill(x - 2, y - 1, x, y + 1, 0xFF1A0A0A);
		g.fill(x + 1, y - 1, x + 3, y + 1, 0xFF1A0A0A);
		g.fill(x - 1, y + 3, x, y + 4, 0xFF1A0A0A);
		g.fill(x + 1, y + 3, x + 2, y + 4, 0xFF1A0A0A);
	}

	/** A step of a way out: ticked off when done, plain while still to do. */
	private static int step(GuiGraphicsExtractor g, Font font, int y, Component text, boolean done) {
		int cx = 26;
		int cy = y + 4;
		diamond(g, cx, cy, 4, done ? 0xFFB8B2A6 : 0xFF9A968E);
		diamond(g, cx, cy, 3, 0xF0181818);
		if (done) {
			g.text(font, Component.literal("✔"), cx - 3, cy - 4, 0xFFE8E2D4, false);
		}
		g.text(font, text, cx + 9, y, done ? 0xFF7A766E : 0xFFD8D2C4, true);
		return y + 11;
	}

	private static boolean carrying(Minecraft mc, dev.halloween.game.HwItems.Kind... kinds) {
		var inv = mc.player.getInventory();
		for (int i = 0; i < dev.halloween.game.Inventory.SLOTS; i++) {
			dev.halloween.game.HwItems.Kind k = dev.halloween.game.HwItems.kindOf(inv.getItem(i));
			for (dev.halloween.game.HwItems.Kind want : kinds) {
				if (k == want) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Survivors: "escape with your life", then each way out with its steps, ticked off as they are done. A way out
	 * nobody has started on (and you carry nothing for) shows just its heading and what it needs.
	 */
	private static void escapeSteps(GuiGraphicsExtractor g, Minecraft mc, int y) {
		HwNet.MatchSync m = ClientState.match;
		if (m.state() != 2) {
			return;
		}
		Font font = mc.font;
		header(g, font, 10, y, Component.translatable("halloween.goal.escape"), true);
		y += 15;
		java.util.List<HwNet.ExitInfo> cellars = new java.util.ArrayList<>();
		for (HwNet.ExitInfo e : m.exits()) {
			if (e.type() == 0) {
				cellars.add(e);
			}
		}
		if (!cellars.isEmpty()) {
			boolean opened = cellars.stream().anyMatch(e -> e.state() >= 1);
			long free = cellars.stream().filter(e -> e.state() != 2).count();
			header(g, font, 10, y, Component.translatable("halloween.escape.cellar", free), false);
			y += 13;
			if (opened || carrying(mc, dev.halloween.game.HwItems.Kind.BOLT_CUTTERS, dev.halloween.game.HwItems.Kind.PADLOCK_KEY)) {
				y = step(g, font, y, Component.translatable("halloween.escape.cellar.1"), opened);
				y = step(g, font, y, Component.translatable("halloween.escape.cellar.2"), false);
			} else {
				y = needs(g, font, y, "halloween.escape.cellar.need");
			}
		}
		int gateSeconds = dev.halloween.HwConfig.get().gateOpenLastSeconds * 20;
		for (HwNet.ExitInfo e : m.exits()) {
			int s = e.state();
			switch (e.type()) {
				case 1 -> {
					boolean gone = (s & 4) != 0;
					boolean running = (s & 8) != 0;
					header(g, font, 10, y, Component.translatable(gone ? "halloween.escape.car.gone" : "halloween.escape.car"), false);
					y += 13;
					if (gone) {
						break;
					}
					if ((s & 3) != 0 || running || carrying(mc, dev.halloween.game.HwItems.Kind.REPAIR_KIT, dev.halloween.game.HwItems.Kind.GAS_CAN,
						dev.halloween.game.HwItems.Kind.SEDAN_KEYS)) {
						y = step(g, font, y, Component.translatable("halloween.escape.car.1"), (s & 2) != 0);
						y = step(g, font, y, Component.translatable("halloween.escape.car.2"), (s & 1) != 0);
						y = step(g, font, y, Component.translatable("halloween.escape.car.3"), running);
						y = step(g, font, y, running ? Component.translatable("halloween.escape.car.4.seats", e.extra())
							: Component.translatable("halloween.escape.car.4"), false);
					} else {
						y = needs(g, font, y, "halloween.escape.car.need");
					}
				}
				case 2 -> {
					header(g, font, 10, y, Component.translatable(s == 3 ? "halloween.escape.police.gone" : "halloween.escape.police"), false);
					y += 13;
					if (s == 3 || s == 4) {
						break;
					}
					boolean found = s != 5;
					boolean called = s == 1 || s == 2;
					y = step(g, font, y, Component.translatable("halloween.escape.police.1"), found);
					if (found) {
						y = step(g, font, y, Component.translatable("halloween.escape.police.2"), called);
						y = step(g, font, y, s == 1 ? Component.translatable("halloween.escape.police.3.eta", clock(e.extra()))
							: Component.translatable("halloween.escape.police.3"), s == 2);
						y = step(g, font, y, s == 2 ? Component.translatable("halloween.escape.police.4.left", clock(e.extra()))
							: Component.translatable("halloween.escape.police.4"), false);
					}
				}
				case 3 -> {
					header(g, font, 10, y, Component.translatable("halloween.escape.gate"), false);
					y += 13;
					int wait = gateSeconds > 0 ? m.ticksLeft() - gateSeconds : 0;
					if (wait > 0) {
						y = needs(g, font, y, Component.translatable("halloween.escape.gate.later", clock(wait)));
					} else if (s >= 1 || e.extra() > 0 || carrying(mc, dev.halloween.game.HwItems.Kind.FUSE, dev.halloween.game.HwItems.Kind.GATE_KEY)) {
						y = step(g, font, y, Component.translatable("halloween.escape.gate.1"), s >= 1 || e.extra() > 0);
						y = step(g, font, y, Component.translatable("halloween.escape.gate.2"), s >= 1);
						y = step(g, font, y, Component.translatable("halloween.escape.gate.3"), false);
					} else {
						y = needs(g, font, y, "halloween.escape.gate.need");
					}
				}
				default -> {
				}
			}
		}
		if (!m.cutPhones().isEmpty()) {
			g.text(font, Component.translatable("halloween.hud.phones_cut", m.cutPhones().size()), 22, y, 0xFFE07070, true);
		}
	}

	private static int needs(GuiGraphicsExtractor g, Font font, int y, String key) {
		return needs(g, font, y, Component.translatable(key));
	}

	private static int needs(GuiGraphicsExtractor g, Font font, int y, Component text) {
		g.text(font, text, 26, y, 0xFF8A847A, true);
		return y + 11;
	}

	/** A goal's heading: a white diamond in a grey one, capitals. */
	private static void header(GuiGraphicsExtractor g, Font font, int x, int y, Component text, boolean exits) {
		int cx = x + 5;
		int cy = y + 4;
		if (exits) {
			g.fill(cx - 4, cy - 4, cx + 4, cy + 5, 0xFF9A968E);
			g.fill(cx - 3, cy - 3, cx + 3, cy + 4, 0xF0181818);
			g.fill(cx - 1, cy - 1, cx + 1, cy + 1, 0xFFE8E2D4);
		} else {
			diamond(g, cx, cy, 5, 0xFF9A968E);
			diamond(g, cx, cy, 4, 0xF0181818);
			diamond(g, cx, cy, 2, 0xFFE8E2D4);
		}
		g.text(font, text.copy().withStyle(net.minecraft.ChatFormatting.BOLD), x + 14, y, 0xFFE8E2D4, true);
	}

	private static int objectives(GuiGraphicsExtractor g, Font font, HwNet.ShapeHud hud) {
		int y = ClientState.match.state() == 2 ? 26 : 10;
		boolean first = true;
		for (Component line : hud.objectives()) {
			diamond(g, 14, y + 3, 3, first ? 0xFFD8D2C4 : 0xFF8FA0C8);
			g.text(font, line, 22, y, first ? TEXT : 0xFFB8C2DA, true);
			y += 12;
			first = false;
		}
		return y;
	}

	/**
	 * XP feed on the right, under the notices: "+075 XP" and the reasons, right-aligned, fading out after a few
	 * seconds. The left side is the lists' (objectives, ways out), however long they get.
	 */
	private static void feed(GuiGraphicsExtractor g, Font font, int w, int h) {
		long now = System.currentTimeMillis();
		int right = w - 12;
		int y = h / 2 + 16;
		for (ClientState.FeedEntry entry : ClientState.FEED) {
			long age = now - entry.time();
			if (age > 5000) {
				continue;
			}
			int alpha = (int) (255 * Mth.clamp((5000 - age) / 800f, 0, 1));
			if (alpha < 8) {
				continue;
			}
			if (y + 16 + entry.lines().size() * 10 > h - 140) {
				// No more room above the abilities and the key hints.
				break;
			}
			int color = (alpha << 24) | (TEXT & 0xFFFFFF);
			String points = String.format("+%03d", entry.points());
			int xpW = font.width("XP");
			int numW = (int) (font.width(points) * 1.6f);
			g.pose().pushMatrix();
			g.pose().translate(right - xpW - 3 - numW, y);
			g.pose().scale(1.6f, 1.6f);
			g.text(font, points, 0, 0, color, true);
			g.pose().popMatrix();
			g.text(font, "XP", right - xpW, y + 5, color, true);
			y += 16;
			for (Component line : entry.lines()) {
				g.text(font, line, right - font.width(line), y, color, true);
				y += 10;
			}
			y += 6;
		}
	}

	/** Three-segment Bloodthirst ring with blood drops in the middle, like the original. */
	private static void bloodthirstRing(GuiGraphicsExtractor g, Font font, HwNet.ShapeHud hud, int h) {
		int cx = 34;
		int cy = h - 34;
		float r = 20;
		float value = hud.bloodthirst();
		int level = Math.min(3, (int) (value / 100f) + 1);
		for (int i = 0; i < 3; i++) {
			float from = i / 3f + 0.015f;
			float to = (i + 1) / 3f - 0.015f;
			ring(g, cx, cy, r, 5, from, to, 0xC0303030);
			float segment = Mth.clamp((value - i * 100f) / 100f, 0, 1);
			if (segment >= 1f) {
				ring(g, cx, cy, r, 5, from, to, BLOOD);
			} else if (segment > 0f) {
				ring(g, cx, cy, r, 5, from, from + (to - from) * segment, 0xFFE8E2D6);
			}
		}
		for (int i = 0; i < level; i++) {
			drop(g, cx - (level - 1) * 4 + i * 8, cy - 4, level == 3 ? BLOOD : 0xFFE8E2D6);
		}
	}

	/** Weapon, ability diamonds and small actions in the bottom-right corner. */
	private static void abilities(GuiGraphicsExtractor g, Minecraft mc, HwNet.ShapeHud hud, int w, int h) {
		Font font = mc.font;
		int baseY = h - 28;

		// Killer Sense diamond.
		int senseX = w - 26;
		boolean senseTired = hud.fatigue() > 0.6f;
		if (MichaelRenderer.vecna()) {
			vecnaAbilities(g, font, mc, hud, w, baseY);
			return;
		}
		if (!MichaelRenderer.demogorgon()) {
			abilityDiamond(g, font, senseX, baseY, "◉", HalloweenClient.KEY_SENSE,
				hud.sense() ? 0xE0D8D2C4 : DARK_FILL, 1f - hud.fatigue(), senseTired ? 0xFFC77B2A : 0xFF606060, hud.sense() ? 0xFF101010 : TEXT);
		}

		// Shape Jump diamond: red while unavailable.
		int jumpX = w - 62;
		boolean jumpReady = hud.jumpBlock() == 0;
		boolean inShadow = hud.shadowTicks() > 0;
		float jumpProgress = !inShadow && hud.jumpBlock() == 3 ? 1f - (float) hud.jumpCooldown() / Math.max(1, hud.jumpCooldownMax()) : 1f;
		boolean demo = MichaelRenderer.demogorgon();
		HwNet.DemoHud dh = ClientState.demo;
		abilityDiamond(g, font, jumpX, baseY, demo ? "◈" : "»", HalloweenClient.KEY_SHAPE_JUMP,
			demo && dh.below() ? 0xE08A2A2A : inShadow ? (jumpReady ? 0xE07A5CC8 : 0xE0602A6A) : jumpReady ? DARK_FILL : RED_FILL, jumpProgress, 0xFF606060, TEXT);
		if (demo) {
			// The Upside Down: going down, down there (how to get out), climbing out, resting, ready.
			Component line = !dh.ready() ? Component.translatable("halloween.hud.demo.building")
				: dh.phase() == 1 ? Component.translatable("halloween.hud.demo.sinking")
				: dh.phase() == 3 ? Component.translatable("halloween.hud.demo.emerging", seconds(dh.phaseTicks()))
				: dh.below() ? Component.translatable("halloween.hud.demo.below")
				: hud.jumpBlock() == 3 ? Component.literal(seconds(hud.jumpCooldown()))
				: Component.translatable("halloween.hud.demo.ready");
			g.centeredText(font, line, jumpX, baseY - 26, dh.below() ? 0xFFE07070 : DIM);
		} else if (inShadow && hud.jumpBlock() != 0) {
			// Leaving the shadow is refused here: say why.
			g.centeredText(font, Component.translatable(switch (hud.jumpBlock()) {
				case 1 -> "halloween.hud.jump.light";
				case 5 -> "halloween.hud.jump.door";
				case 6 -> "halloween.hud.jump.close";
				default -> "halloween.hud.jump.seen";
			}), jumpX, baseY - 26, 0xFFE07070);
		} else if (!inShadow && hud.jumpBlock() == 3) {
			g.centeredText(font, seconds(hud.jumpCooldown()), jumpX, baseY - 26, DIM);
		} else if (!inShadow && (hud.jumpBlock() == 1 || hud.jumpBlock() == 2)) {
			g.centeredText(font, Component.translatable(hud.jumpBlock() == 1 ? "halloween.hud.jump.light" : "halloween.hud.jump.seen"), jumpX, baseY - 26, DIM);
		}

		// Detection Impulse diamond, above the other two.
		int impulseX = w - 44;
		int impulseY = baseY - 36;
		boolean impulseReady = hud.impulseCooldown() == 0;
		float impulseProgress = impulseReady ? 1f : 1f - (float) hud.impulseCooldown() / Math.max(1, hud.impulseCooldownMax());
		abilityDiamond(g, font, impulseX, impulseY, demo ? "✺" : "◎", HalloweenClient.KEY_IMPULSE,
			impulseReady ? DARK_FILL : RED_FILL, impulseProgress, 0xFF606060, TEXT);

		// Weapon.
		int weaponX = w - 112;
		ItemStack held = mc.player.getMainHandItem();
		if (!held.isEmpty()) {
			g.item(held, weaponX, baseY - 8);
		}
		g.centeredText(font, mc.options.keyAttack.getTranslatedKeyMessage(), weaponX + 8, baseY + 12, DIM);

		// Small actions: grab, dash, right-aligned to the left of the weapon.
		int sx = weaponX - 10;
		int sy = baseY - 30;
		smallAction(g, font, sx, sy + 11, Component.translatable("halloween.hud.grab"), mc.options.keyUse.getTranslatedKeyMessage(), hud.grabCooldown());
		smallAction(g, font, sx, sy + 22, Component.translatable(demo ? (dh.charging() ? "halloween.hud.shred.charging" : "halloween.hud.shred") : "halloween.hud.dash"),
			mc.options.keySprint.getTranslatedKeyMessage(), hud.dashTicks() > 0 ? 0 : hud.dashCooldown());
	}

	/** Vecna: the cursed while the Anchor key is held (the one he would go to picked out), the Anchor's delay, the Eyes. */
	private static void vecnaScreen(GuiGraphicsExtractor g, Minecraft mc, int w, int h) {
		HwNet.VecnaHud vh = ClientState.vecna;
		// The ritual, under the match clock: his kills towards it, then the cursed one; the rift once open.
		HwNet.VecnaRitual rit = ClientState.ritual;
		HwNet.CurseMark cursed = null;
		for (HwNet.CurseMark m : vh.marks()) {
			if (m.stage() > 0) {
				cursed = m;
			}
		}
		Component ritLine = rit.rift() ? Component.translatable("halloween.hud.ritual.rift")
			: rit.kills() < rit.needed() ? Component.translatable("halloween.hud.ritual.kills", rit.kills(), rit.needed())
			: cursed != null ? Component.translatable("halloween.hud.ritual.now_cursed", cursed.name())
			: Component.translatable("halloween.hud.ritual.no_cursed");
		g.centeredText(mc.font, ritLine, w / 2, 46, rit.rift() ? BLOOD : 0xFFD0A0A0);
		if (cursed != null && !rit.rift()) {
			g.centeredText(mc.font, Component.translatable("halloween.hud.ritual.cursed", cursed.name(), cursed.stage()), w / 2, 58, cursed.stage() >= 4 ? 0xFFFF6060 : DIM);
		}
		if (cursed != null && cursed.stage() >= 4 && vh.anchorPhase() != dev.halloween.game.Vecna.ANCHOR_CHOOSING) {
			// Broken by the curse: he sees them wherever they are.
			if (ClientVr.active()) {
				VrMarks.add(cursed.pos(), Component.literal("◆ ").append(Component.translatable("halloween.hud.vecna.mark", cursed.name(), cursed.stage(),
					(int) mc.player.position().distanceTo(cursed.pos()))), 0xFFFF6060, 1.1f);
			}
			Projection.Point p = Projection.toScreen(cursed.pos(), w, h);
			if (p != null) {
				diamond(g, (int) p.x(), (int) p.y(), 5, 0xFF0C0C0C);
				diamond(g, (int) p.x(), (int) p.y(), 4, 0xFFFF4040);
				g.centeredText(mc.font, Component.translatable("halloween.hud.vecna.mark", cursed.name(), cursed.stage(), (int) p.distance()),
					(int) p.x(), (int) p.y() - 15, 0xFFFF6060);
			}
		}
		if (vh.gripAim() >= 0) {
			// The Grip's aim: a standing line where it will go (it flies upright), filling as the aim steadies.
			int cx = w / 2;
			int top = h / 2 - 26;
			int bottom = h / 2 + 26;
			float k = Mth.clamp(vh.gripAim() / (float) Math.max(1, vh.gripAimTicks()), 0, 1);
			boolean ready = k >= 1;
			g.fill(cx - 2, top - 1, cx + 2, bottom + 1, 0x90000000);
			g.fill(cx - 1, top, cx + 1, bottom, 0x60601018);
			int fillTop = bottom - Math.round((bottom - top) * k);
			g.fill(cx - 1, fillTop, cx + 1, bottom, ready ? 0xFFFF3A2A : 0xFFB02030);
			if (ready) {
				g.fill(cx - 4, top, cx + 4, top + 1, 0xFFFF3A2A);
				g.fill(cx - 4, bottom - 1, cx + 4, bottom, 0xFFFF3A2A);
			}
		}
		if (vh.anchorPhase() == dev.halloween.game.Vecna.ANCHOR_CHOOSING) {
			Vec3 from = CameraAim.origin();
			Vec3 look = CameraAim.direction();
			HwNet.CurseMark pick = null;
			double best = Double.MAX_VALUE;
			for (HwNet.CurseMark m : vh.marks()) {
				double a = dev.halloween.game.Sight.angleTo(from, look, m.pos());
				if (a < best) {
					best = a;
					pick = m;
				}
			}
			for (HwNet.CurseMark m : vh.marks()) {
				if (ClientVr.active()) {
					VrMarks.add(m.pos(), Component.literal("◆ ").append(Component.translatable("halloween.hud.vecna.mark", m.name(), m.stage(),
						(int) mc.player.position().distanceTo(m.pos()))), m == pick ? 0xFFFF4040 : 0xFF8A3A3A, m == pick ? 1.3f : 1f);
					continue;
				}
				Projection.Point p = Projection.toScreen(m.pos(), w, h);
				if (p == null) {
					continue;
				}
				boolean it = m == pick;
				int color = it ? 0xFFFF4040 : 0xFF8A3A3A;
				diamond(g, (int) p.x(), (int) p.y(), it ? 6 : 4, 0xFF0C0C0C);
				diamond(g, (int) p.x(), (int) p.y(), it ? 5 : 3, color);
				Component label = Component.translatable("halloween.hud.vecna.mark", m.name(), m.stage(), (int) p.distance());
				g.centeredText(mc.font, label, (int) p.x(), (int) p.y() - 16, color);
			}
			Component line = pick == null ? Component.translatable("halloween.hud.vecna.anchor_none")
				: Component.translatable("halloween.hud.vecna.anchor_pick", pick.name(), HalloweenClient.KEY_IMPULSE.getTranslatedKeyMessage());
			g.textWithBackdrop(mc.font, line, w / 2 - mc.font.width(line) / 2, h / 2 + 40, mc.font.width(line), pick == null ? DIM : 0xFFFF6060);
		} else if (vh.anchorPhase() == dev.halloween.game.Vecna.ANCHOR_CHANNEL) {
			Component line = Component.translatable("halloween.hud.vecna.anchor_arriving", seconds(vh.anchorLeft()));
			g.textWithBackdrop(mc.font, line, w / 2 - mc.font.width(line) / 2, h / 2 + 40, mc.font.width(line), 0xFFFF6060);
		}
		if (vh.eyesTarget() >= 0) {
			// Through their eyes: the edges darkened red, his reserve running down.
			int edge = 40;
			for (int i = 0; i < edge; i += 4) {
				int a = (int) (150 * (1 - i / (float) edge));
				int c = a << 24 | 0x2A0006;
				g.fill(0, i, w, i + 4, c);
				g.fill(0, h - i - 4, w, h - i, c);
				g.fill(i, 0, i + 4, h, c);
				g.fill(w - i - 4, 0, w - i, h, c);
			}
			Component line = Component.translatable("halloween.hud.vecna.eyes_on", seconds(vh.eyesReserve()), HalloweenClient.KEY_SHAPE_JUMP.getTranslatedKeyMessage());
			g.textWithBackdrop(mc.font, line, w / 2 - mc.font.width(line) / 2, h - 60, mc.font.width(line), 0xFFFF8080);
		}
	}

	/**
	 * A survivor's curse (against Vecna): under the match clock, its four stages and the way to the next; at the
	 * last, the seconds left in red. From the third stage on, the telephone booths show through walls.
	 */
	private static void curseHud(GuiGraphicsExtractor g, Minecraft mc, int w, int h) {
		HwNet.CurseHud c = ClientState.curse;
		HwNet.VecnaRitual rit = ClientState.ritual;
		if (rit.rift()) {
			g.centeredText(mc.font, Component.translatable("halloween.hud.ritual.rift_survivor"), w / 2, c.stage() == 0 ? 46 : 96, BLOOD);
		}
		if (c.stage() == 0) {
			return;
		}
		if (c.stage() >= 1) {
			// Red veins creeping in from the edges of the screen, further each stage; at the last they throb with the clock.
			int stage = Math.min(4, c.stage());
			float a = 0.85f;
			if (stage == 4) {
				float sec = (mc.level.getGameTime() % 20 + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false)) / 20f;
				a = 0.75f + 0.25f * (float) Math.exp(-sec * 6);
			}
			if (!ClientVr.active()) {
				// (In the headset the curse's colour on the world says it; veins on the panel would be a frame round it.)
				g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, CURSE_VEINS[stage - 1], 0, 0, 0, 0, w, h, 512, 288, 512, 288,
					net.minecraft.util.ARGB.white(a));
			}
		}
		int cx = w / 2;
		int y = 46;
		int color = c.stage() >= 4 ? BLOOD : c.stage() >= 2 ? 0xFFE07070 : 0xFFB8A8C8;
		Component title = Component.translatable("halloween.hud.curse." + c.stage());
		g.centeredText(mc.font, title, cx, y, color);
		for (int i = 0; i < 4; i++) {
			int px = cx - 21 + i * 14;
			diamond(g, px, y + 15, 4, 0xFF0C0C0C);
			diamond(g, px, y + 15, 3, i < c.stage() ? color : 0xFF303030);
		}
		if (c.stage() < 4) {
			int bw = 56;
			g.fill(cx - bw / 2, y + 22, cx + bw / 2, y + 24, 0xC0181818);
			g.fill(cx - bw / 2, y + 22, cx - bw / 2 + Math.round(bw * Mth.clamp(c.progress(), 0, 1)), y + 24, color);
		} else {
			// Broken: no way back, and he sees them.
			boolean blink = mc.level.getGameTime() % 20 < 12;
			g.centeredText(mc.font, Component.translatable("halloween.hud.curse.broken"), cx, y + 24, blink ? BLOOD : 0xFFFF8080);
		}
		g.centeredText(mc.font, Component.translatable("halloween.hud.curse.no_escape"), cx, y + 36, DIM);
	}

	/** Vecna's corner: the Grip on the Sense key, the Eyes and the Anchor (still to come), the weapon and the grab. */
	private static void vecnaAbilities(GuiGraphicsExtractor g, Font font, Minecraft mc, HwNet.ShapeHud hud, int w, int baseY) {
		HwNet.VecnaHud vh = ClientState.vecna;
		int gripX = w - 26;
		boolean holding = vh.gripTarget() >= 0;
		boolean gripReady = vh.gripCooldown() == 0;
		float gripProgress = gripReady ? 1f : 1f - (float) vh.gripCooldown() / Math.max(1, vh.gripCooldownMax());
		abilityDiamond(g, font, gripX, baseY, "✋", HalloweenClient.KEY_SENSE,
			holding || vh.gripFlying() || vh.gripAim() >= 0 ? 0xE08A1A2A : gripReady ? DARK_FILL : RED_FILL, gripProgress, 0xFF606060, holding ? 0xFFFFD0D0 : TEXT);
		Component gripLine = holding ? Component.translatable("halloween.hud.vecna.grip.holding")
			: vh.gripFlying() ? Component.translatable("halloween.hud.vecna.grip.flying")
			: vh.gripAim() >= 0 ? Component.translatable(vh.gripAim() >= vh.gripAimTicks() ? "halloween.hud.vecna.grip.ready" : "halloween.hud.vecna.grip.aim",
				HalloweenClient.KEY_SENSE.getTranslatedKeyMessage())
			: gripReady ? null : Component.literal(seconds(vh.gripCooldown()));
		if (gripLine != null) {
			// Above the other diamonds, right-aligned to the screen's edge.
			int lx = w - 4 - font.width(gripLine);
			g.text(font, gripLine, lx, baseY - 76, holding ? 0xFFE07070 : DIM, true);
		}
		// The Eyes: the reserve as the fill (red while too little is left to look), lit while looking.
		boolean looking = vh.eyesTarget() >= 0;
		boolean eyesReady = vh.eyesReserve() >= 20;
		abilityDiamond(g, font, w - 62, baseY, "◉", HalloweenClient.KEY_SHAPE_JUMP, looking ? 0xE08A1A2A : eyesReady ? DARK_FILL : RED_FILL,
			vh.eyesReserve() / (float) Math.max(1, vh.eyesMax()), 0xFF8A6A9A, looking ? 0xFFFFD0D0 : TEXT);
		if (looking || vh.eyesReserve() < vh.eyesMax()) {
			g.centeredText(font, seconds(vh.eyesReserve()), w - 62, baseY - 26, looking ? 0xFFE07070 : DIM);
		}
		// The Anchor: its rest, or lit while choosing and going.
		int ax = w - 44;
		int ay = baseY - 36;
		boolean anchorReady = vh.anchorCooldown() == 0;
		abilityDiamond(g, font, ax, ay, "⚓", HalloweenClient.KEY_IMPULSE, vh.anchorPhase() != 0 ? 0xE08A1A2A : anchorReady ? DARK_FILL : RED_FILL,
			anchorReady ? 1f : 1f - vh.anchorCooldown() / (float) Math.max(1, vh.anchorCooldownMax()), 0xFF606060, TEXT);
		if (!anchorReady) {
			g.centeredText(font, seconds(vh.anchorCooldown()), ax, ay - 26, DIM);
		}

		int weaponX = w - 112;
		ItemStack held = mc.player.getMainHandItem();
		if (!held.isEmpty()) {
			g.item(held, weaponX, baseY - 8);
		}
		g.centeredText(font, mc.options.keyAttack.getTranslatedKeyMessage(), weaponX + 8, baseY + 12, DIM);
		smallAction(g, font, weaponX - 10, baseY - 19, Component.translatable("halloween.hud.grab"), mc.options.keyUse.getTranslatedKeyMessage(), hud.grabCooldown());
	}

	private static void abilityDiamond(GuiGraphicsExtractor g, Font font, int cx, int cy, String glyph, KeyMapping key,
		int fill, float progress, int progressColor, int glyphColor) {
		diamond(g, cx, cy, 13, 0xFF0C0C0C);
		diamond(g, cx, cy, 12, fill);
		if (progress < 1f) {
			int size = (int) (11 * Mth.clamp(progress, 0, 1));
			if (size > 0) {
				diamond(g, cx, cy, size, progressColor);
			}
		}
		g.centeredText(font, glyph, cx, cy - 4, glyphColor);
		Component label = key.getTranslatedKeyMessage();
		int lw = font.width(label) + 6;
		g.fill(cx - lw / 2, cy + 15, cx + lw / 2, cy + 25, 0xE0E8E2D6);
		g.centeredText(font, label, cx, cy + 16, 0xFF101010);
	}

	// ---------------------------------------------------------------- the Runt

	private static final int RUNT_PURPLE = 0xFFB98CFF;

	/** A marker over somebody, seen through walls: a diamond and, if given, a word above it. */
	private static void markEntity(GuiGraphicsExtractor g, Minecraft mc, int id, float partial, int w, int h, Component label, int color, int size) {
		Entity e = id < 0 ? null : mc.level.getEntity(id);
		if (e == null || e == mc.player) {
			return;
		}
		Vec3 head = e.getPosition(partial).add(0, e.getBbHeight() + 0.35, 0);
		if (ClientVr.active()) {
			VrMarks.add(head, "◆", color, 1f + size * 0.15f);
			if (label != null) {
				VrMarks.add(head.add(0, 0.3, 0), label, color, 1f);
			}
			return;
		}
		Projection.Point p = Projection.toScreen(head, w, h);
		if (p == null) {
			return;
		}
		diamond(g, (int) p.x(), (int) p.y(), size + 1, 0xFF0C0C0C);
		diamond(g, (int) p.x(), (int) p.y(), size, color);
		if (label != null) {
			g.centeredText(mc.font, label, (int) p.x(), (int) p.y() - size - 11, color);
		}
	}

	/** Michael's view of his Runt: where it is, whom it smells, whom it is holding for him. */
	private static void runtForMichael(GuiGraphicsExtractor g, Minecraft mc, int w, int h, float partial) {
		HwNet.RuntInfo info = ClientState.runtInfo;
		if (info == null || mc.level.getGameTime() - ClientState.runtInfoAt > 40) {
			return;
		}
		markEntity(g, mc, info.runtId(), partial, w, h, null, RUNT_PURPLE, 3);
		if (info.holdingId() >= 0) {
			markEntity(g, mc, info.holdingId(), partial, w, h, Component.translatable("halloween.hud.runt.held"), BLOOD, 4);
		}
		// What it is doing, and (a bot) how to give it orders.
		Component status = Component.translatable("halloween.hud.runt.status." + info.status());
		g.text(mc.font, Component.translatable("halloween.hud.runt.status", status), w - 10 - mc.font.width(Component.translatable("halloween.hud.runt.status", status)),
			h - 110, 0xFFB98CFF, true);
		if (info.bot()) {
			Component keyHint = Component.translatable("halloween.hud.runt.orders", HalloweenClient.KEY_RUNT_ORDER.getTranslatedKeyMessage());
			g.text(mc.font, keyHint, w - 10 - mc.font.width(keyHint), h - 99, DIM, true);
		}
	}

	/**
	 * The Runt's screen: what to do, Michael and the smelt survivors marked, the fear meter with its three
	 * levels, the gifts he has and the two to pick from when a level comes, his keys and their cooldowns.
	 */
	private static void runtView(GuiGraphicsExtractor g, Minecraft mc, int w, int h, float partial) {
		HwNet.RuntHud hud = ClientState.runtHud;
		Font font = mc.font;
		g.text(font, Component.translatable("halloween.hud.runt.goal1"), 10, 10, TEXT, true);
		g.text(font, Component.translatable("halloween.hud.runt.goal2"), 10, 22, TEXT, true);
		feed(g, font, w, h);
		if (hud == null) {
			return;
		}
		markEntity(g, mc, hud.michaelId(), partial, w, h, Component.translatable("halloween.hud.runt.michael"), BLOOD, 4);
		// Fear: three segments, one per level.
		int cx = 34;
		int cy = h - 34;
		for (int i = 0; i < 3; i++) {
			ring(g, cx, cy, 20, 5, i / 3f + 0.015f, (i + 1) / 3f - 0.015f, 0xC0303030);
		}
		for (int i = 0; i < Math.min(3, hud.level()); i++) {
			ring(g, cx, cy, 20, 5, i / 3f + 0.015f, (i + 1) / 3f - 0.015f, RUNT_PURPLE);
		}
		if (hud.level() < 3 && hud.nextFear() > hud.prevFear()) {
			float part = Mth.clamp((hud.fear() - hud.prevFear()) / (hud.nextFear() - hud.prevFear()), 0, 1);
			float from = hud.level() / 3f + 0.015f;
			float to = (hud.level() + 1) / 3f - 0.015f;
			ring(g, cx, cy, 20, 5, from, from + (to - from) * part, TEXT);
		}
		g.centeredText(font, String.valueOf(hud.level()), cx, cy - 4, RUNT_PURPLE);
		// The gifts he has, above the meter.
		int gy = h - 70;
		for (int i = 0; i < 3; i++) {
			int pick = hud.picks().get(i);
			if (pick >= 0) {
				String gift = dev.halloween.game.Runt.GIFTS[i][pick];
				g.text(font, Component.translatable("halloween.runt.gift." + gift), 10, gy, RUNT_PURPLE, true);
				gy -= 11;
			}
		}
		// A gift waiting: the two to pick from, keys 1 and 2.
		if (hud.pending() >= 0) {
			int lvl = hud.pending();
			int py = h / 2 + 30;
			g.centeredText(font, Component.translatable("halloween.hud.runt.pick", lvl + 1), w / 2, py, RUNT_PURPLE);
			for (int k = 0; k < 2; k++) {
				String gift = dev.halloween.game.Runt.GIFTS[lvl][k];
				g.centeredText(font, Component.translatable("halloween.hud.runt.option", k + 1, Component.translatable("halloween.runt.gift." + gift)),
					w / 2, py + 14 + k * 24, TEXT);
				g.centeredText(font, Component.translatable("halloween.runt.gift." + gift + ".desc"), w / 2, py + 25 + k * 24, DIM);
			}
		}
		// The pounce winding up: a bar under the crosshair.
		if (hud.charge() > 0f) {
			int bw = 40;
			g.fill(w / 2 - bw / 2, h / 2 + 12, w / 2 + bw / 2, h / 2 + 15, 0x80303030);
			g.fill(w / 2 - bw / 2, h / 2 + 12, w / 2 - bw / 2 + (int) (bw * hud.charge()), h / 2 + 15, RUNT_PURPLE);
		}
		// Keys, bottom right: frighten (attack), his special, his tricks.
		int baseY = h - 28;
		float scare = hud.scareCooldown() == 0 ? 1f : 1f - (float) hud.scareCooldown() / Math.max(1, hud.scareMax());
		diamond(g, w - 26, baseY, 13, 0xFF0C0C0C);
		diamond(g, w - 26, baseY, 12, hud.scareCooldown() == 0 ? DARK_FILL : RED_FILL);
		if (scare < 1f) {
			diamond(g, w - 26, baseY, Math.max(1, (int) (11 * scare)), 0xFF606060);
		}
		g.centeredText(font, "!", w - 26, baseY - 4, TEXT);
		g.centeredText(font, mc.options.keyAttack.getTranslatedKeyMessage(), w - 26, baseY + 16, DIM);
		int sy = baseY - 40;
		smallAction(g, font, w - 10, sy, Component.translatable("halloween.hud.runt.pounce"), mc.options.keyShift.getTranslatedKeyMessage(), hud.pounceCooldown());
		sy -= 11;
		smallAction(g, font, w - 10, sy, Component.translatable("halloween.hud.runt.knock"), mc.options.keyAttack.getTranslatedKeyMessage(), hud.knockCooldown());
		sy -= 11;
		boolean swap = hud.picks().get(2) == 1;
		boolean mischief = hud.picks().get(1) == 1;
		if (swap) {
			smallAction(g, font, w - 10, sy, Component.translatable("halloween.runt.gift.swap"), HalloweenClient.KEY_SHAPE_JUMP.getTranslatedKeyMessage(),
				hud.specialCooldown());
			sy -= 11;
		}
		if (mischief) {
			smallAction(g, font, w - 10, sy, Component.translatable("halloween.runt.gift.mischief"), mc.options.keyUse.getTranslatedKeyMessage(),
				hud.trickCooldown());
		}
		// Down, trapped, holding on.
		if (hud.stunned() > 0) {
			g.centeredText(font, Component.translatable("halloween.hud.runt.stunned"), w / 2, h - 60, BLOOD);
		} else if (hud.holding() > 0) {
			g.centeredText(font, Component.translatable("halloween.hud.runt.riding"), w / 2, h - 60, RUNT_PURPLE);
		}
	}

	private static void smallAction(GuiGraphicsExtractor g, Font font, int x, int y, Component name, Component key, int cooldown) {
		int color = cooldown > 0 ? DIM : TEXT;
		Component text = cooldown > 0
			? Component.translatable("halloween.hud.small.cooldown", name, seconds(cooldown))
			: Component.translatable("halloween.hud.small.ready", name, key);
		g.text(font, text, x - font.width(text), y, color, true);
	}

	// ---------------------------------------------------------------- Civilian

	/** What right click does with this in hand (or what it does by itself), if anything worth saying. */
	private static String itemPrompt(dev.halloween.game.HwItems.Kind kind) {
		if (kind == null) {
			return null;
		}
		return switch (kind) {
			case HEALING_SPRAY -> "halloween.hud.prompt.item.spray";
			case MED_KIT -> "halloween.hud.prompt.item.med_kit";
			case FLARE_GUN -> "halloween.hud.prompt.item.flare_gun";
			case BEAR_TRAP, TIN_CANS -> "halloween.hud.prompt.item.trap";
			case HOURGLASS -> "halloween.hud.prompt.item.hourglass";
			case AMULET -> "halloween.hud.prompt.item.amulet";
			case BRASS_LANTERN -> "halloween.hud.prompt.item.lantern";
			case FIRECRACKERS, ROAD_FLARE, BRICK, ROCK, BASEBALL -> "halloween.hud.prompt.item.throw";
			case CLEAVER, HATCHET, PITCHFORK -> "halloween.hud.prompt.item.melee_throw";
			case POCKET_KNIFE, BUTTERFLY_KNIFE -> "halloween.hud.prompt.item.knife";
			case ICE_PICK -> "halloween.hud.prompt.item.ice_pick";
			case DART -> "halloween.hud.prompt.item.dart";
			default -> null;
		};
	}

	/** Hints for the door or window under the crosshair. */
	private static void prompts(GuiGraphicsExtractor g, Minecraft mc, int w, int h) {
		if (ClientState.progress.label().isEmpty() && ClientState.myRole().survivor()
			&& dev.halloween.game.HwItems.is(mc.player.getMainHandItem(), dev.halloween.game.HwItems.Kind.FLASHLIGHT)) {
			Component torch = Component.translatable("halloween.hud.prompt.flashlight");
			g.textWithBackdrop(mc.font, torch, w / 2 - mc.font.width(torch) / 2, h / 2 + 46, mc.font.width(torch), DIM);
		}
		String itemHint = itemPrompt(dev.halloween.game.HwItems.kindOf(mc.player.getMainHandItem()));
		if (ClientState.progress.label().isEmpty() && ClientState.myRole().survivor() && itemHint != null && !ClientState.qte.active()) {
			Component hint = Component.translatable(itemHint);
			g.textWithBackdrop(mc.font, hint, w / 2 - mc.font.width(hint) / 2, h / 2 + 46, mc.font.width(hint), DIM);
		}
		if (ClientState.progress.label().isEmpty() && ClientState.myRole().survivor()
			&& mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult eh && ClientState.match.npcs().contains(eh.getEntity().getId())) {
			Component npc = Component.translatable("halloween.hud.prompt.npc");
			g.textWithBackdrop(mc.font, npc, w / 2 - mc.font.width(npc) / 2, h / 2 + 22, mc.font.width(npc), TEXT);
			return;
		}
		if (!ClientState.progress.label().isEmpty() || !(mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit)
			|| hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
			return;
		}
		var pos = hit.getBlockPos();
		var state = mc.level.getBlockState(pos);
		Component line = null;
		Component second = null;
		int object = ClientInteractions.matchObject(mc.level, pos);
		boolean shape = ClientState.myRole() == Role.SHAPE;
		if (object == 1 && !shape) {
			line = Component.translatable(ClientState.match.searched().contains(pos) ? "halloween.hud.prompt.searched" : "halloween.hud.prompt.search");
		} else if (object == 2) {
			line = Component.translatable(ClientState.match.cutPhones().contains(pos) && !shape ? "halloween.hud.prompt.phone_cut" : "halloween.hud.prompt.phone");
		} else if (object == 4 && !shape) {
			line = Component.translatable("halloween.hud.prompt.case");
			second = Component.translatable("halloween.hud.prompt.case_how");
		} else if (object == 3) {
			line = Component.translatable("halloween.hud.prompt.exit");
		} else if (shape) {
			return;
		} else if (dev.halloween.game.LightSystem.isBreaker(mc.level, pos)) {
			if (!state.getValue(net.minecraft.world.level.block.LeverBlock.POWERED)) {
				line = Component.translatable("halloween.hud.prompt.breaker");
			}
		} else if (state.getBlock() instanceof dev.halloween.game.LockerBlock) {
			// Survivors are only ever invisible while hiding in a locker.
			line = Component.translatable(mc.player.isInvisible() ? "halloween.hud.prompt.locker_out"
				: (ClientEvents.state.flags() & dev.halloween.net.HwNet.EventSync.MIDNIGHT) != 0 ? "halloween.locker.jammed" : "halloween.hud.prompt.locker");
		} else 		if (state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) {
			var base = ClientInteractions.doorBase(mc.level, pos);
			line = Component.translatable(ClientState.BROKEN_LOCKS.contains(base) ? "halloween.hud.prompt.door_broken"
				: ClientState.LOCKED_DOORS.contains(base) ? "halloween.hud.prompt.door_locked" : "halloween.hud.prompt.door");
		} else if (dev.halloween.game.Obstacles.kindOf(state) != null && !ClientState.match.containers().contains(pos)) {
			// Something to pull down behind you (game.Obstacles).
			line = Component.translatable("halloween.hud.prompt.knock", mc.options.keyUse.getTranslatedKeyMessage(), mc.options.keySprint.getTranslatedKeyMessage());
		} else if (dev.halloween.game.Sight.isGlass(state)) {
			boolean low = mc.player.getHealth() <= mc.player.getMaxHealth() * HwConfig.get().vaultDamageFraction;
			line = low ? Component.translatable("halloween.hud.prompt.window_low")
				: Component.translatable("halloween.hud.prompt.window", mc.options.keySprint.getTranslatedKeyMessage());
		}
		if (line == null) {
			return;
		}
		int y = h / 2 + 22;
		g.textWithBackdrop(mc.font, line, w / 2 - mc.font.width(line) / 2, y, mc.font.width(line), TEXT);
		if (second != null) {
			g.textWithBackdrop(mc.font, second, w / 2 - mc.font.width(second) / 2, y + 12, mc.font.width(second), 0xFFE07070);
		}
	}

	/** Locking, unlocking or climbing in progress. */
	private static void progressBar(GuiGraphicsExtractor g, Minecraft mc, int w, int h) {
		HwNet.Progress p = ClientState.progress;
		if (p.label().isEmpty()) {
			return;
		}
		int barW = 120;
		int x = w / 2 - barW / 2;
		int y = h / 2 + 26;
		Component label = Component.translatable(p.label());
		g.centeredText(mc.font, label, w / 2, y - 11, TEXT);
		bar(g, x, y, barW, 4, p.progress(), p.danger() ? BLOOD : 0xFFD8D2C4);
	}

	/** The Shape looking at a locked door. */
	private static void shapeDoorPrompt(GuiGraphicsExtractor g, Minecraft mc, int w, int h) {
		if (!(mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit) || hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
			return;
		}
		var pos = hit.getBlockPos();
		if (mc.level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.DoorBlock
			&& ClientState.LOCKED_DOORS.contains(ClientInteractions.doorBase(mc.level, pos))) {
			Component line = Component.translatable("halloween.hud.prompt.door_shape");
			g.textWithBackdrop(mc.font, line, w / 2 - mc.font.width(line) / 2, h / 2 + 30, mc.font.width(line), TEXT);
		} else if (dev.halloween.game.Sight.isGlass(mc.level.getBlockState(pos)) && mc.player.getEyePosition().distanceTo(hit.getLocation()) <= 3.2) {
			Component line = Component.translatable(ClientState.shadowActive() ? "halloween.hud.prompt.window_shadow" : "halloween.hud.prompt.window_smash");
			g.textWithBackdrop(mc.font, line, w / 2 - mc.font.width(line) / 2, h / 2 + 30, mc.font.width(line), TEXT);
		}
	}


	/** Health (in four parts), and just above it, thinner, the stamina (red and blinking while out of breath). */
	private static void healthBar(GuiGraphicsExtractor g, Minecraft mc, int w, int h) {
		int x = w - 110;
		int y = h - 22;
		g.fill(x - 4, y - 21, x + 106, h - 6, 0x99000000);
		g.text(mc.font, Component.translatable("halloween.hud.health"), x, y - 17, TEXT, true);
		boolean out = ClientState.staminaExhausted;
		boolean blink = out && (System.currentTimeMillis() / 250) % 2 == 0;
		bar(g, x, y - 6, 100, 3, ClientState.stamina, out ? (blink ? 0xFFB02020 : 0xFF701818) : 0xFF8FB0C8);
		float frac = mc.player.getHealth() / Math.max(1f, mc.player.getMaxHealth());
		bar(g, x, y, 100, 6, frac, frac < 0.35f ? BLOOD : 0xFFD8D2C4);
		for (int i = 1; i < 4; i++) {
			g.fill(x + i * 25, y, x + i * 25 + 1, y + 6, 0xFF1A1A1A);
		}
	}

	// ---------------------------------------------------------------- drawing helpers

	private static void bar(GuiGraphicsExtractor g, int x, int y, int width, int height, float frac, int color) {
		g.fill(x, y, x + width, y + height, 0xFF1A1A1A);
		g.fill(x, y, x + (int) (width * Mth.clamp(frac, 0, 1)), y + height, color);
	}

	/** Filled diamond (square rotated 45 degrees) with half-diagonal {@code size}. */
	static void diamond(GuiGraphicsExtractor g, int cx, int cy, int size, int color) {
		for (int dy = -size; dy <= size; dy++) {
			int half = size - Math.abs(dy);
			g.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color);
		}
	}

	/** Arc of a ring from {@code from} to {@code to} (fractions of a turn, clockwise from the top). */
	static void ring(GuiGraphicsExtractor g, float cx, float cy, float r, int thickness, float from, float to, int color) {
		int steps = Math.max(8, (int) (r * 7 * Math.abs(to - from)));
		int half = Math.max(1, thickness / 2);
		for (int i = 0; i <= steps; i++) {
			double a = (from + (to - from) * i / (double) steps) * Math.PI * 2 - Math.PI / 2;
			int x = (int) (cx + Math.cos(a) * r);
			int y = (int) (cy + Math.sin(a) * r);
			g.fill(x - half, y - half, x + half + (thickness % 2), y + half + (thickness % 2), color);
		}
	}

	private static final String[] DROP = {
		"..#..",
		"..#..",
		".###.",
		"#####",
		"#####",
		".###.",
	};

	private static void drop(GuiGraphicsExtractor g, int cx, int top, int color) {
		for (int row = 0; row < DROP.length; row++) {
			for (int col = 0; col < 5; col++) {
				if (DROP[row].charAt(col) == '#') {
					g.fill(cx - 2 + col, top + row, cx - 1 + col, top + row + 1, color);
				}
			}
		}
	}

	private static String seconds(int ticks) {
		return String.valueOf((ticks + 19) / 20);
	}
}
