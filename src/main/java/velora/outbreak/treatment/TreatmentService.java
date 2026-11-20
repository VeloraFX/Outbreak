package velora.outbreak.treatment;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import velora.outbreak.illness.IllnessDefinition;
import velora.outbreak.illness.IllnessManager;
import velora.outbreak.illness.IllnessRegistry;
import velora.outbreak.illness.IllnessState;
import velora.outbreak.illness.OutbreakAttachments;
import velora.outbreak.illness.SymptomManager;
import velora.outbreak.illness.VillagerIllnessData;

/**
 * Use-treatment-item-on-villager. All validation is server-side and an item is only consumed when
 * treatment really starts. Wrong item / wrong state => overlay hint, item kept, villager unharmed.
 * There is deliberately no "must have diagnosed first" gate: the diagnosis only informs the choice.
 */
public final class TreatmentService {
    private static final double MAX_DIST_SQ = 3.5 * 3.5;

    private TreatmentService() {}

    /** Registered from OutbreakContent.init() as a UseEntityCallback listener. */
    public static InteractionResult onUse(Player player, Level level, InteractionHand hand, Entity entity) {
        if (!(entity instanceof Villager villager) || player.isSpectator()) return InteractionResult.PASS;
        ItemStack held = player.getItemInHand(hand);
        if (!(held.getItem() instanceof TreatmentItem item)) return InteractionResult.PASS;
        if (level.isClientSide()) return InteractionResult.SUCCESS; // server decides; swing the arm
        if (player instanceof ServerPlayer sp && level instanceof ServerLevel sl) {
            return apply(sp, sl, villager, held, item) ? InteractionResult.SUCCESS : InteractionResult.FAIL;
        }
        return InteractionResult.PASS;
    }

    /** @return true if treatment started (and one item was consumed outside creative mode). */
    private static boolean apply(ServerPlayer player, ServerLevel level, Villager villager,
                                 ItemStack stack, TreatmentItem item) {
        if (!villager.isAlive() || player.distanceToSqr(villager) > MAX_DIST_SQ) {
            player.sendOverlayMessage(Component.translatable("message.outbreak.treat.too_far"));
            return false;
        }

        VillagerIllnessData d = villager.getAttached(OutbreakAttachments.ILLNESS);
        // Healthy, incubating and recovered villagers all get the SAME message, so using an item
        // cannot reveal an incubating infection.
        if (d == null || d.state() == IllnessState.INCUBATING || d.state() == IllnessState.RECOVERED) {
            player.sendOverlayMessage(Component.translatable("message.outbreak.treat.no_symptoms"));
            return false;
        }
        if (d.state() == IllnessState.UNDER_TREATMENT) {
            player.sendOverlayMessage(Component.translatable("message.outbreak.treat.already_treated"));
            return false;
        }
        if (d.state() == IllnessState.RECOVERING) {
            player.sendOverlayMessage(Component.translatable("message.outbreak.treat.recovering"));
            return false;
        }

        // SYMPTOMATIC from here on.
        IllnessDefinition def = IllnessRegistry.get(d.illnessId());
        if (def == null) {
            player.sendOverlayMessage(Component.translatable("message.outbreak.treat.unknown"));
            return false;
        }
        if (!def.treatment().equals(item.treatment())) {
            player.sendOverlayMessage(Component.translatable("message.outbreak.treat.wrong"));
            return false;
        }

        if (!IllnessManager.beginTreatment(villager, item.treatment(), level.getGameTime())) return false;
        if (!player.isCreative()) stack.shrink(1);
        SymptomManager.treatmentStarted(villager, level);
        player.sendOverlayMessage(Component.translatable("message.outbreak.treat.started"));
        return true;
    }
}