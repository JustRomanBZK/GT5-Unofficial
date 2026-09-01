package gregtech.common.modularui2.sync;

import java.io.IOException;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;

import org.jetbrains.annotations.Nullable;

import com.cleanroommc.modularui.network.NetworkUtils;
import com.cleanroommc.modularui.utils.MouseData;
import com.cleanroommc.modularui.value.sync.SyncHandler;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.metatileentity.ISlotLockable;
import gregtech.api.util.slotlock.SlotLockState;

/**
 * Synchronizes the {@link SlotLockState} of an {@link ISlotLockable} machine with the client and executes lock
 * requests coming from the GUI. One instance per GUI, register it with {@link #KEY}.
 */
public class SlotLockSyncHandler extends SyncHandler<SlotLockSyncHandler> {

    public static final String KEY = "gt:slotLocks";

    private static final int SYNC_STATE = 0;
    private static final int ACTION_TOGGLE = 1;
    private static final int ACTION_CAPACITY = 2;
    private static final int ACTION_LOCK_ALL = 3;
    private static final int ACTION_LOCK_ITEM = 4;

    private final ISlotLockable lockable;
    private final IMetaTileEntity mte;
    private SlotLockState lastSynced;
    /** Client side GUI state: whether clicking a slot toggles its lock instead of moving items. */
    private boolean lockingMode;

    public SlotLockSyncHandler(ISlotLockable lockable) {
        if (!(lockable instanceof IMetaTileEntity mte)) {
            throw new IllegalArgumentException("ISlotLockable must be a MetaTileEntity");
        }
        this.lockable = lockable;
        this.mte = mte;
        allowC2S();
    }

    public ISlotLockable getLockable() {
        return lockable;
    }

    public SlotLockState getState() {
        return lockable.getSlotLockState();
    }

    public boolean isLockingMode() {
        return lockingMode;
    }

    public void setLockingMode(boolean lockingMode) {
        this.lockingMode = lockingMode;
    }

    public boolean hasUnlockedSlot() {
        return getState().hasUnlockedSlot(lockable.getLockableSlots());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Server -> client
    // ---------------------------------------------------------------------------------------------------------------

    @Override
    public void detectAndSendChanges(boolean init) {
        SlotLockState state = getState();
        if (init || lastSynced == null || !lastSynced.stateEquals(state)) {
            lastSynced = state.copy();
            syncToClient(SYNC_STATE, state::write);
        }
    }

    @Override
    public void readOnClient(int id, PacketBuffer buf) throws IOException {
        if (id == SYNC_STATE) {
            getState().read(buf);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Client -> server
    // ---------------------------------------------------------------------------------------------------------------

    @Override
    public void readOnServer(int id, PacketBuffer buf) throws IOException {
        switch (id) {
            case ACTION_TOGGLE -> {
                int slot = buf.readVarIntFromBuffer();
                MouseData mouseData = MouseData.readPacket(buf);
                toggleLock(slot, mouseData);
            }
            case ACTION_CAPACITY -> {
                int slot = buf.readVarIntFromBuffer();
                int delta = buf.readVarIntFromBuffer();
                if (lockable.isSlotLockable(slot)) {
                    getState().adjustCapacity(slot, delta);
                    lockable.onSlotLocksChanged();
                }
            }
            case ACTION_LOCK_ALL -> {
                boolean lock = buf.readBoolean();
                SlotLockState state = getState();
                for (int slot : lockable.getLockableSlots()) {
                    state.setPlayerLocked(slot, lock, mte.getStackInSlot(slot));
                }
                lockable.onSlotLocksChanged();
            }
            case ACTION_LOCK_ITEM -> {
                int slot = buf.readVarIntFromBuffer();
                ItemStack item = NetworkUtils.readItemStack(buf);
                if (lockable.isSlotLockable(slot) && item != null) {
                    if (getState().playerLock(slot, mte.getStackInSlot(slot), item)) {
                        lockable.onSlotLocksChanged();
                    }
                }
            }
            default -> {}
        }
    }

    private void toggleLock(int slot, MouseData mouseData) {
        if (!lockable.isSlotLockable(slot)) return;
        SlotLockState state = getState();
        if (mouseData.shift) {
            // Move the content to the player and close the slot, like shift-click in MI's locking mode
            EntityPlayer player = getSyncManager().getPlayer();
            ItemStack current = mte.getStackInSlot(slot);
            if (current != null) {
                ItemStack remaining = current.copy();
                player.inventory.addItemStackToInventory(remaining);
                mte.setInventorySlotContents(slot, remaining.stackSize > 0 ? remaining : null);
                player.inventory.markDirty();
            }
            if (mte.getStackInSlot(slot) == null) {
                state.playerLock(slot, null, null);
            }
        } else {
            state.togglePlayerLock(slot, mte.getStackInSlot(slot), getSyncManager().getCursorItem());
        }
        lockable.onSlotLocksChanged();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Client API
    // ---------------------------------------------------------------------------------------------------------------

    @SideOnly(Side.CLIENT)
    public void requestToggle(int slot, int mouseButton) {
        MouseData mouseData = MouseData.create(mouseButton);
        syncToServer(ACTION_TOGGLE, buf -> {
            buf.writeVarIntToBuffer(slot);
            mouseData.writeToPacket(buf);
        });
    }

    @SideOnly(Side.CLIENT)
    public void requestCapacityChange(int slot, int delta) {
        syncToServer(ACTION_CAPACITY, buf -> {
            buf.writeVarIntToBuffer(slot);
            buf.writeVarIntToBuffer(delta);
        });
    }

    @SideOnly(Side.CLIENT)
    public void requestLockAll(boolean lock) {
        syncToServer(ACTION_LOCK_ALL, buf -> buf.writeBoolean(lock));
    }

    @SideOnly(Side.CLIENT)
    public void requestLockToItem(int slot, ItemStack item) {
        syncToServer(ACTION_LOCK_ITEM, buf -> {
            buf.writeVarIntToBuffer(slot);
            NetworkUtils.writeItemStack(buf, item);
        });
    }

    /**
     * Client side prediction of whether the slot can be locked to the given item.
     */
    public boolean canLockTo(int slot, @Nullable ItemStack item) {
        return lockable.isSlotLockable(slot) && getState().canPlayerLock(slot, mte.getStackInSlot(slot), item);
    }
}
