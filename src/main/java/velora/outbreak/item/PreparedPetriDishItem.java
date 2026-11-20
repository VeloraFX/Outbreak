package velora.outbreak.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import velora.outbreak.OutbreakContent;
import velora.outbreak.diagnosis.SampleData;

import java.util.function.Consumer;

public class PreparedPetriDishItem extends Item {
    public PreparedPetriDishItem(Item.Properties properties) {
        super(properties.stacksTo(1));
    }

    /** Shows where the sample came from. It never shows a diagnosis. */
    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display,
                                Consumer<Component> out, TooltipFlag flag) {
        SampleData d = stack.get(OutbreakContent.SAMPLE);
        if (d == null) {
            out.accept(Component.translatable("tooltip.outbreak.sample.empty").withStyle(ChatFormatting.GRAY));
            return;
        }
        out.accept(Component.translatable("tooltip.outbreak.sample.source", d.shortSource()).withStyle(ChatFormatting.GRAY));
        out.accept(Component.translatable("tooltip.outbreak.sample.collected", d.collectedTick() / 24000L).withStyle(ChatFormatting.GRAY));
        out.accept(Component.translatable("tooltip.outbreak.sample.id", d.shortId()).withStyle(ChatFormatting.DARK_GRAY));
        if (!d.viewable()) {
            out.accept(Component.translatable("tooltip.outbreak.sample.unusable").withStyle(ChatFormatting.RED));
        }
    }
}