package gregtech.nei;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.fluids.FluidStack;

import org.jetbrains.annotations.Nullable;

import com.cleanroommc.modularui.api.IMuiScreen;
import com.cleanroommc.modularui.factory.PosGuiData;
import com.cleanroommc.modularui.screen.ModularContainer;

import codechicken.nei.PositionedStack;
import codechicken.nei.api.IOverlayHandler;
import codechicken.nei.recipe.GuiOverlayButton;
import codechicken.nei.recipe.IRecipeHandler;
import codechicken.nei.recipe.TemplateRecipeHandler;
import gregtech.api.enums.GTValues;
import gregtech.api.enums.ItemList;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.metatileentity.IRecipeSlotLockTarget;
import gregtech.api.interfaces.metatileentity.ISlotLockable;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.net.GTPacketLockSlotsToRecipe;
import gregtech.api.recipe.RecipeMap;
import gregtech.api.util.GTUtility;
import gregtech.common.items.ItemIntegratedCircuit;
import gregtech.common.modularui2.sync.SlotLockSyncHandler;

/**
 * NEI overlay handler for machines implementing {@link IRecipeSlotLockTarget}: the "overlay recipe" button of a
 * GregTech recipe locks the input and output slots of the opened machine (or of the hatches of the opened multiblock)
 * to the recipe items and fluids, like Modern Industrialization does with EMI.
 */
public class SlotLockOverlayHandler implements IOverlayHandler {

    public static final SlotLockOverlayHandler INSTANCE = new SlotLockOverlayHandler();

    private SlotLockOverlayHandler() {}

    /**
     * @return the lockable machine whose GUI is opened, or null. For single block machines and hatches the GUI must
     *         show the lock state, otherwise invisible locks could be created.
     */
    @Nullable
    public static IRecipeSlotLockTarget getLockTarget(@Nullable GuiContainer gui) {
        if (!(gui instanceof IMuiScreen) || !(gui.inventorySlots instanceof ModularContainer container)) return null;
        if (!(container.getGuiData() instanceof PosGuiData data)) return null;
        TileEntity tile = data.getTileEntity();
        if (!(tile instanceof IGregTechTileEntity gtTile)) return null;
        IMetaTileEntity mte = gtTile.getMetaTileEntity();
        if (!(mte instanceof IRecipeSlotLockTarget target)) return null;
        if (target instanceof ISlotLockable lockable) {
            if (!lockable.supportsSlotLocking()) return null;
            if (container.getSyncManager()
                .getMainPSM()
                .findSyncHandlerNullable(SlotLockSyncHandler.KEY, 0) == null) return null;
        }
        return target;
    }

    public static boolean canHandle(@Nullable GuiContainer gui, RecipeMap<?> recipeMap) {
        IRecipeSlotLockTarget target = getLockTarget(gui);
        return target != null && target.acceptsRecipeLock(recipeMap);
    }

    @Override
    public void overlayRecipe(GuiContainer gui, IRecipeHandler handler, int recipeIndex, boolean maxTransfer) {
        lockSlots(gui, handler, recipeIndex);
    }

    @Override
    public int transferRecipe(GuiContainer gui, IRecipeHandler handler, int recipeIndex, int multiplier) {
        lockSlots(gui, handler, recipeIndex);
        return 0;
    }

    @Override
    public boolean canFillCraftingGrid(GuiContainer gui, IRecipeHandler handler, int recipeIndex) {
        return false;
    }

    @Override
    public boolean requireShiftForOverlayRecipe() {
        return false;
    }

    @Override
    public List<GuiOverlayButton.ItemOverlayState> presenceOverlay(GuiContainer gui, IRecipeHandler handler,
        int recipeIndex) {
        return Collections.emptyList();
    }

    private static void lockSlots(GuiContainer gui, IRecipeHandler handler, int recipeIndex) {
        IRecipeSlotLockTarget target = getLockTarget(gui);
        if (!(target instanceof IMetaTileEntity mte)) return;
        if (!(handler instanceof GTNEIDefaultHandler gtHandler)) return;
        if (recipeIndex < 0 || recipeIndex >= gtHandler.arecipes.size()) return;
        TemplateRecipeHandler.CachedRecipe cached = gtHandler.arecipes.get(recipeIndex);
        if (!(cached instanceof GTNEIDefaultHandler.CachedDefaultRecipe recipe)) return;
        if (!target.acceptsRecipeLock(gtHandler.getRecipeMap())) return;

        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        List<ItemStack> inputs = new ArrayList<>();
        List<FluidStack> fluidInputs = new ArrayList<>();
        collectStacks(recipe.mInputs, player, true, inputs, fluidInputs);
        List<ItemStack> outputs = new ArrayList<>();
        List<FluidStack> fluidOutputs = new ArrayList<>();
        collectStacks(recipe.mOutputs, player, false, outputs, fluidOutputs);
        if (inputs.isEmpty() && outputs.isEmpty() && fluidInputs.isEmpty() && fluidOutputs.isEmpty()) return;

        IGregTechTileEntity tile = mte.getBaseMetaTileEntity();
        if (tile == null) return;
        GTValues.NW.sendToServer(new GTPacketLockSlotsToRecipe(tile, inputs, outputs, fluidInputs, fluidOutputs));
    }

    /**
     * Converts the NEI representation of the recipe items into concrete stacks with the required amount. For inputs
     * with several alternatives (ore dictionary), the first alternative found in the player inventory is preferred.
     * Fluid display stacks are converted to fluids.
     */
    private static void collectStacks(List<PositionedStack> stacks, EntityPlayer player, boolean inputs,
        List<ItemStack> items, List<FluidStack> fluids) {
        for (PositionedStack positioned : stacks) {
            if (positioned == null || positioned.item == null) continue;
            int amount = positioned.item.stackSize;
            if (positioned instanceof GTNEIDefaultHandler.FixedPositionedStack fixed) {
                // Special slot items (molds, lenses, ...) are inputs that are not part of the regular input slots
                if (inputs && !fixed.isInput()) continue;
                amount = fixed.realStackSize;
            }
            if (ItemList.Display_Fluid.isStackEqual(positioned.item, true, true)) {
                FluidStack fluid = GTUtility.getFluidFromDisplayStack(positioned.item);
                if (fluid != null && fluid.getFluid() != null) fluids.add(fluid);
                continue;
            }
            if (positioned.item.getItem() instanceof ItemIntegratedCircuit) continue;

            ItemStack chosen = findInPlayerInventory(positioned, player);
            if (chosen == null) chosen = positioned.item;
            ItemStack stack = GTUtility.copyAmount(Math.max(1, amount), chosen);
            if (stack == null) continue;
            items.add(stack);
        }
    }

    @Nullable
    private static ItemStack findInPlayerInventory(PositionedStack positioned, EntityPlayer player) {
        if (player == null) return null;
        for (ItemStack stack : player.inventory.mainInventory) {
            if (stack != null && positioned.contains(stack)) {
                return stack;
            }
        }
        return null;
    }
}
