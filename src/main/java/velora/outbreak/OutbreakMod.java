package velora.outbreak;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.server.packs.PackType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import velora.outbreak.command.OutbreakCommands;
import velora.outbreak.config.OutbreakConfig;
import velora.outbreak.illness.*;
import velora.outbreak.net.OutbreakNetworking;

public class OutbreakMod implements ModInitializer {
    public static final String MOD_ID = "outbreak";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        OutbreakConfig.reload(); // never throws; falls back to defaults
        OutbreakAttachments.init();
        OutbreakContent.init();
        OutbreakNetworking.init();
        ResourceLoader.get(PackType.SERVER_DATA).registerReloadListener(IllnessLoader.ID, new IllnessLoader());
        IllnessTracker.init();
        SpreadManager.init();
        SymptomPause.init();
        OnsetManager.init();
        OutbreakCommands.register();
        ServerTickEvents.END_SERVER_TICK.register(IllnessTracker::tick);
        ServerTickEvents.END_SERVER_TICK.register(SpreadManager::tick);
        ServerTickEvents.END_SERVER_TICK.register(SymptomPause::tick);
        ServerTickEvents.END_SERVER_TICK.register(OnsetManager::tick);

        LOGGER.info("Outbreak loaded");
    }
}