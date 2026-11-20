package velora.outbreak.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;
import velora.outbreak.diagnosis.Feature;
import velora.outbreak.diagnosis.JournalEntry;
import velora.outbreak.diagnosis.ObservationProfile;
import velora.outbreak.diagnosis.Optics;
import velora.outbreak.diagnosis.Outcome;
import velora.outbreak.diagnosis.SampleData;
import velora.outbreak.net.OutbreakNetworking.AnalyzeIntent;
import velora.outbreak.net.OutbreakNetworking.DiagnosisResult;
import velora.outbreak.net.OutbreakNetworking.JournalRequest;
import velora.outbreak.net.OutbreakNetworking.Observation;
import velora.outbreak.net.OutbreakNetworking.OpenMicroscope;
import velora.outbreak.net.OutbreakNetworking.SubmitIntent;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Client-only. Holds only what the server has revealed. The image is drawn procedurally from the sample id
 * plus the observation levels the server returned, so unexamined features cannot be inferred from it.
 */
public class MicroscopeScreen extends Screen {
    private static final int W = 320;
    private static final int H = 244;
    private static final int VIEW = 120;
    private static final String[] MAG = {"\u00d740", "\u00d7100", "\u00d7400"};

    private final BlockPos pos;
    private final SampleData sample;
    private final List<String> choices;
    private final List<JournalEntry> journal;
    private final boolean usable;

    private final EnumMap<Feature, Integer> known = new EnumMap<>(Feature.class);
    private final List<AbstractWidget> widgets = new ArrayList<>();
    private final List<Button> zoomButtons = new ArrayList<>();
    private final List<Button> choiceButtons = new ArrayList<>();
    private Button submitButton;

    private int zoom = 1;
    private int focus = 50;
    private int cooldown = 0;
    private String selected = null;
    private boolean resolved = false;
    private Component status = Component.empty();
    private int left;
    private int top;

    public MicroscopeScreen(OpenMicroscope open) {
        super(Component.translatable("screen.outbreak.microscope"));
        this.pos = open.pos();
        this.sample = open.sample();
        this.choices = open.choices();
        this.journal = new ArrayList<>(open.journal());
        this.usable = open.usable();
        if (!usable) status = Component.translatable("status.outbreak.unusable");
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        clearWidgets();
        widgets.clear();
        zoomButtons.clear();
        choiceButtons.clear();
        left = (width - W) / 2;
        top = (height - H) / 2;
        button(Component.translatable("screen.outbreak.microscope.journal.open"), left + 56, top + 205, 74, 13,
                b -> ClientPlayNetworking.send(JournalRequest.INSTANCE));
        if (!usable) return;

        button(Component.literal("-"), left + 10, top + 148, 20, 16, b -> focus = Mth.clamp(focus - 2, 0, 100));
        button(Component.literal("+"), left + 110, top + 148, 20, 16, b -> focus = Mth.clamp(focus + 2, 0, 100));
        for (int i = 0; i < 3; i++) {
            final int z = i + 1;
            zoomButtons.add(button(Component.literal(MAG[i]), left + 10 + i * 41, top + 168, 38, 16, b -> {
                zoom = z;
                refresh();
            }));
        }
        button(Component.translatable("screen.outbreak.microscope.examine"), left + 10, top + 188, 120, 16, b -> examine());

        int rowH = Math.max(10, Math.min(15, 92 / Math.max(1, choices.size())));
        for (int i = 0; i < choices.size(); i++) {
            final String id = choices.get(i);
            choiceButtons.add(button(choiceName(id), left + 140, top + 124 + i * rowH, 170, rowH - 1, b -> {
                selected = id;
                refresh();
            }));
        }
        submitButton = button(Component.translatable("screen.outbreak.microscope.submit"), left + 140, top + 220, 170, 16, b -> submit());
        refresh();
    }

    private Button button(Component label, int x, int y, int w, int h, Button.OnPress action) {
        Button b = Button.builder(label, action).bounds(x, y, w, h).build();
        widgets.add(b);
        return addRenderableWidget(b);
    }

    private void refresh() {
        for (int i = 0; i < zoomButtons.size(); i++) {
            zoomButtons.get(i).setMessage(Component.literal(MAG[i])
                    .withStyle(zoom == i + 1 ? ChatFormatting.YELLOW : ChatFormatting.WHITE));
        }
        for (int i = 0; i < choiceButtons.size(); i++) {
            String id = choices.get(i);
            choiceButtons.get(i).setMessage(choiceName(id).copy()
                    .withStyle(id.equals(selected) ? ChatFormatting.YELLOW : ChatFormatting.WHITE));
            choiceButtons.get(i).active = !resolved;
        }
        if (submitButton != null) submitButton.active = !resolved;
    }

    // ------------------------------------------------------------------ intents

    private void examine() {
        if (cooldown > 0 || !usable) return;
        cooldown = 10;
        ClientPlayNetworking.send(new AnalyzeIntent(pos, zoom, focus));
    }

    private void submit() {
        if (resolved || !usable) return;
        if (selected == null) {
            status = Component.translatable("status.outbreak.pick");
            return;
        }
        ClientPlayNetworking.send(new SubmitIntent(pos, selected));
    }

    @Override
    public void tick() {
        super.tick();
        if (cooldown > 0) cooldown--;
    }

    // ------------------------------------------------------------------ server replies

    public void onObservation(Observation o) {
        if (!o.sharp()) {
            status = Component.translatable("status.outbreak.blurry");
            return;
        }
        List<Feature> features = Feature.atZoom(o.zoom());
        for (int i = 0; i < features.size() && i < o.levels().size(); i++) {
            known.put(features.get(i), Mth.clamp(o.levels().get(i), 0, 3));
        }
        int idx = Mth.clamp(o.zoom(), 1, 3) - 1;
        status = Component.translatable("status.outbreak.examined", MAG[idx]);
    }

    public void onResult(DiagnosisResult r) {
        switch (r.outcome()) {
            case CORRECT -> {
                resolved = true;
                selected = r.choice();
                status = ObservationProfile.INCONCLUSIVE_CHOICE.equals(r.truth())
                        ? Component.translatable("result.outbreak.correct.inconclusive")
                        : Component.translatable("result.outbreak.correct", choiceName(r.truth()));
                journal.add(new JournalEntry(sample.sampleId(), 0L, r.choice(), Outcome.CORRECT));
            }
            case MISMATCH -> {
                status = Component.translatable("result.outbreak.mismatch", choiceName(r.choice()));
                journal.add(new JournalEntry(sample.sampleId(), 0L, r.choice(), Outcome.MISMATCH));
            }
            case INSUFFICIENT -> status = Component.translatable("result.outbreak.insufficient",
                    choiceName(r.choice()), missingNames(r.missing()));
        }
        refresh();
    }

    public void onServerClose() { onClose(); }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (usable && scrollY != 0) {
            focus = Mth.clamp(focus + (scrollY > 0 ? 2 : -2), 0, 100);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    // ------------------------------------------------------------------ rendering

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // Background (incl. blur) is drawn by the framework before this method; calling it again crashes
        // with "Can only blur once per frame".
        g.fill(left - 2, top - 2, left + W + 2, top + H + 2, 0xFF0B0B10);
        g.fill(left, top, left + W, top + H, 0xFF272730);

        g.text(font, title, left + 10, top + 8, 0xFFFFFFFF, false);
        drawViewport(g, left + 10, top + 22);

        if (usable) {
            g.text(font, Component.translatable("screen.outbreak.microscope.focus", focus), left + 36, top + 132, 0xFFB0B0B8, false);
            g.fill(left + 34, top + 153, left + 106, top + 159, 0xFF101015);
            int thumb = left + 34 + focus * 70 / 100;
            g.fill(thumb, top + 150, thumb + 3, top + 162, 0xFFE0C060);
        }

        // Observations
        g.text(font, Component.translatable("screen.outbreak.microscope.observations"), left + 140, top + 22, 0xFFFFFFFF, false);
        int y = top + 34;
        for (Feature f : Feature.values()) {
            Integer level = known.get(f);
            MutableComponent line = Component.translatable("observation.outbreak." + f.key()).append(": ");
            line.append(level == null
                    ? Component.literal("?").withStyle(ChatFormatting.DARK_GRAY)
                    : Component.translatable("observation.outbreak." + f.key() + "." + level).withStyle(ChatFormatting.YELLOW));
            g.text(font, line, left + 140, y, 0xFFD0D0D8, false);
            y += 10;
        }
        g.textWithWordWrap(font, status, left + 140, top + 80, 170, 0xFFFFD27F);

        g.text(font, Component.translatable("screen.outbreak.microscope.diagnosis"), left + 140, top + 112, 0xFFFFFFFF, false);

        // Journal (most recent last, newest two)
        g.text(font, Component.translatable("screen.outbreak.microscope.journal"), left + 10, top + 208, 0xFFFFFFFF, false);
        if (journal.isEmpty()) {
            g.text(font, Component.translatable("screen.outbreak.microscope.journal.empty"), left + 10, top + 219, 0xFF808088, false);
        } else {
            int shown = Math.min(2, journal.size());
            for (int i = 0; i < shown; i++) {
                JournalEntry e = journal.get(journal.size() - 1 - i);
                MutableComponent line = Component.literal("#" + e.shortSample() + " ").append(choiceName(e.choice()))
                        .append(e.outcome() == Outcome.CORRECT ? " [OK]" : " [X]");
                g.text(font, line, left + 10, top + 219 + i * 10, 0xFFA0A0A8, false);
            }
        }

        super.extractRenderState(g, mouseX, mouseY, partialTick); // draws the registered buttons
    }

    private void drawViewport(GuiGraphicsExtractor g, int x, int y) {
        g.fill(x - 1, y - 1, x + VIEW + 1, y + VIEW + 1, 0xFF000000);
        g.fill(x, y, x + VIEW, y + VIEW, 0xFFD9D4C4);
        if (!usable) return;

        g.enableScissor(x, y, x + VIEW, y + VIEW);
        long seed = sample.sampleId().getMostSignificantBits() ^ (zoom * 0x9E3779B97F4A7C15L);

        int blur = Math.max(0, Math.abs(focus - Optics.target(sample.sampleId(), zoom)) - Optics.TOLERANCE);
        int alpha = Math.max(60, 255 - blur * 5);
        int halo = blur / 8;

        int baseCount = switch (zoom) { case 1 -> 56; case 2 -> 22; default -> 9; };
        int radius = switch (zoom) { case 1 -> 2; case 2 -> 5; default -> 9; };
        Integer density = known.get(Feature.CELL_DENSITY);
        double factor = density == null ? 1.0 : (density == 0 ? 1.0 : density == 1 ? 1.4 : 1.9);
        int count = (int) (baseCount * factor);
        Integer type = known.get(Feature.CELL_TYPE);

        SplittableRandom rnd = new SplittableRandom(seed);
        for (int i = 0; i < count; i++) {
            int cx = x + rnd.nextInt(VIEW);
            int cy = y + rnd.nextInt(VIEW);
            int jitter = rnd.nextInt(3) - 1;
            drawCell(g, cx, cy, radius + jitter, type, alpha, halo);
        }

        Integer particles = known.get(Feature.PARTICLES);
        if (zoom == 3 && particles != null && particles > 0) {
            SplittableRandom pr = new SplittableRandom(seed ^ 0x51ED2701L);
            int n = particles == 1 ? 16 : particles == 3 ? 18 : 12;
            for (int i = 0; i < n; i++) {
                int px = x + 3 + pr.nextInt(VIEW - 6);
                int py = y + 3 + pr.nextInt(VIEW - 6);
                if (particles == 1) { // small spiky viral-like dots
                    int c = (alpha << 24) | 0x2E7D32;
                    g.fill(px - 2, py, px + 3, py + 1, c);
                    g.fill(px, py - 2, px + 1, py + 3, c);
                    g.fill(px - 1, py - 1, px + 2, py + 2, c);
                } else if (particles == 3) { // rod-shaped bacteria, some lying flat, some upright
                    int c = (alpha << 24) | 0x2F6F7A;
                    if (i % 2 == 0) g.fill(px - 3, py - 1, px + 4, py + 1, c);
                    else g.fill(px - 1, py - 3, px + 1, py + 4, c);
                } else { // pollen grains
                    g.fill(px - 3, py - 3, px + 4, py + 4, (alpha << 24) | 0x8A6D00);
                    g.fill(px - 2, py - 2, px + 3, py + 3, (alpha << 24) | 0xE6C619);
                }
            }
        }

        Integer inflammation = known.get(Feature.INFLAMMATION);
        if (zoom == 3 && inflammation != null && inflammation > 0) {
            int haze = inflammation == 1 ? 0x28 : 0x55;
            g.fill(x, y, x + VIEW, y + VIEW, (haze << 24) | 0xC03030);
        }
        g.disableScissor();
    }

    private void drawCell(GuiGraphicsExtractor g, int cx, int cy, int r, Integer type, int alpha, int halo) {
        int body;
        int nucleus = -1;
        boolean granules = false;
        boolean lobes = false;
        if (type == null) {
            body = 0xCFC6C0;
        } else if (type == 1) {
            body = 0x6B5B95;
            nucleus = 0x3B2F63;
        } else if (type == 2) {
            body = 0xE8946B;
            nucleus = 0x7A3B52;
            granules = true;
        } else if (type == 3) { // neutrophil: pale body, multi-lobed nucleus
            body = 0xD9D0E6;
            nucleus = 0x4B3A8C;
            lobes = true;
        } else {
            body = 0xE6C3C3;
        }
        if (halo > 0) disc(g, cx, cy, r + halo, ((alpha / 3) << 24) | body);
        disc(g, cx, cy, r, (alpha << 24) | body);
        if (nucleus >= 0 && r >= 4) {
            if (lobes) {
                int lr = Math.max(1, r / 3);
                disc(g, cx - lr, cy - lr, lr, (alpha << 24) | nucleus);
                disc(g, cx + lr, cy - lr / 2, lr, (alpha << 24) | nucleus);
                disc(g, cx, cy + lr, lr, (alpha << 24) | nucleus);
            } else {
                disc(g, cx, cy, Math.max(1, (int) (r * 0.6)), (alpha << 24) | nucleus);
            }
        }
        if (granules && r >= 5) {
            int c = (alpha << 24) | 0xB5472B;
            g.fill(cx - r / 2 - 1, cy - r / 2, cx - r / 2 + 1, cy - r / 2 + 2, c);
            g.fill(cx + r / 2, cy - 1, cx + r / 2 + 2, cy + 1, c);
            g.fill(cx - 1, cy + r / 2, cx + 1, cy + r / 2 + 2, c);
        }
    }

    private static void disc(GuiGraphicsExtractor g, int cx, int cy, int r, int argb) {
        if (r < 1) r = 1;
        for (int dy = -r; dy <= r; dy++) {
            int half = (int) Math.sqrt(r * r - dy * dy + 0.5);
            g.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, argb);
        }
    }

    // ------------------------------------------------------------------ text helpers

    private static Component choiceName(String id) {
        return switch (id) {
            case ObservationProfile.HEALTHY_CHOICE -> Component.translatable("diagnosis.outbreak.healthy");
            case ObservationProfile.INCONCLUSIVE_CHOICE -> Component.translatable("diagnosis.outbreak.inconclusive");
            default -> Component.translatableWithFallback("illness.outbreak." + id, id);
        };
    }

    private static Component missingNames(List<String> keys) {
        MutableComponent out = Component.empty();
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) out.append(", ");
            out.append(Component.translatable("observation.outbreak." + keys.get(i)));
        }
        return out;
    }
}