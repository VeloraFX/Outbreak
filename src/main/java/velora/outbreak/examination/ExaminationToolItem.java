package velora.outbreak.examination;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.function.Consumer;

/** Marker item with a tooltip. The behaviour lives in ExaminationService (UseEntityCallback). */
public class ExaminationToolItem extends Item {
    public ExaminationToolItem(Item.Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display,
                                Consumer<Component> out, TooltipFlag flag) {
        out.accept(Component.translatable("tooltip.outbreak.examination_tool").withStyle(ChatFormatting.GRAY));
        out.accept(Component.translatable("tooltip.outbreak.examination_tool.use").withStyle(ChatFormatting.DARK_GRAY));
    }
}