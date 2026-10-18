package com.fl1s.rewritten.timetravel.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.fl1s.rewritten.timetravel.util.TimeFormat;
import com.fl1s.rewritten.timetravel.net.JumpRequestPayload;
import com.fl1s.rewritten.timetravel.net.TimelineNet;
import com.fl1s.rewritten.timetravel.net.ToggleTimelinePayload;
import com.fl1s.rewritten.timetravel.net.TimelineSyncPayload;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public final class TimelineClient {
    private TimelineClient() {}

    private static final String CATEGORY = "key.categories.timetravel_rewritten";

    public static final KeyMapping KEY_BACK = new KeyMapping("key.timetravel_rewritten.back",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_BRACKET, CATEGORY);
    public static final KeyMapping KEY_FORWARD = new KeyMapping("key.timetravel_rewritten.forward",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_BRACKET, CATEGORY);
    public static final KeyMapping KEY_CONFIRM = new KeyMapping("key.timetravel_rewritten.confirm",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_ENTER, CATEGORY);
    public static final KeyMapping KEY_CANCEL = new KeyMapping("key.timetravel_rewritten.cancel",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_BACKSPACE, CATEGORY);
    public static final KeyMapping KEY_RETURN = new KeyMapping("key.timetravel_rewritten.return",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_APOSTROPHE, CATEGORY);
    public static final KeyMapping KEY_TOGGLE = new KeyMapping("key.timetravel_rewritten.toggle",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_BACKSLASH, CATEGORY);
    public static final KeyMapping[] ALL_KEYS = {KEY_TOGGLE, KEY_RETURN, KEY_BACK, KEY_FORWARD, KEY_CONFIRM, KEY_CANCEL};

    private static final long NO_PREVIEW = -1L;
    private static boolean running = false;
    private static boolean showTicks = false;
    private static boolean showHud = true;
    private static long displayTick = 0;
    private static long previewTick = NO_PREVIEW;
    private static long returnTick = NO_PREVIEW;
    private static boolean synced = false;
    private static int holdBack = 0;
    private static int holdForward = 0;
    private static int flashTicks = 0;

    public static void onSync(TimelineSyncPayload payload) {
        running = payload.running();
        showTicks = payload.showTicks();
        showHud = payload.showHud();
        if (synced && Math.abs(payload.tick() - displayTick) > 40) returnTick = displayTick;
        synced = true;
        displayTick = payload.tick();
        if (!running) previewTick = NO_PREVIEW;
    }

    public static void onClearChat() {
        Minecraft.getInstance().gui.getChat().clearMessages(false);
    }

    public static void reset() {
        synced = false;
        returnTick = NO_PREVIEW;
        running = false;
        displayTick = 0;
        previewTick = NO_PREVIEW;
        holdBack = holdForward = 0;
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

    private static void handleScrollKey(KeyMapping key, int direction, boolean back) {
        boolean acted = false;
        while (key.consumeClick()) {
            scroll(direction);
            acted = true;
            if (back) holdBack = 0; else holdForward = 0;
        }
        int held = back ? holdBack : holdForward;
        if (key.isDown()) {
            held++;
            if (!acted && held > 10 && held % 2 == 0) scroll(direction);
        } else {
            held = 0;
        }
        if (back) holdBack = held; else holdForward = held;
    }

    public static void clientTick(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            reset();
            return;
        }

        if (running && !mc.isPaused()) displayTick++;
        if (flashTicks > 0) flashTicks--;

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
                flashTicks = 20;
            }
        }

        while (KEY_CANCEL.consumeClick()) {
            previewTick = NO_PREVIEW;
        }

        while (KEY_CONFIRM.consumeClick()) {
            if (previewTick != NO_PREVIEW) {
                TimelineNet.sendToServer(new JumpRequestPayload(previewTick));
                previewTick = NO_PREVIEW;
                flashTicks = 20;
            }
        }
    }

    public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || !running || !showHud) return;

        boolean previewing = previewTick != NO_PREVIEW;

        Component line1 = Component.translatable("hud.timetravel_rewritten.time", TimeFormat.format(displayTick, showTicks));
        Component line2 = null;
        if (previewing) {
            line2 = Component.translatable("hud.timetravel_rewritten.target",
                    TimeFormat.format(previewTick, showTicks),
                    TimeFormat.delta(previewTick - displayTick, showTicks));
        }

        int width = graphics.guiWidth();
        int lineH = mc.font.lineHeight + 2;
        int lines = 1 + (line2 != null ? 1 : 0);

        int boxW = Math.max(mc.font.width(line1), line2 != null ? mc.font.width(line2) : 0) + 12;
        int x1 = (width - boxW) / 2;
        int y1 = 4;
        int y2 = y1 + lines * lineH + 6;

        graphics.fill(x1, y1, x1 + boxW, y2, 0x90000000);

        int y = y1 + 4;
        int mainColor = flashTicks > 0 ? 0xFF55FF55 : 0xFFFFFFFF;
        graphics.drawCenteredString(mc.font, line1, width / 2, y, mainColor);
        y += lineH;
        if (line2 != null) {
            int color = previewTick < displayTick ? 0xFF55AAFF : 0xFFFFAA00; //past is blue; future is orange
            graphics.drawCenteredString(mc.font, line2, width / 2, y, color);
        }
    }
}
