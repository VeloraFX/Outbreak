package velora.outbreak.treatment;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.function.Consumer;

public class TreatmentItem extends Item {
    private final String treatment;

    public TreatmentItem(Item.Properties properties, String treatment) {
        super(properties.stacksTo(16));
        this.treatment = treatment;
    }

    public String treatment() { return treatment; }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display,
                                Consumer<Component> out, TooltipFlag flag) {
        out.accept(Component.translatable("tooltip.outbreak.treatment." + treatment).withStyle(ChatFormatting.GRAY));
        out.accept(Component.translatable("tooltip.outbreak.treatment.use").withStyle(ChatFormatting.DARK_GRAY));
    }
}