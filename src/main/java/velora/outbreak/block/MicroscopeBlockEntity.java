package velora.outbreak.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.Containers;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import velora.outbreak.OutbreakContent;
import velora.outbreak.diagnosis.SampleData;

/** Holds the dish on the stage (sample metadata only; the hidden truth stays in the server store). */
public class MicroscopeBlockEntity extends BlockEntity {
    private SampleData dish; // null = empty stage

    public MicroscopeBlockEntity(BlockPos pos, BlockState state) {
        super(OutbreakContent.MICROSCOPE_BE, pos, state);
    }

    public SampleData dish() { return dish; }

    public void setDish(SampleData data) {
        this.dish = data;
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
        }
    }

    public SampleData takeDish() {
        SampleData d = dish;
        setDish(null);
        return d;
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        if (dish != null) out.store("dish", SampleData.CODEC, dish);
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        this.dish = in.read("dish", SampleData.CODEC).orElse(null);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** Drops the dish when the block is broken. */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (dish != null && level != null && !level.isClientSide()) {
            Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    OutbreakContent.preparedDish(dish));
            dish = null;
        }
    }
}