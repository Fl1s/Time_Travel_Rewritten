package com.fl1s.rewritten.timetravel.client;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.net.JumpRequestPayload;
import com.fl1s.rewritten.timetravel.net.TimelineNet;
import com.fl1s.rewritten.timetravel.net.TimelineSyncPayload;
import com.fl1s.rewritten.timetravel.net.ToggleTimelinePayload;
import com.fl1s.rewritten.timetravel.util.TimeFormat;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public final class TimelineClient {
    private TimelineClient() {
    }

    private static final String CATEGORY = "key.categories.timetravel_rewritten";

    public static final KeyMapping KEY_BACK = new KeyMapping("key.timetravel_rewritten.back", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_BRACKET, CATEGORY);
    public static final KeyMapping KEY_FORWARD = new KeyMapping("key.timetravel_rewritten.forward", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_BRACKET, CATEGORY);
    public static final KeyMapping KEY_CONFIRM = new KeyMapping("key.timetravel_rewritten.confirm", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_ENTER, CATEGORY);
    public static final KeyMapping KEY_CANCEL = new KeyMapping("key.timetravel_rewritten.cancel", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_BACKSPACE, CATEGORY);
    public static final KeyMapping KEY_RETURN = new KeyMapping("key.timetravel_rewritten.return", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_APOSTROPHE, CATEGORY);
    public static final KeyMapping KEY_TOGGLE = new KeyMapping("key.timetravel_rewritten.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_BACKSLASH, CATEGORY);
    public static final KeyMapping[] ALL_KEYS = {KEY_TOGGLE, KEY_RETURN, KEY_BACK, KEY_FORWARD, KEY_CONFIRM, KEY_CANCEL};

    private static final long NO_PREVIEW = -1L;

    private record JumpRecord(long from, long to) {
    }

    private static final List<JumpRecord> history = new ArrayList<>();

    private static final ResourceLocation HUD_TEXTURE = ResourceLocation.fromNamespaceAndPath("timetravel_rewritten", "textures/gui/hud.png");
    private static final ResourceLocation HUD_DARK_TEXTURE = ResourceLocation.fromNamespaceAndPath("timetravel_rewritten", "textures/gui/hud_dark.png");

    private static final int TEX_W = 160;
    private static final int TEX_H = 87;
    private static final int TEX_NORMAL_V = 0;
    private static final int TEX_PREVIEW_V = 35;

    private static final int TEX_ARROW_SIZE = 7;
    private static final int TEX_ARROW_UP_U = 0;
    private static final int TEX_ARROW_UP_V = 80;
    private static final int TEX_ARROW_DOWN_U = 7;
    private static final int TEX_ARROW_DOWN_V = 80;

    private static final int PANEL_W = 160;
    private static final int PANEL_H_NORMAL = 35;
    private static final int PANEL_H_PREVIEW = 45;
    private static final int BOTTOM_OFFSET = 55;
    private static final int SCREEN_MARGIN = 4;
    private static final int TOP_MARGIN = 20;

    private static final int WINDOW_LEFT = 2;
    private static final int WINDOW_RIGHT = 158;
    private static final int WINDOW_TOP = 2;
    private static final int WINDOW_BOTTOM_NORMAL = 33;
    private static final int WINDOW_BOTTOM_PREVIEW = 43;
    private static final int WINDOW_FILL = 0x80000000;

    private static final int COMPACT_BG = 0x90000000;

    private static final int COLOR_TEXT = 0xFFE0E0E0;
    private static final int COLOR_BAR_BG = 0xFF3A3A3A;
    private static final int COLOR_START = 0xFFE0E0E0;
    private static final int COLOR_NOW = 0xFFFFFFFF;
    private static final int COLOR_PAST = 0xFF55AAFF;
    private static final int COLOR_FUTURE = 0xFFFFDD00;
    private static final int COLOR_RETURN = 0xFFAAAAAA;
    private static final int COLOR_TICK = 0x40FFFFFF;

    private static boolean running = false;
    private static boolean showTicks = false;
    private static boolean showHud = true;
    private static long displayTick = 0;
    private static long serverTick = 0;
    private static boolean individualTime = false;
    private static long previewTick = NO_PREVIEW;
    private static long previewVisual = NO_PREVIEW;
    private static long viewCenterVisual = Long.MIN_VALUE;
    private static long returnTick = NO_PREVIEW;
    private static boolean synced = false;
    private static int holdBack = 0;
    private static int holdForward = 0;

    private static long cachedLine1Tick = Long.MIN_VALUE;
    private static boolean cachedLine1Ticks = false;
    private static Component cachedLine1;

    public static void onSync(TimelineSyncPayload payload) {
        running = payload.running();
        showTicks = payload.showTicks();
        showHud = payload.showHud();
        individualTime = payload.individualTime();
        if (synced && Math.abs(payload.tick() - displayTick) > 40) {
            if (payload.tick() == 0 && !payload.running()) {
                history.clear();
            } else {
                returnTick = displayTick;
                history.add(new JumpRecord(displayTick, payload.tick()));
                if (history.size() > 50) history.remove(0);
            }
        }
        synced = true;
        displayTick = payload.tick();
        serverTick = payload.serverTick();
        if (!running) {
            previewTick = NO_PREVIEW;
            previewVisual = NO_PREVIEW;
        }
    }

    public static void onClearChat() {
        Minecraft.getInstance().gui.getChat().clearMessages(false);
    }

    public static void reset() {
        synced = false;
        returnTick = NO_PREVIEW;
        running = false;
        displayTick = 0;
        serverTick = 0;
        individualTime = false;
        previewTick = NO_PREVIEW;
        previewVisual = NO_PREVIEW;
        viewCenterVisual = Long.MIN_VALUE;
        holdBack = holdForward = 0;
        cachedLine1Tick = Long.MIN_VALUE;
        history.clear();
        CloneVanishFx.clear();
    }

    private static long stepTicks() {
        if (Screen.hasAltDown()) return 20L * 600;
        if (Screen.hasControlDown()) return 20L * 60;
        if (Screen.hasShiftDown()) return 20L * 10;
        return 20L;
    }

    private static void scroll(int direction) {
        long base = (previewTick == NO_PREVIEW) ? displayTick : previewTick;
        long next = Math.max(0, base + direction * stepTicks());
        previewTick = (next == displayTick) ? NO_PREVIEW : next;
    }

    public static boolean onMouseScroll(double deltaY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return false;
        if (!running || previewTick == NO_PREVIEW) return false;
        if (deltaY == 0) return false;
        scroll(deltaY > 0 ? +1 : -1);
        return true;
    }

    private static void handleScrollKey(KeyMapping key, int direction, boolean back) {
        boolean acted = false;
        while (key.consumeClick()) {
            scroll(direction);
            acted = true;
            if (back) holdBack = 0;
            else holdForward = 0;
        }
        int held = back ? holdBack : holdForward;
        if (key.isDown()) {
            held++;
            if (!acted && held > 10 && held % 2 == 0) scroll(direction);
        } else {
            held = 0;
        }
        if (back) holdBack = held;
        else holdForward = held;
    }

    public static void clientTick(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            reset();
            return;
        }

        if (running && !mc.isPaused()) {
            displayTick++;
            serverTick++;
        }

        if (previewTick == NO_PREVIEW) {
            previewVisual = NO_PREVIEW;
        } else {
            if (previewVisual == NO_PREVIEW) previewVisual = displayTick;
            long diff = previewTick - previewVisual;
            if (Math.abs(diff) <= 2) previewVisual = previewTick;
            else previewVisual += diff / 4;
        }

        long targetCenter = previewTick != NO_PREVIEW
                ? (previewVisual != NO_PREVIEW ? previewVisual : previewTick)
                : displayTick;

        if (viewCenterVisual == Long.MIN_VALUE) {
            viewCenterVisual = targetCenter;
        } else {
            long diff = targetCenter - viewCenterVisual;
            if (Math.abs(diff) <= 2) viewCenterVisual = targetCenter;
            else viewCenterVisual += diff / 4;
        }

        while (KEY_TOGGLE.consumeClick()) {
            TimelineNet.sendToServer(new ToggleTimelinePayload());
            previewTick = NO_PREVIEW;
        }

        if (!running) {
            for (KeyMapping key : ALL_KEYS) while (key.consumeClick()) { /* ignore */ }
            return;
        }

        handleScrollKey(KEY_BACK, -1, true);
        handleScrollKey(KEY_FORWARD, +1, false);

        while (KEY_RETURN.consumeClick()) {
            if (returnTick != NO_PREVIEW) {
                TimelineNet.sendToServer(new JumpRequestPayload(returnTick));
                previewTick = NO_PREVIEW;
            }
        }

        while (KEY_CANCEL.consumeClick()) {
            previewTick = NO_PREVIEW;
        }

        while (KEY_CONFIRM.consumeClick()) {
            if (previewTick != NO_PREVIEW) {
                TimelineNet.sendToServer(new JumpRequestPayload(previewTick));
                previewTick = NO_PREVIEW;
            }
        }
    }

    public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        if (!running || !showHud || !TimeTravel.showTimelineInfo) return;
        if (mc.screen != null) return;

        boolean previewing = previewTick != NO_PREVIEW;
        boolean compact = TimeTravel.compactHud;
        boolean dark = TimeTravel.isDarkHud;

        if (compact) {
            Component line = previewing ? Component.translatable("hud.timetravel_rewritten.target", TimeFormat.format(previewTick, showTicks), TimeFormat.delta(previewTick - displayTick, showTicks))
                    : individualTime ? currentLine() : buildLine1(displayTick);
            renderCompact(graphics, mc, line, previewing);
            return;
        }

        Component line1 = buildLine1(individualTime ? serverTick : displayTick);
        Component line2 = null;
        int line1Color = COLOR_TEXT;
        int line2Color = COLOR_TEXT;
        if (individualTime && !previewing) {
            line2 = currentLine();
            line2Color = currentColor();
        }
        if (previewing) {
            if (individualTime) {
                line1 = currentLine();
                line1Color = currentColor();
            }
            String key = previewTick < displayTick ? "hud.timetravel_rewritten.target.past" : "hud.timetravel_rewritten.target.future";
            line2 = Component.translatable(key, TimeFormat.format(previewTick, showTicks), TimeFormat.delta(previewTick - displayTick, showTicks));
        }

        int panelW = PANEL_W;
        boolean tall = line2 != null;
        int panelH = tall ? PANEL_H_PREVIEW : PANEL_H_NORMAL;

        int gw = graphics.guiWidth();
        int gh = graphics.guiHeight();

        float s = TimeTravel.hudScale;
        int px = gw - (int) (panelW * s) - SCREEN_MARGIN;
        int py = TimeTravel.hudAtBottom ? gh - (int) (BOTTOM_OFFSET / s) - panelH : TOP_MARGIN;

        boolean scaled = Math.abs(s - 1.0f) > 1e-4f;
        if (scaled) {
            graphics.pose().pushPose();
            graphics.pose().translate(px, py, 0);
            graphics.pose().scale(s, s, 1f);
            px = 0;
            py = 0;
        }

        try {
            ResourceLocation tex = dark ? HUD_DARK_TEXTURE : HUD_TEXTURE;
            int vOff = tall ? TEX_PREVIEW_V : TEX_NORMAL_V;
            graphics.blit(tex, px, py, 0, vOff, panelW, panelH, TEX_W, TEX_H);

            int wb = tall ? WINDOW_BOTTOM_PREVIEW : WINDOW_BOTTOM_NORMAL;
            graphics.fill(px + WINDOW_LEFT, py + WINDOW_TOP, px + WINDOW_RIGHT, py + wb, WINDOW_FILL);

            int cx = px + panelW / 2;

            drawCenteredNoShadow(graphics, mc.font, line1, cx, py + 6, line1Color);

            if (line2 != null) {
                drawCenteredNoShadow(graphics, mc.font, line2, cx, py + 16, line2Color);
            }

            long previewPos = previewing ? (previewVisual != NO_PREVIEW ? previewVisual : previewTick) : NO_PREVIEW;
            int barY = py + (tall ? 35 : 25);
            renderTimelineBar(graphics, tex, cx, barY, panelW - 12, displayTick, viewCenterVisual, previewPos, returnTick, previewing);
        } finally {
            if (scaled) graphics.pose().popPose();
        }
    }

    private static Component currentLine() {
        return Component.translatable("hud.timetravel_rewritten.current_time", TimeFormat.format(displayTick, showTicks));
    }

    private static int currentColor() {
        return displayTick < serverTick ? COLOR_PAST : COLOR_TEXT;
    }

    private static void renderCompact(GuiGraphics graphics, Minecraft mc, Component line, boolean previewing) {
        int gw = graphics.guiWidth();
        int gh = graphics.guiHeight();

        int lineW = mc.font.width(line);
        int boxW = lineW + 12;
        int boxH = mc.font.lineHeight + 8;

        float s = TimeTravel.hudScale;
        int px = gw - (int) (boxW * s) - SCREEN_MARGIN;
        int py = TimeTravel.hudAtBottom ? gh - (int) (BOTTOM_OFFSET / s) - (int) (boxH * s) : TOP_MARGIN;

        graphics.pose().pushPose();
        graphics.pose().translate(px, py, 0);
        graphics.pose().scale(s, s, 1f);

        graphics.fill(0, 0, boxW, boxH, COMPACT_BG);
        graphics.fill(0, 0, boxW, 1, 0x30FFFFFF);

        int color = previewing ? (previewTick < displayTick ? COLOR_PAST : COLOR_FUTURE) : 0xFFFFFFFF;

        graphics.drawString(mc.font, line, (boxW - lineW) / 2, (boxH - mc.font.lineHeight) / 2, color, false);

        graphics.pose().popPose();
    }

    private static void drawCenteredNoShadow(GuiGraphics graphics, Font font, Component text, int cx, int y, int color) {
        int w = font.width(text);
        graphics.drawString(font, text, cx - w / 2, y, color, false);
    }

    private static void renderTimelineBar(GuiGraphics g, ResourceLocation tex, int cx, int y, int width, long display, long center, long preview, long ret, boolean previewing) {
        if (width < 20) return;

        int alphaMask = previewing ? 0x40 : 0xFF;
        int x0 = cx - width / 2;
        int h = 4;
        long half = 1200L;

        g.fill(x0, y, x0 + width, y + h, COLOR_BAR_BG);

        int dimTickColor = (COLOR_TICK & 0x00FFFFFF) | (alphaMask << 24);
        long startTick = center - half;
        long endTick = center + half;
        long firstTick = Math.max(0, (startTick / 20) * 20);
        if (firstTick < startTick && firstTick > 0) firstTick += 20;

        for (long t = firstTick; t <= endTick; t += 20) {
            int px = cx + (int) Math.round((t - center) * (width / 2.0) / half);
            if (px < x0 || px >= x0 + width) continue;

            boolean isMajor = (t % 100 == 0);
            int tickH = isMajor ? 3 : 1;
            g.fill(px, y + h - tickH, px + 1, y + h, dimTickColor);
        }

        long dStart = -center;
        if (dStart >= -half) {
            int sx = cx + (int) Math.round(dStart * (width / 2.0) / half);
            g.fill(sx, y - 1, sx + 1, y + h + 1, COLOR_START);
        } else {
            g.fill(x0, y - 1, x0 + 1, y + h + 1, COLOR_START);
        }

        if (ret != NO_PREVIEW) {
            long d = Math.max(-half, Math.min(half, ret - center));
            int rx = cx + (int) Math.round(d * (width / 2.0) / half);
            g.fill(rx - 1, y - 1, rx + 2, y + h + 1, COLOR_RETURN);
        }

        int arrowY = y - TEX_ARROW_SIZE;
        int branchY = arrowY - 3;

        int hSize = history.size();
        for (int i = 0; i < hSize; i++) {
            JumpRecord j = history.get(i);
            long dFrom = Math.max(-half, Math.min(half, j.from() - center));
            long dTo = Math.max(-half, Math.min(half, j.to() - center));
            int pxFrom = cx + (int) Math.round(dFrom * (width / 2.0) / half);
            int pxTo = cx + (int) Math.round(dTo * (width / 2.0) / half);

            if (pxFrom == pxTo) continue;

            float fade = hSize > 1 ? (0.15f + 0.85f * i / (float) (hSize - 1)) : 1.0f;
            int jumpAlpha = (int) (255 * fade);
            if (previewing) jumpAlpha /= 4;

            int c = j.to() < j.from() ? COLOR_PAST : COLOR_FUTURE;
            c = (c & 0x00FFFFFF) | (jumpAlpha << 24);

            g.fill(pxFrom, arrowY, pxFrom + 1, branchY, c);
            g.fill(pxTo, arrowY, pxTo + 1, branchY, c);
            g.fill(Math.min(pxFrom, pxTo), branchY, Math.max(pxFrom, pxTo) + 1, branchY + 1, c);

            RenderSystem.setShaderColor(1f, 1f, 1f, jumpAlpha / 255f);
            g.blit(tex, pxFrom - 3, arrowY, TEX_ARROW_UP_U, TEX_ARROW_UP_V, TEX_ARROW_SIZE, TEX_ARROW_SIZE, TEX_W, TEX_H);
            g.blit(tex, pxTo - 3, arrowY, TEX_ARROW_DOWN_U, TEX_ARROW_DOWN_V, TEX_ARROW_SIZE, TEX_ARROW_SIZE, TEX_W, TEX_H);
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        }

        if (preview != NO_PREVIEW) {
            long d = Math.max(-half, Math.min(half, preview - center));
            int px = cx + (int) Math.round(d * (width / 2.0) / half);
            int color = preview < display ? COLOR_PAST : COLOR_FUTURE;

            long dDisplay = Math.max(-half, Math.min(half, display - center));
            int pxDisplay = cx + (int) Math.round(dDisplay * (width / 2.0) / half);

            if (px != pxDisplay) {
                g.fill(pxDisplay, arrowY, pxDisplay + 1, branchY, color);
                g.fill(px, arrowY, px + 1, branchY, color);
                g.fill(Math.min(pxDisplay, px), branchY, Math.max(pxDisplay, px) + 1, branchY + 1, color);
            }

            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            g.blit(tex, pxDisplay - 3, arrowY, TEX_ARROW_UP_U, TEX_ARROW_UP_V, TEX_ARROW_SIZE, TEX_ARROW_SIZE, TEX_W, TEX_H);
            g.blit(tex, px - 3, arrowY, TEX_ARROW_DOWN_U, TEX_ARROW_DOWN_V, TEX_ARROW_SIZE, TEX_ARROW_SIZE, TEX_W, TEX_H);
        }

        long dNow = Math.max(-half, Math.min(half, display - center));
        int pxNow = cx + (int) Math.round(dNow * (width / 2.0) / half);
        g.fill(pxNow, y - 2, pxNow + 1, y + h + 2, COLOR_NOW);
    }

    private static Component buildLine1(long tick) {
        if (tick != cachedLine1Tick || showTicks != cachedLine1Ticks || cachedLine1 == null) {
            cachedLine1Tick = tick;
            cachedLine1Ticks = showTicks;
            cachedLine1 = Component.translatable("hud.timetravel_rewritten.time", TimeFormat.format(tick, showTicks));
        }
        return cachedLine1;
    }
}
