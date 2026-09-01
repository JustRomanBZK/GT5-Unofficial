package gregtech.api.net;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.INetHandler;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import com.google.common.io.ByteArrayDataInput;

import cpw.mods.fml.common.network.ByteBufUtils;
import gregtech.api.interfaces.metatileentity.IRecipeSlotLockTarget;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.BaseTileEntity;
import gregtech.api.util.GTByteBuffer;
import gregtech.api.util.GTUtility;
import io.netty.buffer.ByteBuf;

/**
 * Client -> Server: lock the slots of an {@link IRecipeSlotLockTarget} machine to the items and fluids of a recipe
 * shown in NEI.
 */
public class GTPacketLockSlotsToRecipe extends GTPacket {

    private static final double MAX_DISTANCE_SQ = 64;

    private int mX;
    private short mY;
    private int mZ;
    private List<ItemStack> inputs;
    private List<ItemStack> outputs;
    private List<FluidStack> fluidInputs;
    private List<FluidStack> fluidOutputs;

    private EntityPlayerMP player;

    public GTPacketLockSlotsToRecipe() {
        super();
    }

    public GTPacketLockSlotsToRecipe(IGregTechTileEntity tile, List<ItemStack> inputs, List<ItemStack> outputs,
        List<FluidStack> fluidInputs, List<FluidStack> fluidOutputs) {
        this(tile.getXCoord(), tile.getYCoord(), tile.getZCoord(), inputs, outputs, fluidInputs, fluidOutputs);
    }

    public GTPacketLockSlotsToRecipe(int x, short y, int z, List<ItemStack> inputs, List<ItemStack> outputs,
        List<FluidStack> fluidInputs, List<FluidStack> fluidOutputs) {
        super();
        this.mX = x;
        this.mY = y;
        this.mZ = z;
        this.inputs = inputs;
        this.outputs = outputs;
        this.fluidInputs = fluidInputs;
        this.fluidOutputs = fluidOutputs;
    }

    @Override
    public byte getPacketID() {
        return GTPacketTypes.LOCK_SLOTS_TO_RECIPE.id;
    }

    @Override
    public void encode(ByteBuf aOut) {
        aOut.writeInt(mX);
        aOut.writeShort(mY);
        aOut.writeInt(mZ);
        writeStacks(aOut, inputs);
        writeStacks(aOut, outputs);
        writeFluids(aOut, fluidInputs);
        writeFluids(aOut, fluidOutputs);
    }

    private static void writeStacks(ByteBuf aOut, List<ItemStack> stacks) {
        ByteBufUtils.writeVarInt(aOut, stacks.size(), 5);
        for (ItemStack stack : stacks) {
            // Amount is written separately, the NBT encoding only supports a byte sized stack size
            ByteBufUtils.writeVarInt(aOut, Math.max(1, stack.stackSize), 5);
            ByteBufUtils.writeTag(
                aOut,
                GTUtility.copyAmount(1, stack)
                    .writeToNBT(new NBTTagCompound()));
        }
    }

    private static List<ItemStack> readStacks(ByteArrayDataInput aData) {
        int count = readVarInt(aData, 5);
        List<ItemStack> stacks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int amount = readVarInt(aData, 5);
            NBTTagCompound tag = GTByteBuffer.readCompoundTagFromGreggyByteBuf(aData);
            ItemStack stack = tag == null ? null : ItemStack.loadItemStackFromNBT(tag);
            if (stack == null) continue;
            stack.stackSize = amount;
            stacks.add(stack);
        }
        return stacks;
    }

    private static void writeFluids(ByteBuf aOut, List<FluidStack> fluids) {
        ByteBufUtils.writeVarInt(aOut, fluids.size(), 5);
        for (FluidStack fluid : fluids) {
            ByteBufUtils.writeUTF8String(
                aOut,
                fluid.getFluid()
                    .getName());
            ByteBufUtils.writeVarInt(aOut, Math.max(1, fluid.amount), 5);
        }
    }

    private static List<FluidStack> readFluids(ByteArrayDataInput aData) {
        int count = readVarInt(aData, 5);
        List<FluidStack> fluids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String name = readUTF8String(aData);
            int amount = readVarInt(aData, 5);
            Fluid fluid = FluidRegistry.getFluid(name);
            if (fluid == null) continue;
            fluids.add(new FluidStack(fluid, amount));
        }
        return fluids;
    }

    @Override
    public GTPacket decode(ByteArrayDataInput aData) {
        int x = aData.readInt();
        short y = aData.readShort();
        int z = aData.readInt();
        List<ItemStack> inputs = readStacks(aData);
        List<ItemStack> outputs = readStacks(aData);
        List<FluidStack> fluidInputs = readFluids(aData);
        List<FluidStack> fluidOutputs = readFluids(aData);
        return new GTPacketLockSlotsToRecipe(x, y, z, inputs, outputs, fluidInputs, fluidOutputs);
    }

    @Override
    public void setINetHandler(INetHandler aHandler) {
        if (aHandler instanceof NetHandlerPlayServer serverHandler) {
            player = serverHandler.playerEntity;
        } else {
            player = null;
        }
    }

    @Override
    public void process(IBlockAccess aWorld) {
        if (player == null || player.worldObj == null) return;
        if (player.getDistanceSq(mX + 0.5, mY + 0.5, mZ + 0.5) > MAX_DISTANCE_SQ) return;

        final TileEntity tile = player.worldObj.getTileEntity(mX, mY, mZ);
        if (!(tile instanceof BaseTileEntity baseTile) || baseTile.isDead()) return;
        if (!(tile instanceof IGregTechTileEntity gtTile)) return;
        if (!(gtTile.getMetaTileEntity() instanceof IRecipeSlotLockTarget target)) return;

        target.lockSlotsToRecipe(inputs, outputs, fluidInputs, fluidOutputs);
    }
}
