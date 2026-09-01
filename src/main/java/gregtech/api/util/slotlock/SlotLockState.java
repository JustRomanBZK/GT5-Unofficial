package gregtech.api.util.slotlock;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.common.util.Constants;

import org.jetbrains.annotations.Nullable;

import com.cleanroommc.modularui.network.NetworkUtils;

import gregtech.api.util.GTUtility;

/**
 * Per-slot lock configuration of a machine inventory. This is a port of the slot locking mechanics of Modern
 * Industrialization ({@code AbstractConfigurableStack}) to GregTech.
 * <p>
 * Every slot can be:
 * <ul>
 * <li>locked by the player to a specific item: only that item may be inserted (by players, pipes and the machine
 * itself). Locking an empty slot without an item locks it to <i>nothing</i>, i.e. the slot cannot be used at all.</li>
 * <li>locked by the machine: while a recipe is being processed the output slots are reserved for the recipe outputs so
 * that pipes cannot fill them with other items.</li>
 * <li>limited to a capacity between 0 and 64 items.</li>
 * </ul>
 * The state is stored on the {@link gregtech.api.interfaces.metatileentity.ISlotLockable MetaTileEntity} and
 * synchronized to the client by the GUI.
 */
public final class SlotLockState {

    public static final int MAX_CAPACITY = 64;
    private static final String NBT_KEY = "gtSlotLocks";

    private final int size;
    private final ItemStack[] lockedItems;
    private final boolean[] playerLocked;
    private final boolean[] machineLocked;
    private final byte[] capacity;

    public SlotLockState(int size) {
        this.size = Math.max(0, size);
        this.lockedItems = new ItemStack[this.size];
        this.playerLocked = new boolean[this.size];
        this.machineLocked = new boolean[this.size];
        this.capacity = new byte[this.size];
        Arrays.fill(this.capacity, (byte) MAX_CAPACITY);
    }

    public int size() {
        return size;
    }

    private boolean inRange(int slot) {
        return slot >= 0 && slot < size;
    }

    /**
     * Compares two stacks for locking purposes. NBT is ignored so that e.g. damaged tools still match.
     */
    public static boolean matches(@Nullable ItemStack a, @Nullable ItemStack b) {
        return a != null && b != null && GTUtility.areStacksEqual(a, b, true);
    }

    private static ItemStack copyOne(ItemStack stack) {
        return GTUtility.copyAmount(1, stack);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Queries
    // ---------------------------------------------------------------------------------------------------------------

    public boolean isPlayerLocked(int slot) {
        return inRange(slot) && playerLocked[slot];
    }

    public boolean isMachineLocked(int slot) {
        return inRange(slot) && machineLocked[slot];
    }

    public boolean isLocked(int slot) {
        return isPlayerLocked(slot) || isMachineLocked(slot);
    }

    /**
     * @return true if the slot is locked and nothing can be inserted into it.
     */
    public boolean isLockedToNothing(int slot) {
        return isLocked(slot) && lockedItems[slot] == null;
    }

    /**
     * @return the item the slot is locked to, or null if the slot is not locked or locked to nothing.
     */
    @Nullable
    public ItemStack getLockedItem(int slot) {
        return inRange(slot) ? lockedItems[slot] : null;
    }

    public boolean isLockedTo(int slot, @Nullable ItemStack stack) {
        return isLocked(slot) && matches(lockedItems[slot], stack);
    }

    public int getCapacity(int slot) {
        return inRange(slot) ? capacity[slot] : MAX_CAPACITY;
    }

    /**
     * @return the maximum amount of the given stack that fits into the slot.
     */
    public int getCapacityFor(int slot, @Nullable ItemStack stack) {
        return Math.min(getCapacity(slot), stack == null ? MAX_CAPACITY : stack.getMaxStackSize());
    }

    /**
     * @return whether the lock of the slot allows the given item to be inserted. Does not check the current content
     *         of the slot.
     */
    public boolean isItemAllowed(int slot, @Nullable ItemStack stack) {
        if (!isLocked(slot)) return true;
        return matches(lockedItems[slot], stack);
    }

    /**
     * @return whether the slot with the given current content is at (or above) its capacity.
     */
    public boolean isFull(int slot, @Nullable ItemStack current) {
        return current != null && current.stackSize >= getCapacityFor(slot, current);
    }

    public boolean hasAnyLock() {
        for (int i = 0; i < size; i++) {
            if (playerLocked[i] || machineLocked[i]) return true;
        }
        return false;
    }

    public boolean hasAnyLock(int[] slots) {
        for (int slot : slots) {
            if (isLocked(slot)) return true;
        }
        return false;
    }

    /**
     * @return true if at least one of the given slots is not locked by the player.
     */
    public boolean hasUnlockedSlot(int[] slots) {
        for (int slot : slots) {
            if (!isPlayerLocked(slot)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Player locks
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Toggles the player lock of a slot. If the player holds an item and the slot is empty or locked to nothing, the
     * slot gets locked to that item instead.
     *
     * @param current the current content of the slot
     * @param cursor  the item held by the player, if any
     */
    public void togglePlayerLock(int slot, @Nullable ItemStack current, @Nullable ItemStack cursor) {
        if (!inRange(slot)) return;
        if (playerLocked[slot] && lockedItems[slot] == null && cursor != null) {
            // Locked to nothing: the held item becomes the lock target
            lockedItems[slot] = copyOne(cursor);
        } else if (!playerLocked[slot] && current == null
            && cursor != null
            && (!machineLocked[slot] || matches(lockedItems[slot], cursor))) {
                // Empty unlocked slot: lock directly to the held item
                lockedItems[slot] = copyOne(cursor);
                playerLocked[slot] = true;
            } else {
                playerLocked[slot] = !playerLocked[slot];
            }
        updateLockedItem(slot, current);
    }

    public void setPlayerLocked(int slot, boolean lock, @Nullable ItemStack current) {
        if (!inRange(slot) || playerLocked[slot] == lock) return;
        playerLocked[slot] = lock;
        updateLockedItem(slot, current);
    }

    /**
     * Locks the slot to the given item (or to nothing if {@code target} is null).
     *
     * @param current the current content of the slot
     * @return false if the slot content or a machine lock conflicts with the requested lock
     */
    public boolean playerLock(int slot, @Nullable ItemStack current, @Nullable ItemStack target) {
        if (!inRange(slot)) return false;
        if (current != null && !matches(current, target)) return false;
        if (machineLocked[slot] && lockedItems[slot] != null && !matches(lockedItems[slot], target)) return false;
        lockedItems[slot] = target == null ? null : copyOne(target);
        playerLocked[slot] = true;
        return true;
    }

    /**
     * @return whether {@link #playerLock} would succeed.
     */
    public boolean canPlayerLock(int slot, @Nullable ItemStack current, @Nullable ItemStack target) {
        if (!inRange(slot)) return false;
        if (current != null && !matches(current, target)) return false;
        return !machineLocked[slot] || lockedItems[slot] == null || matches(lockedItems[slot], target);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Machine locks
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Reserves the slot for the given item. Never overrides an existing lock target.
     */
    public void enableMachineLock(int slot, @Nullable ItemStack item) {
        if (!inRange(slot) || item == null) return;
        machineLocked[slot] = true;
        if (lockedItems[slot] == null) {
            lockedItems[slot] = copyOne(item);
        }
    }

    public void disableMachineLock(int slot, @Nullable ItemStack current) {
        if (!inRange(slot) || !machineLocked[slot]) return;
        machineLocked[slot] = false;
        updateLockedItem(slot, current);
    }

    /**
     * Removes the player locks of the given slots. Machine locks and capacities are kept.
     */
    public void clearPlayerLocks(int[] slots, ItemStack[] inventory) {
        for (int slot : slots) {
            if (!inRange(slot) || !playerLocked[slot]) continue;
            playerLocked[slot] = false;
            updateLockedItem(slot, slot < inventory.length ? inventory[slot] : null);
        }
    }

    public void clearMachineLocks(int[] slots, ItemStack[] inventory) {
        for (int slot : slots) {
            disableMachineLock(slot, inRange(slot) && slot < inventory.length ? inventory[slot] : null);
        }
    }

    private void updateLockedItem(int slot, @Nullable ItemStack current) {
        if (!playerLocked[slot] && !machineLocked[slot]) {
            lockedItems[slot] = null;
        } else if (lockedItems[slot] == null && current != null) {
            lockedItems[slot] = copyOne(current);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Recipe locking (port of Modern Industrialization CrafterComponent#lockRecipe)
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Locks the given input and output slots so that they only accept the items of a recipe. Previous player locks are
     * removed first. Enough slots are locked to hold the required amount of every item (taking the slot capacities into
     * account). Finally, all remaining empty and unlocked slots are locked to nothing.
     *
     * @param inputs      the recipe inputs, stack size = required amount
     * @param outputs     the recipe outputs, stack size = produced amount
     * @param inputSlots  indices of the input slots
     * @param outputSlots indices of the output slots
     * @param inventory   the machine inventory
     */
    public void lockToRecipe(List<ItemStack> inputs, List<ItemStack> outputs, int[] inputSlots, int[] outputSlots,
        ItemStack[] inventory) {
        lockToRecipe(
            inputs,
            outputs,
            Collections.singletonList(new SlotLockTarget(this, inventory, inputSlots)),
            Collections.singletonList(new SlotLockTarget(this, inventory, outputSlots)));
    }

    /**
     * Locks slots spread over several inventories (e.g. the busses of a multiblock) to the items of a recipe. See
     * {@link #lockToRecipe(List, List, int[], int[], ItemStack[])}. Player locks set for a previous recipe are removed
     * first, so selecting another recipe replaces the old configuration.
     */
    public static void lockToRecipe(List<ItemStack> inputs, List<ItemStack> outputs, List<SlotLockTarget> inputTargets,
        List<SlotLockTarget> outputTargets) {
        for (SlotLockTarget target : inputTargets) target.state()
            .clearPlayerLocks(target.slots(), target.inventory());
        for (SlotLockTarget target : outputTargets) target.state()
            .clearPlayerLocks(target.slots(), target.inventory());
        for (ItemStack input : inputs) lockForStack(input, inputTargets);
        for (ItemStack output : outputs) lockForStack(output, outputTargets);
        if (!inputs.isEmpty()) lockRemaining(inputTargets);
        if (!outputs.isEmpty()) lockRemaining(outputTargets);
    }

    private static void lockForStack(@Nullable ItemStack stack, List<SlotLockTarget> targets) {
        if (stack == null) return;
        int required = Math.max(1, stack.stackSize);

        // Amount already covered by existing locks
        for (SlotLockTarget target : targets) {
            for (int slot : target.slots()) {
                if (target.state()
                    .isLockedTo(slot, stack)) {
                    required -= target.state()
                        .getCapacityFor(slot, stack);
                    if (required <= 0) return;
                }
            }
        }

        // First pass: slots that already contain the item. Second pass: empty slots.
        for (int pass = 0; pass < 2 && required > 0; pass++) {
            for (SlotLockTarget target : targets) {
                SlotLockState state = target.state();
                ItemStack[] inventory = target.inventory();
                for (int slot : target.slots()) {
                    if (required <= 0) return;
                    if (!state.inRange(slot) || slot >= inventory.length) continue;
                    if (state.isLocked(slot) && state.lockedItems[slot] != null) continue;
                    ItemStack current = inventory[slot];
                    boolean applicable = pass == 0 ? matches(current, stack) : current == null;
                    if (!applicable) continue;
                    int cap = state.getCapacityFor(slot, stack);
                    if (cap <= 0) continue;
                    state.lockedItems[slot] = copyOne(stack);
                    state.playerLocked[slot] = true;
                    required -= cap;
                }
            }
        }
    }

    private static void lockRemaining(List<SlotLockTarget> targets) {
        for (SlotLockTarget target : targets) {
            SlotLockState state = target.state();
            ItemStack[] inventory = target.inventory();
            for (int slot : target.slots()) {
                if (!state.inRange(slot) || slot >= inventory.length) continue;
                if (inventory[slot] == null && !state.isLocked(slot)) {
                    state.playerLocked[slot] = true;
                    state.lockedItems[slot] = null;
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Capacity
    // ---------------------------------------------------------------------------------------------------------------

    public void setCapacity(int slot, int cap) {
        if (!inRange(slot)) return;
        capacity[slot] = (byte) Math.max(0, Math.min(MAX_CAPACITY, cap));
    }

    public void adjustCapacity(int slot, int delta) {
        setCapacity(slot, getCapacity(slot) + delta);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Persistence / networking
    // ---------------------------------------------------------------------------------------------------------------

    private boolean isDefault(int slot) {
        return !playerLocked[slot] && !machineLocked[slot]
            && lockedItems[slot] == null
            && capacity[slot] == MAX_CAPACITY;
    }

    public void reset() {
        Arrays.fill(lockedItems, null);
        Arrays.fill(playerLocked, false);
        Arrays.fill(machineLocked, false);
        Arrays.fill(capacity, (byte) MAX_CAPACITY);
    }

    public void save(NBTTagCompound tag) {
        NBTTagList list = new NBTTagList();
        for (int i = 0; i < size; i++) {
            if (isDefault(i)) continue;
            NBTTagCompound slotTag = new NBTTagCompound();
            slotTag.setInteger("slot", i);
            slotTag.setBoolean("pl", playerLocked[i]);
            slotTag.setBoolean("ml", machineLocked[i]);
            slotTag.setByte("cap", capacity[i]);
            if (lockedItems[i] != null) {
                slotTag.setTag("item", lockedItems[i].writeToNBT(new NBTTagCompound()));
            }
            list.appendTag(slotTag);
        }
        if (list.tagCount() > 0) {
            tag.setTag(NBT_KEY, list);
        } else {
            tag.removeTag(NBT_KEY);
        }
    }

    public void load(NBTTagCompound tag) {
        reset();
        if (!tag.hasKey(NBT_KEY, Constants.NBT.TAG_LIST)) return;
        NBTTagList list = tag.getTagList(NBT_KEY, Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound slotTag = list.getCompoundTagAt(i);
            int slot = slotTag.getInteger("slot");
            if (!inRange(slot)) continue;
            playerLocked[slot] = slotTag.getBoolean("pl");
            machineLocked[slot] = slotTag.getBoolean("ml");
            capacity[slot] = slotTag.hasKey("cap") ? slotTag.getByte("cap") : (byte) MAX_CAPACITY;
            lockedItems[slot] = slotTag.hasKey("item") ? ItemStack.loadItemStackFromNBT(slotTag.getCompoundTag("item"))
                : null;
            if (!playerLocked[slot] && !machineLocked[slot]) lockedItems[slot] = null;
        }
    }

    public void write(PacketBuffer buffer) {
        buffer.writeVarIntToBuffer(size);
        for (int i = 0; i < size; i++) {
            int flags = 0;
            if (playerLocked[i]) flags |= 1;
            if (machineLocked[i]) flags |= 2;
            if (lockedItems[i] != null) flags |= 4;
            buffer.writeByte(flags);
            buffer.writeByte(capacity[i]);
            if (lockedItems[i] != null) {
                NetworkUtils.writeItemStack(buffer, lockedItems[i]);
            }
        }
    }

    public void read(PacketBuffer buffer) throws IOException {
        int count = buffer.readVarIntFromBuffer();
        for (int i = 0; i < count; i++) {
            int flags = buffer.readByte();
            byte cap = buffer.readByte();
            ItemStack item = (flags & 4) != 0 ? NetworkUtils.readItemStack(buffer) : null;
            if (!inRange(i)) continue;
            playerLocked[i] = (flags & 1) != 0;
            machineLocked[i] = (flags & 2) != 0;
            capacity[i] = cap;
            lockedItems[i] = item;
        }
    }

    public SlotLockState copy() {
        SlotLockState copy = new SlotLockState(size);
        copy.copyFrom(this);
        return copy;
    }

    public void copyFrom(SlotLockState other) {
        int n = Math.min(size, other.size);
        for (int i = 0; i < n; i++) {
            playerLocked[i] = other.playerLocked[i];
            machineLocked[i] = other.machineLocked[i];
            capacity[i] = other.capacity[i];
            lockedItems[i] = other.lockedItems[i] == null ? null : other.lockedItems[i].copy();
        }
    }

    public boolean stateEquals(@Nullable SlotLockState other) {
        if (other == null || other.size != size) return false;
        for (int i = 0; i < size; i++) {
            if (playerLocked[i] != other.playerLocked[i] || machineLocked[i] != other.machineLocked[i]
                || capacity[i] != other.capacity[i]) return false;
            if ((lockedItems[i] == null) != (other.lockedItems[i] == null)) return false;
            if (lockedItems[i] != null && !ItemStack.areItemStacksEqual(lockedItems[i], other.lockedItems[i]))
                return false;
        }
        return true;
    }
}
