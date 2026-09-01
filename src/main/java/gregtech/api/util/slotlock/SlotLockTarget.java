package gregtech.api.util.slotlock;

import net.minecraft.item.ItemStack;

/**
 * A group of lockable slots of one inventory, used as target for recipe locking.
 *
 * @param state     the lock state of the inventory
 * @param inventory the inventory contents
 * @param slots     the slot indices that may be locked
 */
public record SlotLockTarget(SlotLockState state, ItemStack[] inventory, int[] slots) {}
