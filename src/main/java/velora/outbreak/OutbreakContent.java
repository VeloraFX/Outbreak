package velora.outbreak;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import velora.outbreak.block.MicroscopeBlock;
import velora.outbreak.block.MicroscopeBlockEntity;
import velora.outbreak.diagnosis.DiagnosisService;
import velora.outbreak.diagnosis.SampleData;
import velora.outbreak.illness.IllnessDefinition;
import velora.outbreak.examination.ExaminationService;
import velora.outbreak.examination.ExaminationToolItem;
import velora.outbreak.item.MedicalJournalItem;
import velora.outbreak.item.PreparedPetriDishItem;
import velora.outbreak.treatment.TreatmentItem;
import velora.outbreak.treatment.TreatmentService;

import java.util.function.Function;

/** All registrations (Phase 3: + treatment items; 5c: + examination tool, medical journal). Field order matters: block before block entity type. */
public final class OutbreakContent {
    private OutbreakContent() {}

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(OutbreakMod.MOD_ID, path);
    }

    public static final DataComponentType<SampleData> SAMPLE = Registry.register(
            BuiltInRegistries.DATA_COMPONENT_TYPE, id("sample"),
            DataComponentType.<SampleData>builder()
                    .persistent(SampleData.CODEC)
                    .networkSynchronized(SampleData.STREAM_CODEC)
                    .build());

    public static final Item STERILE_SWAB = registerItem("sterile_swab", p -> new Item(p.stacksTo(16)));
    public static final Item EMPTY_PETRI_DISH = registerItem("empty_petri_dish", p -> new Item(p.stacksTo(16)));
    public static final Item PREPARED_PETRI_DISH = registerItem("prepared_petri_dish", PreparedPetriDishItem::new);

    public static final Item SUPPORTIVE_TONIC = registerItem("supportive_tonic",
            p -> new TreatmentItem(p, IllnessDefinition.TREATMENT_SUPPORTIVE));
    public static final Item ANTIHISTAMINE = registerItem("antihistamine",
            p -> new TreatmentItem(p, IllnessDefinition.TREATMENT_ANTIHISTAMINE));

    public static final Item ORAL_REHYDRATION_SALTS = registerItem("oral_rehydration_salts",
            p -> new TreatmentItem(p, IllnessDefinition.TREATMENT_REHYDRATION));
    static { BuiltInRegistries.ITEM.addAlias(id("oral_rehydration_salts.json"), id("oral_rehydration_salts")); }

    public static final Item EXAMINATION_TOOL = registerItem("examination_tool", ExaminationToolItem::new);
    public static final Item MEDICAL_JOURNAL = registerItem("medical_journal", MedicalJournalItem::new);

    public static final Block MICROSCOPE = registerMicroscope();
    public static final Item MICROSCOPE_ITEM = registerMicroscopeItem();

    public static final BlockEntityType<MicroscopeBlockEntity> MICROSCOPE_BE = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE, id("microscope"),
            FabricBlockEntityTypeBuilder.create(MicroscopeBlockEntity::new, MICROSCOPE).build());

    private static Item registerItem(String name, Function<Item.Properties, Item> factory) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id(name));
        return Registry.register(BuiltInRegistries.ITEM, key, factory.apply(new Item.Properties().setId(key)));
    }

    private static Block registerMicroscope() {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id("microscope"));
        return Registry.register(BuiltInRegistries.BLOCK, key, new MicroscopeBlock(
                BlockBehaviour.Properties.of()
                        .mapColor(MapColor.METAL)
                        .strength(2.0f)
                        .sound(SoundType.METAL)
                        .noOcclusion()
                        .setId(key)));
    }

    private static Item registerMicroscopeItem() {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id("microscope"));
        return Registry.register(BuiltInRegistries.ITEM, key, new BlockItem(MICROSCOPE,
                new Item.Properties().useBlockDescriptionPrefix().setId(key)));
    }

    /** The item for a treatment class, or null if there is none. */
    public static Item treatmentItem(String treatmentClass) {
        return switch (treatmentClass) {
            case IllnessDefinition.TREATMENT_SUPPORTIVE -> SUPPORTIVE_TONIC;
            case IllnessDefinition.TREATMENT_ANTIHISTAMINE -> ANTIHISTAMINE;
            case IllnessDefinition.TREATMENT_REHYDRATION -> ORAL_REHYDRATION_SALTS;
            default -> null;
        };
    }

    public static ItemStack preparedDish(SampleData data) {
        ItemStack stack = new ItemStack(PREPARED_PETRI_DISH);
        stack.set(SAMPLE, data);
        return stack;
    }

    /** Forces class loading (registrations) and wires events. */
    public static void init() {
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(out -> {
            out.accept(STERILE_SWAB);
            out.accept(EMPTY_PETRI_DISH);
            out.accept(SUPPORTIVE_TONIC);
            out.accept(ANTIHISTAMINE);
            out.accept(ORAL_REHYDRATION_SALTS);
            out.accept(EXAMINATION_TOOL);
            out.accept(MEDICAL_JOURNAL);
        });
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(out -> out.accept(MICROSCOPE_ITEM));

        // Swab on villager. UseEntityCallback fires before the villager's own interaction (trade screen).
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (!(entity instanceof Villager villager) || player.isSpectator()) return InteractionResult.PASS;
            ItemStack held = player.getItemInHand(hand);
            if (held.getItem() != STERILE_SWAB) return InteractionResult.PASS;
            if (DiagnosisService.findStack(player, EMPTY_PETRI_DISH) == null) {
                if (!level.isClientSide()) player.sendOverlayMessage(Component.translatable("message.outbreak.swab.no_dish"));
                return InteractionResult.FAIL;
            }
            if (level.isClientSide()) return InteractionResult.SUCCESS;
            if (player instanceof ServerPlayer sp && level instanceof ServerLevel sl) {
                DiagnosisService.collect(sp, sl, villager, held);
            }
            return InteractionResult.SUCCESS;
        });

        // Treatment items on a villager (main hand; vanilla's trade screen would otherwise win).
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                TreatmentService.onUse(player, level, hand, entity));

        // Examination tool on a villager (main hand, same reason).
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                ExaminationService.onUse(player, level, hand, entity));
    }
}