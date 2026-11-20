package velora.outbreak.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import velora.outbreak.diagnosis.ObservationProfile;
import velora.outbreak.journal.JournalLine;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-only. Shows the player's own journal (as sent by the server) and fixed educational pages. All text
 * comes from the lang file. The pages are general background and not medical advice (see the About page).
 */
public class MedicalJournalScreen extends Screen {
    private static final int W = 340;
    private static final int H = 224;
    private static final int TAB_W = 112;
    private static final int TAB_H = 20;
    private static final int TAB_STEP = 22;

    private static final List<String> ILLNESS_PAGES = List.of("common_cold", "seasonal_allergies", "influenza", "food_poisoning");
    private static final List<String> SECTIONS = List.of("what", "spread", "helps", "prevent", "game", "sources");
    private static final int TOOLS_PARAGRAPHS = 6;
    private static final int ABOUT_PARAGRAPHS = 3;

    private static final int TAB_ENTRIES = 0;
    private static final int TAB_TOOLS = ILLNESS_PAGES.size() + 1;
    private static final int TAB_ABOUT = ILLNESS_PAGES.size() + 2;
    private static final int TAB_COUNT = ILLNESS_PAGES.size() + 3;

    /** A null text is a blank line. */
    private record Line(FormattedCharSequence text, int color) {}

    private final List<JournalLine> entries = new ArrayList<>(); // newest first
    private final Screen parent;
    private final List<Button> tabButtons = new ArrayList<>();
    private List<Line> lines = List.of();
    private int tab = TAB_ENTRIES;
    private int scroll = 0;
    private int left;
    private int top;
    private int contentX;
    private int contentY;
    private int contentW;
    private int contentH;

    /** @param parent the screen to return to (the microscope), or null to close to the game. */
    public MedicalJournalScreen(List<JournalLine> data, Screen parent) {
        super(Component.translatable("screen.outbreak.journal"));
        for (int i = data.size() - 1; i >= 0; i--) entries.add(data.get(i));
        this.parent = parent;
    }

    public Screen parent() { return parent; }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        clearWidgets();
        tabButtons.clear();
        left = (width - W) / 2;
        top = (height - H) / 2;
        contentX = left + 8 + TAB_W + 8;
        contentY = top + 22;
        contentW = W - (8 + TAB_W + 8) - 18;
        contentH = H - 22 - 34;

        for (int i = 0; i < TAB_COUNT; i++) {
            final int idx = i;
            tabButtons.add(button(tabName(i), left + 8, top + 22 + i * TAB_STEP, TAB_W, TAB_H, b -> select(idx)));
        }
        button(Component.translatable(parent != null ? "gui.back" : "gui.done"), left + 8, top + H - 28, TAB_W, 20, b -> onClose());
        button(Component.translatable("gui.up"), contentX, top + H - 28, 44, 20, b -> scrollBy(-5));
        button(Component.translatable("gui.down"), contentX + 48, top + H - 28, 44, 20, b -> scrollBy(5));
        rebuildLines();
        refreshTabs();
    }

    private Button button(Component label, int x, int y, int w, int h, Button.OnPress action) {
        Button b = Button.builder(label, action).bounds(x, y, w, h).build();
        return addRenderableWidget(b);
    }

    private void select(int idx) {
        tab = idx;
        scroll = 0;
        rebuildLines();
        refreshTabs();
    }

    private void refreshTabs() {
        for (int i = 0; i < tabButtons.size(); i++) {
            tabButtons.get(i).setMessage(tabName(i).copy().withStyle(i == tab ? ChatFormatting.YELLOW : ChatFormatting.WHITE));
        }
    }

    private static Component tabName(int i) {
        if (i == TAB_ENTRIES) return Component.translatable("journal.outbreak.tab.entries");
        if (i <= ILLNESS_PAGES.size()) return Component.translatable("illness.outbreak." + ILLNESS_PAGES.get(i - 1));
        if (i == TAB_TOOLS) return Component.translatable("journal.outbreak.tab.tools");
        return Component.translatable("journal.outbreak.tab.about");
    }

    // ------------------------------------------------------------------ content

    private void rebuildLines() {
        List<Line> out = new ArrayList<>();
        if (tab == TAB_ENTRIES) {
            entryLines(out);
        } else if (tab <= ILLNESS_PAGES.size()) {
            String id = ILLNESS_PAGES.get(tab - 1);
            for (String section : SECTIONS) {
                heading(out, Component.translatable("journal.outbreak.heading." + section));
                body(out, Component.translatable("journal.outbreak." + id + "." + section));
                out.add(new Line(null, 0));
            }
        } else if (tab == TAB_TOOLS) {
            for (int i = 1; i <= TOOLS_PARAGRAPHS; i++) {
                body(out, Component.translatable("journal.outbreak.tools." + i));
                out.add(new Line(null, 0));
            }
        } else {
            for (int i = 1; i <= ABOUT_PARAGRAPHS; i++) {
                body(out, Component.translatable("journal.outbreak.about." + i));
                out.add(new Line(null, 0));
            }
        }
        lines = out;
        scroll = Mth.clamp(scroll, 0, maxScroll());
    }

    private void heading(List<Line> out, Component text) {
        for (FormattedCharSequence s : font.split(text.copy().withStyle(ChatFormatting.GOLD).withStyle(ChatFormatting.BOLD), contentW)) {
            out.add(new Line(s, 0xFFFFFFFF));
        }
    }

    private void body(List<Line> out, Component text) {
        for (FormattedCharSequence s : font.split(text, contentW)) out.add(new Line(s, 0xFFD0D0D8));
    }

    private void entryLines(List<Line> out) {
        if (entries.isEmpty()) {
            body(out, Component.translatable("screen.outbreak.journal.empty"));
            return;
        }
        for (JournalLine e : entries) {
            ChatFormatting colour = switch (e.outcome()) {
                case CORRECT -> ChatFormatting.GREEN;
                case MISMATCH -> ChatFormatting.RED;
                default -> ChatFormatting.YELLOW;
            };
            MutableComponent head = choiceName(e.choice()).copy().append(" - ")
                    .append(Component.translatable("screen.outbreak.journal.outcome." + e.outcome().getSerializedName()));
            for (FormattedCharSequence s : font.split(head.withStyle(colour), contentW)) out.add(new Line(s, 0xFFFFFFFF));
            body(out, Component.translatable("screen.outbreak.journal.entry.when", e.gameTime() / 24000L, e.sample())
                    .withStyle(ChatFormatting.GRAY));
            e.where().ifPresent(w -> {
                long day = (e.sampledTick() >= 0 ? e.sampledTick() : e.gameTime()) / 24000L;
                body(out, Component.translatable("screen.outbreak.journal.entry.where", day, w.x(), w.z(), dimensionName(w.dimension()))
                        .withStyle(ChatFormatting.GRAY));
            });
            out.add(new Line(null, 0));
        }
    }

    private static Component dimensionName(String dim) {
        return switch (dim) {
            case "minecraft:overworld" -> Component.translatable("journal.outbreak.dim.overworld");
            case "minecraft:the_nether" -> Component.translatable("journal.outbreak.dim.the_nether");
            case "minecraft:the_end" -> Component.translatable("journal.outbreak.dim.the_end");
            default -> Component.literal(dim);
        };
    }

    private static Component choiceName(String id) {
        return switch (id) {
            case ObservationProfile.HEALTHY_CHOICE -> Component.translatable("diagnosis.outbreak.healthy");
            case ObservationProfile.INCONCLUSIVE_CHOICE -> Component.translatable("diagnosis.outbreak.inconclusive");
            default -> Component.translatableWithFallback("illness.outbreak." + id, id);
        };
    }

    // ------------------------------------------------------------------ scrolling

    private int step() { return font.lineHeight + 1; }

    private int visibleLines() { return Math.max(1, contentH / step()); }

    private int maxScroll() { return Math.max(0, lines.size() - visibleLines()); }

    private void scrollBy(int delta) { scroll = Mth.clamp(scroll + delta, 0, maxScroll()); }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0) {
            scrollBy(scrollY > 0 ? -3 : 3);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() { minecraft.setScreen(parent); }

    @Override
    public boolean isPauseScreen() { return false; }

    // ------------------------------------------------------------------ rendering

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // The framework draws the background before this method; do not draw it again.
        g.fill(left - 2, top - 2, left + W + 2, top + H + 2, 0xFF0B0B10);
        g.fill(left, top, left + W, top + H, 0xFF272730);
        g.text(font, title, left + 8, top + 8, 0xFFFFFFFF, false);

        g.fill(contentX - 3, contentY - 3, contentX + contentW + 3, contentY + contentH + 3, 0xFF14141A);
        g.enableScissor(contentX, contentY, contentX + contentW, contentY + contentH);
        int y = contentY;
        int shown = visibleLines();
        for (int i = scroll; i < lines.size() && i < scroll + shown; i++) {
            Line l = lines.get(i);
            if (l.text() != null) g.text(font, l.text(), contentX, y, l.color(), false);
            y += step();
        }
        g.disableScissor();

        if (lines.size() > shown) { // scroll bar
            int trackX = contentX + contentW + 6;
            g.fill(trackX, contentY, trackX + 4, contentY + contentH, 0xFF101015);
            int thumbH = Math.max(10, contentH * shown / lines.size());
            int thumbY = contentY + (contentH - thumbH) * scroll / Math.max(1, maxScroll());
            g.fill(trackX, thumbY, trackX + 4, thumbY + thumbH, 0xFFE0C060);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick); // buttons
    }
}