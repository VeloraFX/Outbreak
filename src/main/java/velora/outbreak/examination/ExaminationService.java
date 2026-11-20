package velora.outbreak.examination;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import velora.outbreak.config.OutbreakConfig;
import velora.outbreak.illness.IllnessState;
import velora.outbreak.illness.OutbreakAttachments;
import velora.outbreak.illness.Symptom;
import velora.outbreak.illness.SymptomLog;
import velora.outbreak.illness.VillagerIllnessData;

/**
 * Examination tool on a villager. Shows only what an examiner could SEE: how often the villager has recently
 * coughed, shivered, clutched their stomach or sneezed. It never names the illness or a treatment, and
 * healthy, incubating, recovered and "quiet" villagers all get the same "no symptoms" message.
 * Server-authoritative: distance and cooldown are checked here; the client only swings its arm.
 */
public final class ExaminationService {
    private static final double MAX_DIST_SQ = 3.5 * 3.5;

    private ExaminationService() {}

    /** Registered from OutbreakContent.init() as a UseEntityCallback listener. */
    public static InteractionResult onUse(Player player, Level level, InteractionHand hand, Entity entity) {
        if (!(entity instanceof Villager villager) || player.isSpectator()) return InteractionResult.PASS;
        ItemStack held = player.getItemInHand(hand);
        if (!(held.getItem() instanceof ExaminationToolItem)) return InteractionResult.PASS;
        if (player.getCooldowns().isOnCooldown(held)) return InteractionResult.FAIL; // cooldowns are synced; no swing
        if (level.isClientSide()) return InteractionResult.SUCCESS;                  // server decides; swing the arm
        if (!(player instanceof ServerPlayer sp) || !(level instanceof ServerLevel sl)) return InteractionResult.PASS;

        if (!villager.isAlive() || sp.distanceToSqr(villager) > MAX_DIST_SQ) {
            sp.sendOverlayMessage(Component.translatable("message.outbreak.examine.too_far"));
            return InteractionResult.FAIL;
        }
        OutbreakConfig.Examination cfg = OutbreakConfig.examination();
        sp.sendOverlayMessage(describe(villager.getAttached(OutbreakAttachments.ILLNESS),
                sl.getGameTime(), cfg.recentWindowTicks()));
        if (cfg.cooldownTicks() > 0) sp.getCooldowns().addCooldown(held, cfg.cooldownTicks());
        return InteractionResult.SUCCESS;
    }

    /**
     * The one place that turns illness data into examiner-visible text. Only SYMPTOMATIC, UNDER_TREATMENT and
     * RECOVERING villagers can produce anything; every other case (no data, INCUBATING, RECOVERED, or no recent
     * symptoms) returns the exact same "none" message.
     */
    public static Component describe(VillagerIllnessData d, long now, int windowTicks) {
        if (d != null && (d.state() == IllnessState.SYMPTOMATIC
                || d.state() == IllnessState.UNDER_TREATMENT
                || d.state() == IllnessState.RECOVERING)) {
            int[] counts = SymptomLog.counts(d.recent(), now, windowTicks);
            MutableComponent list = null;
            for (Symptom s : Symptom.values()) {
                int bucket = SymptomLog.bucket(counts[s.ordinal()]);
                if (bucket == 0) continue;
                Component part = Component.translatable("message.outbreak.examine." + s.key() + "." + bucket);
                if (list == null) list = Component.empty().append(part);
                else list.append(", ").append(part);
            }
            if (list != null) return Component.translatable("message.outbreak.examine.result", list);
        }
        return Component.translatable("message.outbreak.examine.none");
    }
}