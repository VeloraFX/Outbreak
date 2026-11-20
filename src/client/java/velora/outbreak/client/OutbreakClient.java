package velora.outbreak.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import velora.outbreak.OutbreakContent;
import velora.outbreak.net.OutbreakNetworking.CloseScreen;
import velora.outbreak.net.OutbreakNetworking.DiagnosisResult;
import velora.outbreak.net.OutbreakNetworking.JournalData;
import velora.outbreak.net.OutbreakNetworking.Observation;
import velora.outbreak.net.OutbreakNetworking.OpenMicroscope;

public class OutbreakClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		BlockEntityRenderers.register(OutbreakContent.MICROSCOPE_BE, MicroscopeRenderer::new);
		HudConfig.load();
		OutbreakHud.init();

		ClientPlayNetworking.registerGlobalReceiver(OpenMicroscope.TYPE,
				(payload, ctx) -> ctx.client().setScreen(new MicroscopeScreen(payload)));
		ClientPlayNetworking.registerGlobalReceiver(Observation.TYPE, (payload, ctx) -> {
			if (ctx.client().screen instanceof MicroscopeScreen s) s.onObservation(payload);
		});
		ClientPlayNetworking.registerGlobalReceiver(DiagnosisResult.TYPE, (payload, ctx) -> {
			if (ctx.client().screen instanceof MicroscopeScreen s) s.onResult(payload);
		});
		ClientPlayNetworking.registerGlobalReceiver(CloseScreen.TYPE, (payload, ctx) -> {
			if (ctx.client().screen instanceof MicroscopeScreen s) s.onServerClose();
		});

		// The journal opens from the item (no screen open) or from the microscope's Journal button (the
		// microscope becomes the parent and comes back on Back). Any other open screen is left alone.
		ClientPlayNetworking.registerGlobalReceiver(JournalData.TYPE, (payload, ctx) -> {
			Screen current = ctx.client().screen;
			Screen parent;
			if (current == null) parent = null;
			else if (current instanceof MicroscopeScreen) parent = current;
			else if (current instanceof MedicalJournalScreen j) parent = j.parent();
			else return;
			ctx.client().setScreen(new MedicalJournalScreen(payload.lines(), parent));
		});
	}
}