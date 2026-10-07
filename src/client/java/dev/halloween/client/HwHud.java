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
 * bottom-right, XP feed on the left, objectives top-left, world markers projected on screen.
 * Civilians get health and their side of the grab check.
 */
public final class HwHud {
	private static final int TEXT = 0xFFE8E2D6;
	private static final int DIM = 0xFF8A847A;
	private static final int BLOOD = 0xFFB3121B;
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

	private static void renderScaled(GuiGraphicsExtractor g, DeltaTracker delta, Minecraft mc, int w, int h) {
		Role role = ClientState.myRole();
		float partial = delta.getGameTimeDeltaPartialTick(false);
		if (role == Role.SHAPE) {
			HwNet.ShapeHud hud = ClientState.hud;
			if (hud == null) {
				return;
			}
			beams(g, hud, w, h);
			impulseSpots(g, mc, w, h, partial);
			if (hud.sense()) {
				victimMarkers(g, mc, w, h, partial);
			}
			reticle(g, mc, hud, w, h, partial);
			if (ClientState.qte.active() && ClientState.qte.forShape()) {
				shapeGrab(g, mc, w, h, partial);
			}
			int y = objectives(g, mc.font, hud);
			exitLines(g, mc.font, 10, y + 4);
			feed(g, mc.font, h);
			bloodthirstRing(g, mc.font, hud, h);
			abilities(g, mc, hud, w, h);
			shapeDoorPrompt(g, mc, w, h);
			prompts(g, mc, w, h);
			progressBar(g, mc, w, h);
			stunAndBlind(g, mc, hud, w, h);
		} else if (role.survivor()) {
			if (mc.player.isSpectator()) {
				int respawn = ClientState.match.respawnTicks();
				Component line = respawn > 0 ? Component.translatable("halloween.hud.respawn", clock(respawn))
					: Component.translatable("halloween.hud.dead");
				g.centeredText(mc.font, line, w / 2, h - 40, respawn > 0 ? 0xFF7FA7FF : BLOOD);
			} else {
				alerts(g, mc, w, h, partial);
				vanMarker(g, mc, w, h, partial);
				healthBar(g, mc, w, h);
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
			exitLines(g, mc.font, 10, 26);
			feed(g, mc.font, h);
		}
		matchTimer(g, mc, w);
		int noticesTop = minimapAndWanted(g, mc, w, partialTicks(delta));
		notices(g, mc.font, w, noticesTop);
		endScreen(g, mc, w, h);
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
		return y + size + 10;
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

	/** The police van, when it waits: a flashing badge over it, or at the screen edge which way to turn. */
	private static void vanMarker(GuiGraphicsExtractor g, Minecraft mc, int w, int h, float partial) {
		HwNet.MatchSync m = ClientState.match;
		if (m.state() != 2) {
			return;
		}
		for (HwNet.ExitInfo e : m.exits()) {
			if (e.type() != 2 || e.state() != 2) {
				continue;
			}
			long t = mc.level.getGameTime();
			int glow = (t / 6) % 2 == 0 ? 0x90D03A3A : 0x903A6AD8;
			int dist = (int) Math.round(mc.player.position().distanceTo(Vec3.atCenterOf(e.pos())));
			Component label = Component.translatable("halloween.hud.van_here", dist);
			Projection.Point p = Projection.toScreen(Vec3.atCenterOf(e.pos()).add(0, 3.5, 0), w, h);
			if (p != null && p.x() >= 0 && p.x() <= w && p.y() >= 0 && p.y() <= h) {
				int x = (int) p.x();
				int y = (int) p.y();
				g.fill(x - 10, y - 10, x + 10, y + 10, glow);
				g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, BADGE, x - 8, y - 8, 0, 0, 16, 16, 16, 16, 16, 16);
				g.centeredText(mc.font, label, x, y + 13, 0xFF9FC4FF);
				continue;
			}
			Vec3 to = Vec3.atCenterOf(e.pos()).subtract(mc.player.getEyePosition(partial));
			Vec3 look = mc.player.getViewVector(partial);
			boolean right = look.x * to.z - look.z * to.x > 0;
			int x = right ? w - 16 - mc.font.width(label) / 2 : 16 + mc.font.width(label) / 2;
			g.fill(x - 10, h / 2 - 40, x + 10, h / 2 - 20, glow);
			g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, BADGE, x - 8, h / 2 - 38, 0, 0, 16, 16, 16, 16, 16, 16);
			g.centeredText(mc.font, Component.literal(right ? "▶" : "◀"), x, h / 2 - 16, 0xFF9FC4FF);
			g.centeredText(mc.font, label, x, h / 2 - 6, 0xFF9FC4FF);
		}
	}

	/** A hand and three pockets, bottom centre. */
	private static void inventoryBar(GuiGraphicsExtractor g, Minecraft mc, int w, int h) {
		var inv = mc.player.getInventory();
		int slots = dev.halloween.game.Inventory.SLOTS;
		int size = 22;
		int x0 = w / 2 - slots * size / 2;
		int y = h - size - 6;
		for (int i = 0; i < slots; i++) {
			int x = x0 + i * size;
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

	/** With a gun in hand: a reticle as wide as the cone of fire, and the rounds in it and in the pockets. */
	private static void gunHud(GuiGraphicsExtractor g, Minecraft mc, int w, int h) {
		dev.halloween.game.HwItems.Kind kind = ClientGuns.heldGun(mc);
		if (kind == null || mc.gui.screen() != null) {
			return;
		}
		int cx = w / 2;
		int cy = h / 2;
		// The cone's edge on screen: tan(spread) over tan(half the view), times half the screen height.
		double fov = mc.options.fov().get() * ClientState.fovMultiplier();
		double spread = ClientGuns.spread(mc) + ClientGuns.bloom() * 4;
		int gap = (int) Math.round(2 + Math.tan(Math.toRadians(spread)) / Math.tan(Math.toRadians(fov / 2)) * (h / 2.0));
		int len = 4;
		int color = ClientGuns.aiming() ? 0xE0FFFFFF : 0xB0E8E2D6;
		g.fill(cx - gap - len, cy, cx - gap, cy + 1, color);
		g.fill(cx + gap + 1, cy, cx + gap + 1 + len, cy + 1, color);
		g.fill(cx, cy - gap - len, cx + 1, cy - gap, color);
		g.fill(cx, cy + gap + 1, cx + 1, cy + gap + 1 + len, color);
		g.fill(cx, cy, cx + 1, cy + 1, color);
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
		if (hud.blind()) {
			g.fill(0, 0, w, h, 0xF0F4F2EA);
			g.fillGradient(0, 0, w, h, 0x00FFFFFF, 0x60E0D8C0);
		}
		if (hud.stunnedTicks() > 0) {
			g.fill(0, 0, w, h, 0x50000000);
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

	/** Detection Impulse: indoor victims only give away a fuzzy spot on their building. */
	private static void impulseSpots(GuiGraphicsExtractor g, Minecraft mc, int w, int h, float partial) {
		if (!ClientState.impulseActive()) {
			return;
		}
		float age = mc.level.getGameTime() - ClientState.impulseStart + partial;
		float life = Math.max(1, ClientState.impulseUntil - ClientState.impulseStart);
		int alpha = (int) (255 * Mth.clamp(1 - age / life, 0, 1));
		for (Vec3 spot : ClientState.impulseSpots) {
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
					Projection.Point p = Projection.toScreen(FocusPoints.get(living, i, partial), w, h);
					if (p != null) {
						focusIcon(g, i, p.x(), p.y());
					}
				}
			}
			Vec3 head = living.getPosition(partial).add(0, living.getBbHeight() + 0.45, 0);
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
					Vec3 dir = FocusPoints.get(living, i, partial).subtract(eye).normalize();
					onPoint = Math.toDegrees(Math.acos(Mth.clamp(look.dot(dir), -1, 1))) <= limit;
				}
			}
		}
		ring(g, cx, cy, 8, 2, 0f, 1f, onPoint ? GREEN : 0xC0E8E8E8);
		g.centeredText(mc.font, Component.translatable("halloween.hud.focus", mc.options.keyUse.getTranslatedKeyMessage()), cx, cy + 16, 0xA0E8E2D6);
	}

	// ---------------------------------------------------------------- grab

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
			g.centeredText(mc.font, mc.options.keyAttack.getTranslatedKeyMessage(), cx, cy - 4, TEXT);
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
		int cx = w / 2;
		int cy = h / 2 + 44;
		float r = 20;
		ring(g, cx, cy, r, 4, 0f, 1f, 0xC0303030);
		ring(g, cx, cy, r, 4, check.zoneStart(), check.zoneStart() + check.zoneWidth(), 0xFFF2F2F2);
		double elapsed = Math.max(0, mc.level.getGameTime() - check.start() + partial);
		double a = HwNet.needle(elapsed, check.period()) * Math.PI * 2 - Math.PI / 2;
		for (float t = 0; t <= r + 3; t += 1f) {
			int x = (int) (cx + Math.cos(a) * t);
			int y = (int) (cy + Math.sin(a) * t);
			g.fill(x, y, x + 2, y + 2, BLOOD);
		}
		g.centeredText(mc.font, mc.options.keyAttack.getTranslatedKeyMessage(), cx, cy - 4, TEXT);
	}

	private static void victimQte(GuiGraphicsExtractor g, Minecraft mc, int w, int h, float partial) {
		HwNet.Qte qte = ClientState.qte;
		int barW = 180;
		int x = w / 2 - barW / 2;
		int y = (int) (h * 0.62);
		g.fill(x - 6, y - 22, x + barW + 6, y + 34, 0xB0000000);
		g.centeredText(mc.font, Component.translatable("halloween.hud.qte", mc.options.keyAttack.getTranslatedKeyMessage()), w / 2, y - 16, TEXT);

		if (mc.level.getGameTime() - qte.roundStart() + partial >= 0) {
			g.fill(x, y, x + barW, y + 10, 0xFF1A1A1A);
			int z0 = x + (int) (barW * qte.zoneStart());
			int z1 = x + (int) (barW * (qte.zoneStart() + qte.zoneWidth()));
			g.fill(z0, y, z1, y + 10, 0xFF3FA34D);

			double elapsed = mc.level.getGameTime() - qte.roundStart() + partial;
			float cursor = HwNet.qteCursor(Math.max(0, elapsed), qte.period());
			int cx = x + (int) (barW * cursor);
			g.fill(cx - 1, y - 3, cx + 1, y + 13, 0xFFFFFFFF);

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

	/** XP feed on the left: "+075 XP" and the reasons, fading out after a few seconds. */
	private static void feed(GuiGraphicsExtractor g, Font font, int h) {
		long now = System.currentTimeMillis();
		int y = h / 2 - 50;
		for (ClientState.FeedEntry entry : ClientState.FEED) {
			long age = now - entry.time();
			if (age > 5000) {
				continue;
			}
			int alpha = (int) (255 * Mth.clamp((5000 - age) / 800f, 0, 1));
			if (alpha < 8) {
				continue;
			}
			int color = (alpha << 24) | (TEXT & 0xFFFFFF);
			g.pose().pushMatrix();
			g.pose().translate(10, y);
			g.pose().scale(1.6f, 1.6f);
			g.text(font, String.format("+%03d", entry.points()), 0, 0, color, true);
			g.pose().popMatrix();
			g.text(font, "XP", 10 + (int) (font.width("+000") * 1.6f) + 3, y + 5, color, true);
			y += 16;
			for (Component line : entry.lines()) {
				g.text(font, line, 10, y, color, true);
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
		abilityDiamond(g, font, senseX, baseY, "◉", HalloweenClient.KEY_SENSE,
			hud.sense() ? 0xE0D8D2C4 : DARK_FILL, 1f - hud.fatigue(), senseTired ? 0xFFC77B2A : 0xFF606060, hud.sense() ? 0xFF101010 : TEXT);

		// Shape Jump diamond: red while unavailable.
		int jumpX = w - 62;
		boolean jumpReady = hud.jumpBlock() == 0;
		boolean inShadow = hud.shadowTicks() > 0;
		float jumpProgress = !inShadow && hud.jumpBlock() == 3 ? 1f - (float) hud.jumpCooldown() / Math.max(1, hud.jumpCooldownMax()) : 1f;
		abilityDiamond(g, font, jumpX, baseY, "»", HalloweenClient.KEY_SHAPE_JUMP,
			inShadow ? (jumpReady ? 0xE07A5CC8 : 0xE0602A6A) : jumpReady ? DARK_FILL : RED_FILL, jumpProgress, 0xFF606060, TEXT);
		if (inShadow && hud.jumpBlock() != 0) {
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
		abilityDiamond(g, font, impulseX, impulseY, "◎", HalloweenClient.KEY_IMPULSE,
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
		smallAction(g, font, sx, sy + 22, Component.translatable("halloween.hud.dash"), mc.options.keySprint.getTranslatedKeyMessage(),
			hud.dashTicks() > 0 ? 0 : hud.dashCooldown());
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
			line = Component.translatable(mc.player.isInvisible() ? "halloween.hud.prompt.locker_out" : "halloween.hud.prompt.locker");
		} else 		if (state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) {
			var base = ClientInteractions.doorBase(mc.level, pos);
			line = Component.translatable(ClientState.BROKEN_LOCKS.contains(base) ? "halloween.hud.prompt.door_broken"
				: ClientState.LOCKED_DOORS.contains(base) ? "halloween.hud.prompt.door_locked" : "halloween.hud.prompt.door");
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


	private static void healthBar(GuiGraphicsExtractor g, Minecraft mc, int w, int h) {
		int x = w - 110;
		int y = h - 22;
		g.fill(x - 4, y - 14, x + 106, h - 6, 0x99000000);
		g.text(mc.font, Component.translatable("halloween.hud.health"), x, y - 10, TEXT, true);
		float frac = mc.player.getHealth() / Math.max(1f, mc.player.getMaxHealth());
		bar(g, x, y, 100, 6, frac, frac < 0.35f ? BLOOD : 0xFFD8D2C4);
	}

	// ---------------------------------------------------------------- drawing helpers

	private static void bar(GuiGraphicsExtractor g, int x, int y, int width, int height, float frac, int color) {
		g.fill(x, y, x + width, y + height, 0xFF1A1A1A);
		g.fill(x, y, x + (int) (width * Mth.clamp(frac, 0, 1)), y + height, color);
	}

	/** Filled diamond (square rotated 45 degrees) with half-diagonal {@code size}. */
	private static void diamond(GuiGraphicsExtractor g, int cx, int cy, int size, int color) {
		for (int dy = -size; dy <= size; dy++) {
			int half = size - Math.abs(dy);
			g.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color);
		}
	}

	/** Arc of a ring from {@code from} to {@code to} (fractions of a turn, clockwise from the top). */
	private static void ring(GuiGraphicsExtractor g, float cx, float cy, float r, int thickness, float from, float to, int color) {
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
