package velora.outbreak.illness;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;

/**
 * Per-vector hard rules for passing an illness to a specific villager. Used by IllnessManager.transmit,
 * so they hold no matter who calls it.
 *
 * AIRBORNE: nothing extra (range and chance are handled by SpreadManager).
 * FOODBORNE: the receiving villager must be carrying food (vanilla villagers pass food to each other
 * with the TradeWithVillager behaviour). One scan of at most the 8 inventory slots, only done after
 * the chance roll succeeded.
 */
public final class TransmissionRules {
    private TransmissionRules() {}

    public static boolean canTransmitTo(Villager target, IllnessDefinition def) {
        if (!def.canSpread()) return false;
        return def.vector() != TransmissionVector.FOODBORNE || holdsFood(target);
    }

    /** Any item with the vanilla food component (bread, carrots, potatoes, beetroot, ...). */
    public static boolean holdsFood(Villager v) {
        SimpleContainer inv = v.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.has(DataComponents.FOOD)) return true;
        }
        return false;
    }
}