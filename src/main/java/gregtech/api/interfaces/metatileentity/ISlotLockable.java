package gregtech.api.interfaces.metatileentity;

import gregtech.api.util.slotlock.SlotLockState;
import gregtech.api.util.slotlock.SlotLockTarget;

/**
 * Implemented by MetaTileEntities whose item slots can be locked to specific items, reserved by the machine while
 * processing and limited in capacity. See {@link SlotLockState} for the semantics.
 * <p>
 * Implementations are expected to be {@link IMetaTileEntity}s and must consult {@link #getSlotLockState()} in
 * {@code allowPutStack}, {@code isItemValidForSlot} and {@code getSlotLimit}.
 */
public interface ISlotLockable extends IRecipeSlotLockTarget {

    SlotLockState getSlotLockState();

    /**
     * @return whether slot locking is actually usable for this machine, i.e. its GUI shows the lock state. Machines
     *         with custom GUIs that do not render locks should return false so that no invisible locks can be set.
     */
    default boolean supportsSlotLocking() {
        return true;
    }

    /**
     * @return the indices of the input slots that can be locked. Used by recipe locking from NEI.
     */
    int[] getLockableInputSlots();

    /**
     * @return the indices of the output slots that can be locked. Used by recipe locking from NEI.
     */
    int[] getLockableOutputSlots();

    /**
     * @return all slot indices that can be locked by the player.
     */
    default int[] getLockableSlots() {
        int[] inputs = getLockableInputSlots();
        int[] outputs = getLockableOutputSlots();
        int[] result = new int[inputs.length + outputs.length];
        System.arraycopy(inputs, 0, result, 0, inputs.length);
        System.arraycopy(outputs, 0, result, inputs.length, outputs.length);
        return result;
    }

    default boolean isSlotLockable(int slot) {
        for (int i : getLockableInputSlots()) if (i == slot) return true;
        for (int i : getLockableOutputSlots()) if (i == slot) return true;
        return false;
    }

    /**
     * @return the input slots as recipe lock target.
     */
    SlotLockTarget getInputLockTarget();

    /**
     * @return the output slots as recipe lock target.
     */
    SlotLockTarget getOutputLockTarget();

    /**
     * Called after the lock state has been modified. Server side only.
     */
    void onSlotLocksChanged();
}
