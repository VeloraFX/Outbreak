package velora.outbreak.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import velora.outbreak.journal.JournalService;

import java.util.function.Consumer;

/** Use in the air: the server sends the player's own journal and the client opens the screen. */
public class MedicalJournalItem extends Item {
    public MedicalJournalItem(Item.Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide() && player instanceof ServerPlayer sp) JournalService.send(sp);
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display,
                                Consumer<Component> out, TooltipFlag flag) {
        out.accept(Component.translatable("tooltip.outbreak.medical_journal").withStyle(ChatFormatting.GRAY));
        out.accept(Component.translatable("tooltip.outbreak.medical_journal.use").withStyle(ChatFormatting.DARK_GRAY));
    }
}