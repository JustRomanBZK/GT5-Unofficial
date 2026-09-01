package gregtech.api.interfaces.metatileentity;

import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import org.jetbrains.annotations.Nullable;

import gregtech.api.recipe.RecipeMap;

/**
 * A machine whose slots (or the slots of its hatches) can be locked to the items and fluids of a recipe shown in NEI.
 * Single block machines and hatches implement this through {@link ISlotLockable}, multiblock controllers implement it
 * directly and forward the request to their hatches.
 */
public interface IRecipeSlotLockTarget {

    /**
     * @return whether NEI may lock the slots of this machine to recipes of the given recipe map.
     */
    boolean acceptsRecipeLock(@Nullable RecipeMap<?> recipeMap);

    /**
     * Locks the slots to the given recipe items and fluids. Server side only.
     *
     * @param inputs       recipe item inputs, stack size = required amount
     * @param outputs      recipe item outputs, stack size = produced amount
     * @param fluidInputs  recipe fluid inputs
     * @param fluidOutputs recipe fluid outputs
     */
    void lockSlotsToRecipe(List<ItemStack> inputs, List<ItemStack> outputs, List<FluidStack> fluidInputs,
        List<FluidStack> fluidOutputs);
}
