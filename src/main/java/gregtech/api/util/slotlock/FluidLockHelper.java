package gregtech.api.util.slotlock;

import java.util.ArrayList;
import java.util.List;

import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;

import gregtech.api.interfaces.metatileentity.IFluidLockableMui2;
import gregtech.api.metatileentity.implementations.MTEBasicTank;
import gregtech.api.metatileentity.implementations.MTEHatchOutput;

/**
 * Locks fluid hatches of a multiblock to the fluids of a recipe, mirroring {@link SlotLockState#lockToRecipe} for
 * fluids: enough hatches are locked to hold the required amount of every fluid, hatches already containing the fluid
 * are preferred and existing locks are never overridden.
 */
public final class FluidLockHelper {

    private FluidLockHelper() {}

    /**
     * @param fluids        the recipe fluids, amount = required amount
     * @param hatches       the candidate hatches, only {@link IFluidLockableMui2} tanks are considered
     * @param lockRemaining whether empty and unlocked hatches should be locked to nothing afterwards. Only sensible
     *                      for hatches where a lock without fluid rejects every fluid (input hatches).
     */
    public static void lockHatchesToFluids(List<FluidStack> fluids, Iterable<? extends MTEBasicTank> hatches,
        boolean lockRemaining) {
        List<MTEBasicTank> lockables = new ArrayList<>();
        for (MTEBasicTank hatch : hatches) {
            if (hatch instanceof IFluidLockableMui2) lockables.add(hatch);
        }
        if (lockables.isEmpty()) return;

        for (FluidStack fluid : fluids) {
            if (fluid == null || fluid.getFluid() == null) continue;
            lockForFluid(fluid, lockables);
        }
        if (lockRemaining && !fluids.isEmpty()) {
            for (MTEBasicTank tank : lockables) {
                IFluidLockableMui2 lockable = (IFluidLockableMui2) tank;
                if (!lockable.isFluidLocked() && tank.getFillableStack() == null) {
                    lockable.setLockedFluid(null);
                    lockable.lockFluid(true);
                }
            }
        }
    }

    private static void lockForFluid(FluidStack fluid, List<MTEBasicTank> lockables) {
        Fluid target = fluid.getFluid();
        int required = Math.max(1, fluid.amount);

        // Amount already covered by existing locks
        for (MTEBasicTank tank : lockables) {
            IFluidLockableMui2 lockable = (IFluidLockableMui2) tank;
            if (lockable.isFluidLocked() && lockable.getLockedFluid() == target) {
                required -= tank.getCapacity();
                if (required <= 0) return;
            }
        }

        // First pass: hatches already containing the fluid. Second pass: empty hatches.
        for (int pass = 0; pass < 2 && required > 0; pass++) {
            for (MTEBasicTank tank : lockables) {
                if (required <= 0) break;
                IFluidLockableMui2 lockable = (IFluidLockableMui2) tank;
                if (lockable.isFluidLocked() && lockable.getLockedFluid() != null) continue;
                FluidStack stored = tank.getFillableStack();
                boolean applicable = pass == 0 ? stored != null && stored.getFluid() == target : stored == null;
                if (!applicable || !lockable.acceptsFluidLock(target)) continue;
                if (tank instanceof MTEHatchOutput outputHatch && !outputHatch.isFluidLocked()) {
                    // "items and specific fluid" keeps the item output behaviour of the hatch intact
                    outputHatch.setMode((byte) 8);
                }
                lockable.setLockedFluid(target);
                lockable.lockFluid(true);
                required -= tank.getCapacity();
            }
        }
    }
}
