package velora.outbreak.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import velora.outbreak.OutbreakContent;
import velora.outbreak.config.OutbreakConfig;
import velora.outbreak.diagnosis.DiagnosisService;
import velora.outbreak.diagnosis.SampleData;
import velora.outbreak.illness.*;
import velora.outbreak.treatment.TreatmentItem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class OutbreakCommands {
    private static final String TARGETS = "villagers";
    private static final int LIST_LIMIT = 20;
    private static final int CLUSTER_LIMIT = 5;
    private static final int CONFIG_WARNINGS_SHOWN = 10;
    private static final int ONSET_AREAS_SHOWN = 8;
    private static final SimpleCommandExceptionType NO_VILLAGERS =
            new SimpleCommandExceptionType(Component.literal("No villagers matched"));

    private OutbreakCommands() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) ->
                dispatcher.register(Commands.literal("outbreak")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("infect")
                                .then(Commands.argument(TARGETS, EntityArgument.entities())
                                        .then(Commands.argument("illness", StringArgumentType.word())
                                                .suggests((c, b) -> SharedSuggestionProvider.suggest(IllnessRegistry.ids(), b))
                                                .executes(OutbreakCommands::infect))))
                        .then(Commands.literal("cure")
                                .then(Commands.argument(TARGETS, EntityArgument.entities())
                                        .executes(OutbreakCommands::cure)))
                        .then(Commands.literal("treat")
                                .then(Commands.argument(TARGETS, EntityArgument.entities())
                                        .executes(OutbreakCommands::treat)))
                        .then(Commands.literal("stage")
                                .then(Commands.argument(TARGETS, EntityArgument.entities())
                                        .then(Commands.argument("state", StringArgumentType.word())
                                                .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                                        Arrays.stream(IllnessState.values()).map(IllnessState::getSerializedName), b))
                                                .executes(OutbreakCommands::stage))))
                        .then(Commands.literal("sneeze")
                                .then(Commands.argument(TARGETS, EntityArgument.entities())
                                        .executes(OutbreakCommands::sneeze)))
                        .then(Commands.literal("give")
                                .then(Commands.literal("sample")
                                        .then(Commands.argument("villager", EntityArgument.entity())
                                                .executes(ctx -> giveSample(ctx, true))
                                                .then(Commands.literal("unusable")
                                                        .executes(ctx -> giveSample(ctx, false)))))
                                .then(Commands.literal("treatment")
                                        .then(Commands.argument("kind", StringArgumentType.word())
                                                .suggests((c, b) -> SharedSuggestionProvider.suggest(IllnessDefinition.KNOWN_TREATMENTS, b))
                                                .executes(OutbreakCommands::giveTreatment))))
                        .then(Commands.literal("inspect").executes(OutbreakCommands::inspect))
                        .then(Commands.literal("diagnose").executes(OutbreakCommands::diagnose))
                        .then(Commands.literal("list").executes(OutbreakCommands::list))
                        .then(Commands.literal("illnesses").executes(OutbreakCommands::illnesses))
                        .then(Commands.literal("rescan").executes(OutbreakCommands::rescan))
                        .then(Commands.literal("outbreaks").executes(OutbreakCommands::outbreaks))
                        .then(Commands.literal("config")
                                .then(Commands.literal("reload").executes(OutbreakCommands::configReload))
                                .then(Commands.literal("show").executes(OutbreakCommands::configShow)))
                        .then(Commands.literal("spread")
                                .then(Commands.literal("status").executes(OutbreakCommands::spreadStatus))
                                .then(Commands.literal("pass").executes(OutbreakCommands::spreadPass))
                                .then(Commands.literal("reset").executes(OutbreakCommands::spreadReset))
                                .then(Commands.literal("enabled")
                                        .then(Commands.argument("value", BoolArgumentType.bool())
                                                .executes(OutbreakCommands::spreadEnabled)))
                                .then(Commands.literal("chance")
                                        .then(Commands.argument("multiplier",
                                                        DoubleArgumentType.doubleArg(0.0, OutbreakConfig.HARD_MAX_CHANCE))
                                                .executes(OutbreakCommands::spreadChance)))
                                .then(Commands.literal("range")
                                        .then(Commands.argument("multiplier",
                                                        DoubleArgumentType.doubleArg(OutbreakConfig.HARD_MIN_RANGE,
                                                                OutbreakConfig.HARD_MAX_RANGE))
                                                .executes(OutbreakCommands::spreadRange))))
                        .then(Commands.literal("onset")
                                .then(Commands.literal("status").executes(OutbreakCommands::onsetStatus))
                                .then(Commands.literal("check").executes(OutbreakCommands::onsetCheck))
                                .then(Commands.literal("trigger")
                                        .executes(ctx -> onsetTrigger(ctx, false))
                                        .then(Commands.argument("illness", StringArgumentType.word())
                                                .suggests((c, b) -> SharedSuggestionProvider.suggest(IllnessRegistry.ids(), b))
                                                .executes(ctx -> onsetTrigger(ctx, true))))
                                .then(Commands.literal("reset").executes(OutbreakCommands::onsetReset)))));
    }

    private static List<Villager> villagers(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        List<Villager> out = new ArrayList<>();
        for (Entity e : EntityArgument.getEntities(ctx, TARGETS)) {
            if (e instanceof Villager v) out.add(v);
        }
        if (out.isEmpty()) throw NO_VILLAGERS.create();
        return out;
    }

    private static int infect(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        List<Villager> targets = villagers(ctx);
        String id = StringArgumentType.getString(ctx, "illness");
        IllnessDefinition def = IllnessRegistry.get(id);
        if (def == null) {
            ctx.getSource().sendFailure(Component.literal("Unknown illness: " + id));
            return 0;
        }
        int infected = 0;
        for (Villager v : targets) if (IllnessManager.infect(v, def)) infected++;
        final int n = infected;
        final int skipped = targets.size() - infected;
        if (n == 0) {
            ctx.getSource().sendFailure(Component.literal("No villager infected (already ill or immune)"));
            return 0;
        }
        StringBuilder who = new StringBuilder();
        for (Villager v : targets) who.append(' ').append(v.getUUID().toString(), 0, 8);
        ctx.getSource().sendSuccess(() -> Component.literal("Infected " + n + " villager(s) with " + id
                + (skipped > 0 ? " (" + skipped + " skipped: already ill or immune)" : "")
                + " [" + who.toString().trim() + "]"), true);
        return n;
    }

    private static int cure(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        int cured = 0;
        for (Villager v : villagers(ctx)) if (IllnessManager.cure(v)) cured++;
        final int n = cured;
        ctx.getSource().sendSuccess(() -> Component.literal("Cured " + n + " villager(s)"), true);
        return n;
    }

    private static int treat(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        List<Villager> targets = villagers(ctx);
        int started = 0;
        for (Villager v : targets) if (IllnessManager.beginTreatment(v)) started++;
        final int n = started;
        final int skipped = targets.size() - started;
        if (n == 0) {
            ctx.getSource().sendFailure(Component.literal("No villager is SYMPTOMATIC"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Started treatment on " + n + " villager(s)"
                + (skipped > 0 ? " (" + skipped + " not symptomatic)" : "")), true);
        return n;
    }

    private static int stage(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        List<Villager> targets = villagers(ctx);
        String name = StringArgumentType.getString(ctx, "state");
        IllnessState s = IllnessState.byName(name);
        if (s == null) {
            ctx.getSource().sendFailure(Component.literal("Unknown state: " + name));
            return 0;
        }
        int changed = 0;
        for (Villager v : targets) if (IllnessManager.setStage(v, s)) changed++;
        final int n = changed;
        if (n == 0) {
            ctx.getSource().sendFailure(Component.literal("No targeted villager has a known illness"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Stage set to " + name + " on " + n + " villager(s)"), true);
        return n;
    }

    private static int sneeze(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        int n = 0;
        for (Villager v : villagers(ctx)) {
            if (v.level() instanceof ServerLevel level) {
                SymptomManager.sneeze(v, level);
                n++;
            }
        }
        final int count = n;
        ctx.getSource().sendSuccess(() -> Component.literal("Sneezed " + count + " villager(s)"), false);
        return n;
    }

    /** Debug: snapshot a villager into a Prepared Petri Dish in the executing player's inventory. */
    private static int giveSample(CommandContext<CommandSourceStack> ctx, boolean viewable) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Entity e = EntityArgument.getEntity(ctx, "villager");
        if (!(e instanceof Villager v)) {
            ctx.getSource().sendFailure(Component.literal("Target is not a villager"));
            return 0;
        }
        SampleData d = DiagnosisService.giveSample(player, v, viewable);
        ctx.getSource().sendSuccess(() -> Component.literal("Gave sample " + d.shortId()
                + (viewable ? "" : " (unusable)") + " from villager " + d.shortSource()), false);
        return 1;
    }

    /** Debug: 8 treatment items of the given class into the executing player's inventory. */
    private static int giveTreatment(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String kind = StringArgumentType.getString(ctx, "kind");
        Item item = OutbreakContent.treatmentItem(kind);
        player.getInventory().placeItemBackInInventory(new ItemStack(item, 8));
        ctx.getSource().sendSuccess(() -> Component.literal("Gave 8 x " + kind), false);
        return 1;
    }

    /** Debug: resolves the dish in your hand with the correct answer and prints the truth. */
    private static int diagnose(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String msg = DiagnosisService.forceDiagnose(player);
        if (msg == null) {
            ctx.getSource().sendFailure(Component.literal("Hold a Prepared Petri Dish (main hand or offhand)"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        List<Villager> ill = IllnessTracker.snapshot();
        int shown = 0;
        for (Villager v : ill) {
            if (shown >= LIST_LIMIT) break;
            VillagerIllnessData d = v.getAttached(OutbreakAttachments.ILLNESS);
            if (d == null) continue;
            String line = v.getUUID().toString().substring(0, 8) + " " + d.illnessId() + " "
                    + d.state().getSerializedName() + " (" + d.ticksLeft() + "t left)"
                    + (d.treatment().isEmpty() ? "" : " treated=" + d.treatment() + "@" + d.treatedTick())
                    + " gen" + d.generation()
                    + " @ "
                    + v.blockPosition().toShortString();
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
            shown++;
        }
        final int total = ill.size();
        final int hidden = Math.max(0, total - shown);
        ctx.getSource().sendSuccess(() -> Component.literal(total + " tracked villager(s)"
                + (hidden > 0 ? " (" + hidden + " not shown)" : "")), false);
        return total;
    }

    /** Debug: prints the illness record of the villager nearest to you (within 10 blocks). */
    private static int inspect(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Villager best = null;
        double bestDist = 10.0 * 10.0;
        for (Villager v : player.level().getEntitiesOfClass(Villager.class, player.getBoundingBox().inflate(10.0))) {
            double dist = player.distanceToSqr(v);
            if (dist <= bestDist) {
                bestDist = dist;
                best = v;
            }
        }
        if (best == null) {
            ctx.getSource().sendFailure(Component.literal("No villager within 10 blocks"));
            return 0;
        }
        VillagerIllnessData d = best.getAttached(OutbreakAttachments.ILLNESS);
        final String id = best.getUUID().toString().substring(0, 8);
        final String line = d == null
                ? "Villager " + id + ": no illness data (healthy)"
                : "Villager " + id + ": " + d.illnessId() + " " + d.state().getSerializedName()
                + ", " + d.ticksLeft() + "t left, next symptom in " + d.ticksToSymptom() + "t"
                + (d.treatment().isEmpty() ? "" : ", treated=" + d.treatment())
                + ", gen " + d.generation()
                + (d.source().isEmpty() ? ", source: none" : ", source: " + (d.source().length() > 8 ? d.source().substring(0, 8) : d.source())
                + " @t" + d.infectedTick())
                + ", tracked=" + IllnessTracker.snapshot().contains(best)
                + ", recent=" + SymptomLog.describe(d.recent(), best.level().getGameTime());
        ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        return 1;
    }

    private static int illnesses(CommandContext<CommandSourceStack> ctx) {
        int count = 0;
        for (IllnessDefinition d : IllnessRegistry.all()) {
            String line = (IllnessRegistry.warnings(d.id()).isEmpty() ? "" : "[!] ") + d.id() + ": " + d.vector().getSerializedName()
                    + (d.contagious() ? ", contagious" : "")
                    + " | incubation " + d.incubationTicks()
                    + ", natural " + d.naturalRecoveryTicks()
                    + ", treatment " + d.treatmentTicks()
                    + ", recovery " + d.recoveryTicks()
                    + ", immunity " + d.immunityTicks()
                    + ", symptom every " + d.symptomIntervalTicks() + " ticks"
                    + " | cured by " + d.treatment()
                    + ", symptom x" + d.treatedSymptomFactor() + " treated / x" + d.recoveringSymptomFactor() + " recovering"
                    + (d.canSpread() ? " | spreads: " + spreadText(d.spread()) : " | does not spread")
                    + d.onset().map(o -> " | natural onset: " + o.describe()).orElse(" | no natural onset");
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
            List<String> warnings = IllnessRegistry.warnings(d.id());
            for (String w : warnings) {
                ctx.getSource().sendSuccess(() -> Component.literal("  WARNING: " + w), false);
            }
            count++;
        }
        return count;
    }

    private static int rescan(CommandContext<CommandSourceStack> ctx) {
        int[] r = IllnessTracker.rescan(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> Component.literal("Tracker had " + r[0] + ", full scan found " + r[1]), false);
        return r[1];
    }

    private static String spreadText(SpreadParams sp) {
        return "range " + sp.rangeBlocks() + ", chance " + sp.chance() + " every " + sp.intervalTicks()
                + "t, source factor incubating " + sp.incubatingFactor() + " / symptomatic " + sp.symptomaticFactor()
                + " / treated " + sp.treatedFactor() + " / recovering " + sp.recoveringFactor();
    }

    /** Ground-truth census (includes hidden incubating villagers): admin only, never sent to clients. */
    private static int outbreaks(CommandContext<CommandSourceStack> ctx) {
        Map<String, OutbreakTracker.Census> census = OutbreakTracker.census();
        if (census.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("No tracked villagers (loaded only)"), false);
            return 0;
        }
        int total = 0;
        for (OutbreakTracker.Census c : census.values()) {
            total += c.ill();
            String head = c.illnessId + ": " + c.ill() + " ill ("
                    + c.count(IllnessState.INCUBATING) + " incubating, "
                    + c.count(IllnessState.SYMPTOMATIC) + " symptomatic, "
                    + c.count(IllnessState.UNDER_TREATMENT) + " treated, "
                    + c.count(IllnessState.RECOVERING) + " recovering), "
                    + c.immune() + " immune, longest chain gen " + c.maxGeneration;
            ctx.getSource().sendSuccess(() -> Component.literal(head), false);
            int shown = 0;
            for (OutbreakTracker.Cluster k : c.clusters) {
                if (shown++ >= CLUSTER_LIMIT) break;
                String line = "  cluster: " + k.villagers() + " villager(s) (" + k.symptomatic()
                        + " symptomatic) near " + k.x() + " " + k.y() + " " + k.z() + " [" + k.dimension() + "]";
                ctx.getSource().sendSuccess(() -> Component.literal(line), false);
            }
        }
        ctx.getSource().sendSuccess(() -> Component.literal("(ground truth incl. incubating; loaded villagers only)"), false);
        return total;
    }

    private static int spreadStatus(CommandContext<CommandSourceStack> ctx) {
        Boolean enabledOverride = SpreadConfig.enabledOverride();
        ctx.getSource().sendSuccess(() -> Component.literal("Spread " + (SpreadConfig.enabled() ? "ENABLED" : "DISABLED")
                + (enabledOverride == null ? " (config)" : " (runtime override)")
                + ", chance x" + SpreadConfig.chanceMultiplier() + " (override x" + SpreadConfig.chanceOverride() + ")"
                + ", range x" + SpreadConfig.rangeMultiplier() + " (override x" + SpreadConfig.rangeOverride() + ")"
                + " | pass every " + SpreadConfig.passIntervalTicks() + " ticks, caps per pass: "
                + SpreadConfig.maxSourcesPerPass() + " sources, "
                + SpreadConfig.maxCandidatesPerSource() + " candidates/source, "
                + SpreadConfig.maxInfectionsPerPass() + " infections"), false);
        for (IllnessDefinition d : IllnessRegistry.all()) {
            if (!d.canSpread()) continue;
            String line = "  " + d.id() + ": " + spreadText(d.spread());
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        SpreadManager.PassStats p = SpreadManager.lastPass();
        String last = p == null ? "no pass yet"
                : "last pass @tick " + p.tick() + (p.forced() ? " (forced)" : "") + ": " + p.dueSources() + " due, "
                + p.processedSources() + " processed, " + p.skippedByCap() + " skipped by cap, "
                + p.candidatesExamined() + " candidates, " + p.infections() + " infection(s)";
        ctx.getSource().sendSuccess(() -> Component.literal(last + " | " + SpreadManager.totalInfections()
                + " infection(s) by spread since start"), false);
        return 1;
    }

    /** Debug: one pass NOW, ignoring each source's schedule (caps still apply). */
    private static int spreadPass(CommandContext<CommandSourceStack> ctx) {
        SpreadManager.PassStats p = SpreadManager.runPass(ctx.getSource().getServer(), true);
        ctx.getSource().sendSuccess(() -> Component.literal("Forced pass: " + p.dueSources() + " source(s), "
                + p.processedSources() + " processed, " + p.skippedByCap() + " skipped by cap, "
                + p.candidatesExamined() + " candidates, " + p.infections() + " infection(s)"), true);
        return p.infections();
    }

    private static int spreadReset(CommandContext<CommandSourceStack> ctx) {
        SpreadConfig.resetOverrides();
        ctx.getSource().sendSuccess(() -> Component.literal("Spread overrides cleared; values come from the config file again"), true);
        return 1;
    }

    private static int spreadEnabled(CommandContext<CommandSourceStack> ctx) {
        boolean value = BoolArgumentType.getBool(ctx, "value");
        SpreadConfig.setEnabledOverride(value);
        ctx.getSource().sendSuccess(() -> Component.literal("Spread " + (value ? "enabled" : "disabled")
                + " (runtime override; /outbreak spread reset returns to the config value)"), true);
        return 1;
    }

    private static int spreadChance(CommandContext<CommandSourceStack> ctx) {
        double applied = SpreadConfig.setChanceOverride(DoubleArgumentType.getDouble(ctx, "multiplier"));
        ctx.getSource().sendSuccess(() -> Component.literal("Spread chance override = x" + applied
                + " (effective x" + SpreadConfig.chanceMultiplier() + ", limit from config)"), true);
        return 1;
    }

    private static int spreadRange(CommandContext<CommandSourceStack> ctx) {
        double applied = SpreadConfig.setRangeOverride(DoubleArgumentType.getDouble(ctx, "multiplier"));
        ctx.getSource().sendSuccess(() -> Component.literal("Spread range override = x" + applied
                + " (effective x" + SpreadConfig.rangeMultiplier() + ", limit from config)"), true);
        return 1;
    }

    /** Ticks until the next scheduled onset check (never negative, never more than one interval). */
    private static long onsetNextIn(MinecraftServer server, OutbreakConfig.Onset cfg) {
        long left = OnsetManager.nextCheckTick() - server.getTickCount();
        return Math.min(Math.max(0L, left), cfg.checkIntervalTicks());
    }

    /** One check's counters. Never names a villager or an illness. */
    private static String onsetCheckText(OnsetManager.CheckStats s) {
        if (s.skip() != OnsetManager.Skip.NONE) return "skipped (" + s.skip().name() + ")";
        return s.playersEligible() + " eligible / " + s.playersConsidered() + " considered player(s), "
                + s.areas() + " area(s) rolled, skipped " + s.skippedDeduped() + " deduped / "
                + s.skippedCeiling() + " ill-nearby ceiling / " + s.skippedCooldown() + " area cooldown, "
                + s.candidates() + " candidate(s), " + s.rolls() + " roll(s), " + s.rejected() + " rejected, "
                + s.cases() + " case(s)";
    }

    private static int onsetStatus(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        OutbreakConfig.Onset cfg = OutbreakConfig.onset();
        long tick = server.getTickCount();
        long gameTime = server.overworld().getGameTime();
        long nextIn = onsetNextIn(server, cfg);
        ctx.getSource().sendSuccess(() -> Component.literal("Onset " + (cfg.enabled() ? "ENABLED" : "DISABLED")
                + ", check every " + cfg.checkIntervalTicks() + "t"
                + (cfg.enabled() ? ", next check in " + nextIn + "t" : ", no scheduled checks")), false);
        String grace = gameTime >= cfg.graceTicks() ? "over" : (cfg.graceTicks() - gameTime) + "t left";
        String startup = tick >= cfg.startupDelayTicks() ? "over" : (cfg.startupDelayTicks() - tick) + "t left";
        ctx.getSource().sendSuccess(() -> Component.literal("grace: " + grace + " (overworld game time " + gameTime
                + " / " + cfg.graceTicks() + ") | startup delay: " + startup + " (server tick " + tick
                + " / " + cfg.startupDelayTicks() + ")"), false);
        long global = OnsetManager.globalCooldownRemaining(gameTime);
        List<OnsetManager.AreaCooldown> areas = OnsetManager.activeAreaCooldowns(gameTime);
        ctx.getSource().sendSuccess(() -> Component.literal("global cooldown: " + (global > 0 ? global + "t left" : "none")
                + " | area cooldowns: " + areas.size() + " active (radius " + cfg.areaCooldownRadius() + ")"), false);
        int shown = 0;
        for (OnsetManager.AreaCooldown a : areas) {
            if (shown >= ONSET_AREAS_SHOWN) break;
            String line = "  " + a.level().identifier() + " " + (long) Math.floor(a.x()) + " " + (long) Math.floor(a.z())
                    + ", " + (a.expiresAt() - gameTime) + "t left";
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
            shown++;
        }
        if (areas.size() > shown) {
            final int more = areas.size() - shown;
            ctx.getSource().sendSuccess(() -> Component.literal("  ... and " + more + " more"), false);
        }
        ctx.getSource().sendSuccess(() -> Component.literal("tracked villagers (ill or immune): " + IllnessTracker.size()
                + " / " + (cfg.maxTrackedGlobal() > 0 ? String.valueOf(cfg.maxTrackedGlobal()) : "no ceiling")
                + " | natural cases (incl. triggered) since server start or reset: " + OnsetManager.totalCases()), false);
        OnsetManager.CheckStats s = OnsetManager.lastCheck();
        String last = s == null ? "last check: no check yet"
                : "last check (" + (s.forced() ? "forced" : "scheduled") + ", " + Math.max(0L, tick - s.tick())
                + "t ago): " + onsetCheckText(s);
        ctx.getSource().sendSuccess(() -> Component.literal(last), false);
        return 1;
    }

    /** Debug: one normal onset check NOW; every rule applies (enabled, grace, startup delay, cooldowns, ceilings). */
    private static int onsetCheck(CommandContext<CommandSourceStack> ctx) {
        OnsetManager.CheckStats s = OnsetManager.runCheck(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> Component.literal("Onset check (forced, all rules apply): "
                + onsetCheckText(s)), true);
        return s.cases();
    }

    /** Debug: a guaranteed natural case near you (see OnsetManager.trigger). */
    private static int onsetTrigger(CommandContext<CommandSourceStack> ctx, boolean withIllness) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        IllnessDefinition def = null;
        if (withIllness) {
            String id = StringArgumentType.getString(ctx, "illness");
            def = IllnessRegistry.get(id);
            if (def == null) {
                ctx.getSource().sendFailure(Component.literal("Unknown illness: " + id));
                return 0;
            }
        }
        OnsetManager.TriggerResult r = OnsetManager.trigger(ctx.getSource().getServer(), player, def);
        if (!r.success()) {
            ctx.getSource().sendFailure(Component.literal("Onset trigger failed: " + r.message()));
            return 0;
        }
        OutbreakConfig.Onset cfg = OutbreakConfig.onset();
        ctx.getSource().sendSuccess(() -> Component.literal("Natural case triggered: villager " + r.villager()
                + " now incubating " + r.illnessId() + " (" + r.message() + "); cooldowns armed: global "
                + cfg.globalCooldownTicks() + "t, area " + cfg.areaCooldownTicks() + "t r" + cfg.areaCooldownRadius()), true);
        return 1;
    }

    /** Clears onset cooldowns and stats; the check schedule is kept (see OnsetManager.clearCooldownsAndStats). */
    private static int onsetReset(CommandContext<CommandSourceStack> ctx) {
        OnsetManager.clearCooldownsAndStats();
        long nextIn = onsetNextIn(ctx.getSource().getServer(), OutbreakConfig.onset());
        ctx.getSource().sendSuccess(() -> Component.literal("Onset cooldowns and stats cleared (schedule unchanged: next check in "
                + nextIn + "t)"), true);
        return 1;
    }

    private static void sendConfigWarnings(CommandContext<CommandSourceStack> ctx, List<String> warnings) {
        int shown = 0;
        for (String w : warnings) {
            if (shown++ >= CONFIG_WARNINGS_SHOWN) break;
            ctx.getSource().sendSuccess(() -> Component.literal("  warning: " + w), false);
        }
        if (warnings.size() > CONFIG_WARNINGS_SHOWN) {
            final int more = warnings.size() - CONFIG_WARNINGS_SHOWN;
            ctx.getSource().sendSuccess(() -> Component.literal("  ... and " + more + " more (see the server log)"), false);
        }
    }

    private static int configReload(CommandContext<CommandSourceStack> ctx) {
        OutbreakConfig.LoadResult r = OutbreakConfig.reload();
        SymptomPause.releaseAll(); // a changed pause_ticks applies to the next pause only
        ctx.getSource().sendSuccess(() -> Component.literal("Config reloaded from " + r.path()
                + (r.usedDefaultsOnly() ? " (defaults in use)" : "") + ": " + r.warnings().size() + " warning(s)"), true);
        sendConfigWarnings(ctx, r.warnings());
        return 1;
    }

    private static int configShow(CommandContext<CommandSourceStack> ctx) {
        OutbreakConfig.LoadResult r = OutbreakConfig.lastResult();
        OutbreakConfig.Spread s = OutbreakConfig.spread();
        OutbreakConfig.Symptoms y = OutbreakConfig.symptoms();
        ctx.getSource().sendSuccess(() -> Component.literal("Config file: " + r.path()
                + (r.usedDefaultsOnly() ? " (not usable: defaults in use)" : "") + ", " + r.warnings().size() + " warning(s)"), false);
        ctx.getSource().sendSuccess(() -> Component.literal("spread: enabled=" + s.enabled() + " chance x" + s.chanceMultiplier()
                + " range x" + s.rangeMultiplier() + " pass every " + s.passIntervalTicks() + " @offset " + s.passTickOffset()
                + " | caps " + s.maxSourcesPerPass() + "/" + s.maxCandidatesPerSource() + "/" + s.maxInfectionsPerPass()
                + " | edge weight " + s.edgeProximityWeight() + " | limits: chance <= x" + s.maxChanceMultiplier()
                + ", range x" + s.minRangeMultiplier() + ".." + s.maxRangeMultiplier() + ", max range " + s.maxEffectiveRange()), false);
        ctx.getSource().sendSuccess(() -> Component.literal("symptoms: frequency x" + y.frequencyMultiplier()
                + ", effect budget " + y.maxSymptomEffectsPerTick() + "/tick (recovery " + y.maxRecoveryEffectsPerTick() + "/tick)"
                + ", pause " + y.pauseTicks() + "t"), false);
        OutbreakConfig.Examination ex = OutbreakConfig.examination();
        ctx.getSource().sendSuccess(() -> Component.literal("examination: cooldown " + ex.cooldownTicks()
                + "t, recent window " + ex.recentWindowTicks() + "t"), false);
        OutbreakConfig.Onset on = OutbreakConfig.onset();
        ctx.getSource().sendSuccess(() -> Component.literal("onset: enabled=" + on.enabled()
                + " check every " + on.checkIntervalTicks() + "t, chance x" + on.chanceMultiplier()
                + " | caps " + on.maxNewCasesPerCheck() + " case(s)/" + on.maxPlayersPerCheck() + " players/"
                + on.maxCandidatesPerPlayer() + " candidates per player | box +-" + on.radiusBlocks()
                + " / +-" + on.verticalBlocks() + " | ceilings: ill nearby " + on.maxIllNearby()
                + ", tracked " + on.maxTrackedGlobal() + " (0 = none) | cooldowns: global " + on.globalCooldownTicks()
                + "t, area " + on.areaCooldownTicks() + "t r" + on.areaCooldownRadius()
                + " | grace " + on.graceTicks() + "t, startup delay " + on.startupDelayTicks() + "t"), false);
        ctx.getSource().sendSuccess(() -> Component.literal("effective now: spread " + (SpreadConfig.enabled() ? "ON" : "OFF")
                + ", chance x" + SpreadConfig.chanceMultiplier() + ", range x" + SpreadConfig.rangeMultiplier()), false);
        sendConfigWarnings(ctx, r.warnings());
        return 1;
    }
}