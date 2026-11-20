package velora.outbreak.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import velora.outbreak.OutbreakContent;
import velora.outbreak.hud.HudCase;
import velora.outbreak.hud.HudSync;

import java.util.ArrayList;
import java.util.List;

/**
 * Small corner overlay with the player's OWN confirmed cases, "last known". Input is the last HudSync only.
 * Aging and "nearby" are computed here, at most every REFRESH_TICKS (or when the list or dimension changes),
 * never per frame; a frame only draws the cached lines. Client thread only.
 */
public final class OutbreakHud {
    private OutbreakHud() {}

    private static final long TICKS_PER_DAY = 24_000L;
    private static final long REFRESH_TICKS = 40L;
    private static final int MARGIN = 4;

    private static List<HudCase> cases = List.of();
    private static boolean dirty;
    private static ResourceKey<Level> lastDimension;
    private static long computedAt = Long.MIN_VALUE;
    private static List<Component> lines = List.of();
    private static int boxWidth;

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(HudSync.TYPE, (payload, ctx) -> {
            cases = List.copyOf(payload.cases());
            dirty = true;
        });
        ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> reset());
        HudElementRegistry.addLast(OutbreakContent.id("outbreak_hud"), OutbreakHud::render);
    }

    private static void reset() {
        cases = List.of();
        lines = List.of();
        dirty = false;
        lastDimension = null;
        computedAt = Long.MIN_VALUE;
    }

    private static void render(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
        HudConfig.Values cfg = HudConfig.get();
        if (!cfg.enabled() || cases.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.screen != null || mc.getDebugOverlay().showDebugScreen()) return;
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) return;

        long now = level.getGameTime();
        ResourceKey<Level> dimension = level.dimension();
        if (dirty || dimension != lastDimension || now < computedAt || now - computedAt >= REFRESH_TICKS) {
            recompute(mc.font, cfg, dimension, player.blockPosition().getX(), player.blockPosition().getZ(), now);
        }
        if (lines.isEmpty()) return;

        Font font = mc.font;
        int lineStep = font.lineHeight + 1;
        int x = MARGIN;
        int y = MARGIN;
        g.fill(x - 2, y - 2, x + boxWidth + 2, y + lines.size() * lineStep + 1, 0x66000000);
        for (int i = 0; i < lines.size(); i++) {
            g.text(font, lines.get(i), x, y + i * lineStep, 0xFFE8E8E8, true);
        }
    }

    /** Groups the not-aged-out cases in this dimension within the radius by illness; newest group first. */
    private static void recompute(Font font, HudConfig.Values cfg, ResourceKey<Level> dimension,
                                  int px, int pz, long now) {
        dirty = false;
        lastDimension = dimension;
        computedAt = now;

        String dim = dimension.identifier().toString();
        long maxAge = cfg.ageOutDays() * TICKS_PER_DAY;
        double radius = cfg.nearbyRadiusBlocks();
        double radiusSq = radius * radius;

        List<String> ids = new ArrayList<>();
        int[] count = new int[HudCase.MAX_CASES];
        long[] newest = new long[HudCase.MAX_CASES];
        for (HudCase c : cases) {
            if (!c.dimension().equals(dim)) continue;
            if (now - c.tick() > maxAge) continue; // aged out (a negative age, e.g. clock skew, is kept)
            double dx = c.x() - px;
            double dz = c.z() - pz;
            if (dx * dx + dz * dz > radiusSq) continue;
            int group = ids.indexOf(c.illness());
            if (group < 0) {
                ids.add(c.illness());
                group = ids.size() - 1;
                newest[group] = Long.MIN_VALUE;
            }
            count[group]++;
            newest[group] = Math.max(newest[group], c.tick());
        }

        List<Component> out = new ArrayList<>();
        boolean[] used = new boolean[ids.size()];
        int width = 0;
        for (int n = 0; n < cfg.maxLines() && n < ids.size(); n++) {
            int best = -1;
            for (int i = 0; i < ids.size(); i++) {
                if (!used[i] && (best < 0 || newest[i] > newest[best])) best = i;
            }
            used[best] = true;
            String id = ids.get(best);
            Component name = Component.translatableWithFallback("illness.outbreak." + id, id);
            Component ago = ago(Math.max(0L, now - newest[best]) / TICKS_PER_DAY);
            Component line = count[best] == 1
                    ? Component.translatable("hud.outbreak.case.one", name, ago)
                    : Component.translatable("hud.outbreak.case.many", name, count[best], ago);
            out.add(line);
            width = Math.max(width, font.width(line));
        }
        lines = List.copyOf(out);
        boxWidth = width;
    }

    private static Component ago(long days) {
        if (days <= 0) return Component.translatable("hud.outbreak.ago.today");
        if (days == 1) return Component.translatable("hud.outbreak.ago.day");
        return Component.translatable("hud.outbreak.ago.days", days);
    }
}