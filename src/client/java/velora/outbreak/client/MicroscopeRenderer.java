package velora.outbreak.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import velora.outbreak.OutbreakContent;
import velora.outbreak.block.MicroscopeBlockEntity;
import velora.outbreak.diagnosis.SampleData;

/** Draws the Prepared Petri Dish lying on the microscope stage. */
public class MicroscopeRenderer implements BlockEntityRenderer<MicroscopeBlockEntity, MicroscopeRenderer.State> {

    public static class State extends BlockEntityRenderState {
        public final ItemStackRenderState dish = new ItemStackRenderState();
        public boolean hasDish;
    }

    private final ItemModelResolver items;

    public MicroscopeRenderer(BlockEntityRendererProvider.Context context) {
        this.items = context.itemModelResolver();
    }

    @Override
    public State createRenderState() { return new State(); }

    @Override
    public void extractRenderState(MicroscopeBlockEntity be, State state, float partialTick, Vec3 cameraPos,
                                   ModelFeatureRenderer.CrumblingOverlay crumbling) {
        BlockEntityRenderState.extractBase(be, state, crumbling);
        state.dish.clear();
        SampleData d = be.dish();
        state.hasDish = d != null && be.getLevel() != null;
        if (state.hasDish) {
            items.updateForTopItem(state.dish, OutbreakContent.preparedDish(d), ItemDisplayContext.FIXED,
                    be.getLevel(), null, (int) be.getBlockPos().asLong());
        }
    }

    @Override
    public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        if (!state.hasDish || state.dish.isEmpty()) return;
        pose.pushPose();
        // Stage top is at y = 6/16 and the stage is centred on the block, so no facing rotation is needed.
        pose.translate(0.5, 0.40, 0.5);
        pose.mulPose(Axis.XP.rotationDegrees(90f)); // lie flat
        pose.scale(0.3f, 0.3f, 0.3f);
        state.dish.submit(pose, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
        pose.popPose();
    }
}