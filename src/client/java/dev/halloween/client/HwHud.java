package dev.halloween.client;

import dev.halloween.HwConfig;
import dev.halloween.Role;
import dev.halloween.game.FocusPoints;
import dev.halloween.net.HwNet;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** Match HUD: mask and shadow overlays for the Shape, health and grab QTE for civilians. */
public final class HwHud {
	private static final int TEXT = 0xFFE8E2D6;
	private static final int DIM = 0xFF8A847A;
	private static final int BLOOD = 0xFFB3121B;
	private static final int PANEL = 0x99000000;

	private HwHud() {
	}

	public static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		Role role = ClientState.myRole();
		int w = g.guiWidth();
		int h = g.guiHeight();
		float partial = delta.getGameTimeDeltaPartialTick(false);
		if (role == Role.SHAPE) {
			HwNet.ShapeHud hud = ClientState.hud;
			if (hud == null) {
				return;
			}
			if (hud.shadowTicks() > 0) {
				shadowOverlay(g, w, h, mc.player.tickCount + partial);
			} else if (hud.sense()) {
				maskOverlay(g, w, h);
			}
			shapePanel(g, mc.font, hud, w, h);
			stalkTarget(g, mc, w, h, partial);
		} else if (role == Role.CIVILIAN) {
			if (mc.player.isSpectator()) {
				g.centeredText(mc.font, Component.translatable("halloween.hud.dead"), w / 2, h - 40, BLOOD);
				return;
			}
			healthBar(g, mc, w, h);
			if (ClientState.qte.active() && mc.level != null) {
				qte(g, mc, w, h, partial);
			}
		}
	}

	// ---------------------------------------------------------------- overlays

	/** Darkness everywhere except two almond-shaped eye holes, like looking out through the mask. */
	private static void maskOverlay(GuiGraphicsExtractor g, int w, int h) {
		int color = 0xF0060505;
		int cy = (int) (h * 0.43);
		int ry = Math.max(4, (int) (h * 0.11));
		int rx = Math.max(6, (int) (w * 0.13));
		int left = w / 2 - (int) (w * 0.14);
		int right = w / 2 + (int) (w * 0.14);
		for (int y = 0; y < h; y++) {
			int dy = y - cy;
			if (Math.abs(dy) >= ry) {
				g.fill(0, y, w, y + 1, color);
				continue;
			}
			double t = (double) dy / ry;
			// Flatter top edge, rounder bottom: reads more like eye slits than circles.
			double shape = dy < 0 ? Math.sqrt(1 - t * t * t * t) : Math.sqrt(1 - t * t);
			int half = (int) (rx * shape);
			// Slight upward tilt towards the outer corners.
			int tilt = (int) (dy * 0.25);
			g.fill(0, y, left - half - tilt, y + 1, color);
			g.fill(left + half + tilt, y, right - half - tilt, y + 1, color);
			g.fill(right + half + tilt, y, w, y + 1, color);
		}
		g.fillGradient(0, 0, w, h / 6, 0x80000000, 0x00000000);
	}

	/** Shape Jump: a dim, wavering, purple-black world. */
	private static void shadowOverlay(GuiGraphicsExtractor g, int w, int h, float time) {
		g.fill(0, 0, w, h, 0x70140022);
		for (int y = 0; y < h; y += 3) {
			int offset = (int) (Mth.sin(time * 0.35f + y * 0.07f) * 8);
			g.fill(offset - 8, y, w + offset + 8, y + 1, 0x22000000);
		}
		g.fillGradient(0, 0, w, h / 3, 0xC0000000, 0x00000000);
		g.fillGradient(0, h - h / 3, w, h, 0x00000000, 0xC0000000);
	}

	// ---------------------------------------------------------------- Shape

	private static void shapePanel(GuiGraphicsExtractor g, Font font, HwNet.ShapeHud hud, int w, int h) {
		int x = w - 160;
		int y = h - 74;
		g.fill(x - 4, y - 4, x + 156, h - 6, PANEL);

		String tier = switch (hud.tier()) {
			case 0 -> "I";
			case 1 -> "II";
			default -> "III";
		};
		g.text(font, Component.translatable("halloween.hud.bloodthirst", tier), x, y, BLOOD, true);
		bar(g, x, y + 10, 150, 5, hud.bloodthirst() / 100f, BLOOD);
		for (float threshold : HwConfig.get().bloodthirstTierThresholds) {
			if (threshold > 0) {
				int tx = x + (int) (150 * threshold / 100f);
				g.fill(tx, y + 9, tx + 1, y + 16, 0xFFFFFFFF);
			}
		}

		g.text(font, Component.translatable("halloween.hud.sense"), x, y + 20, hud.sense() ? TEXT : DIM, true);
		bar(g, x + 70, y + 22, 80, 4, 1f - hud.fatigue(), hud.fatigue() > 0.6f ? 0xFFC77B2A : 0xFFD8D2C4);

		Component jump;
		int jumpColor = DIM;
		if (hud.shadowTicks() > 0) {
			jump = Component.translatable("halloween.hud.jump.active", seconds(hud.shadowTicks()));
			jumpColor = 0xFFB07CFF;
		} else {
			jump = switch (hud.jumpBlock()) {
				case 0 -> Component.translatable("halloween.hud.jump.ready");
				case 1 -> Component.translatable("halloween.hud.jump.light");
				case 2 -> Component.translatable("halloween.hud.jump.seen");
				case 3 -> Component.translatable("halloween.hud.jump.cooldown", seconds(hud.jumpCooldown()));
				default -> Component.translatable("halloween.hud.jump.busy");
			};
			if (hud.jumpBlock() == 0) {
				jumpColor = TEXT;
			}
		}
		g.text(font, Component.translatable("halloween.hud.jump", HalloweenClient.KEY_SHAPE_JUMP.getTranslatedKeyMessage(), jump), x, y + 34, jumpColor, true);
		if (hud.jumpBlock() == 3) {
			bar(g, x, y + 44, 150, 2, 1f - (float) hud.jumpCooldown() / Math.max(1, hud.jumpCooldownMax()), 0xFFB07CFF);
		}

		Component grab = hud.grabCooldown() > 0
			? Component.translatable("halloween.hud.grab.cooldown", seconds(hud.grabCooldown()))
			: Component.translatable("halloween.hud.grab.ready");
		g.text(font, grab, x, y + 50, hud.grabCooldown() > 0 ? DIM : TEXT, true);
	}

	/** Stalk progress of the victim closest to the crosshair. */
	private static void stalkTarget(GuiGraphicsExtractor g, Minecraft mc, int w, int h, float partial) {
		Vec3 eye = mc.player.getEyePosition(partial);
		Vec3 look = mc.player.getViewVector(partial);
		HwNet.VictimInfo best = null;
		double bestAngle = 30;
		for (HwNet.VictimInfo info : ClientState.VICTIMS.values()) {
			Entity entity = mc.level.getEntity(info.entityId());
			if (!(entity instanceof LivingEntity living)) {
				continue;
			}
			Vec3 dir = FocusPoints.bodyCenter(living).subtract(eye).normalize();
			double angle = Math.toDegrees(Math.acos(Mth.clamp(look.dot(dir), -1, 1)));
			if (angle < bestAngle) {
				bestAngle = angle;
				best = info;
			}
		}
		if (best == null) {
			return;
		}
		HwConfig cfg = HwConfig.get();
		int x = w / 2 - 30;
		int y = h / 2 + 14;
		int color = best.stalk() >= cfg.stalkMarkedThreshold ? BLOOD : 0xFFD8D2C4;
		bar(g, x, y, 60, 3, best.stalk() / 100f, color);
		Component label = null;
		if (best.stalk() >= 100f) {
			label = Component.translatable("halloween.hud.stalk.full");
		} else if (best.stalk() >= cfg.stalkMarkedThreshold) {
			label = Component.translatable("halloween.hud.stalk.marked");
		} else if (best.stalk() >= cfg.stalkWeakenedThreshold) {
			label = Component.translatable("halloween.hud.stalk.weakened");
		}
		if (label != null) {
			g.centeredText(mc.font, label, w / 2, y + 6, color);
		}
	}

	// ---------------------------------------------------------------- Civilian

	private static void healthBar(GuiGraphicsExtractor g, Minecraft mc, int w, int h) {
		int x = w - 110;
		int y = h - 22;
		g.fill(x - 4, y - 14, x + 106, h - 6, PANEL);
		g.text(mc.font, Component.translatable("halloween.hud.health"), x, y - 10, TEXT, true);
		float frac = mc.player.getHealth() / Math.max(1f, mc.player.getMaxHealth());
		bar(g, x, y, 100, 6, frac, frac < 0.35f ? BLOOD : 0xFFD8D2C4);
	}

	private static void qte(GuiGraphicsExtractor g, Minecraft mc, int w, int h, float partial) {
		HwNet.Qte qte = ClientState.qte;
		int barW = 180;
		int x = w / 2 - barW / 2;
		int y = (int) (h * 0.62);
		g.fill(x - 6, y - 22, x + barW + 6, y + 26, 0xB0000000);
		g.centeredText(mc.font, Component.translatable("halloween.hud.qte", mc.options.keyJump.getTranslatedKeyMessage()), w / 2, y - 16, TEXT);

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

		int pipY = y + 18;
		for (int i = 0; i < qte.needed(); i++) {
			int px = w / 2 - qte.needed() * 6 + i * 12;
			g.fill(px, pipY, px + 8, pipY + 4, i < qte.successes() ? 0xFF3FA34D : 0xFF3A3A3A);
		}
		for (int i = 0; i < qte.maxMisses(); i++) {
			int px = x + barW - 6 - i * 8;
			g.fill(px, pipY, px + 5, pipY + 4, i < qte.misses() ? BLOOD : 0xFF3A3A3A);
		}
	}

	// ---------------------------------------------------------------- helpers

	private static void bar(GuiGraphicsExtractor g, int x, int y, int width, int height, float frac, int color) {
		g.fill(x, y, x + width, y + height, 0xFF1A1A1A);
		g.fill(x, y, x + (int) (width * Mth.clamp(frac, 0, 1)), y + height, color);
	}

	private static String seconds(int ticks) {
		return String.valueOf((ticks + 19) / 20);
	}
}
