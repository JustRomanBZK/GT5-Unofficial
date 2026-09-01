package gregtech.common.modularui2.widget;

import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.drawable.GuiDraw;
import com.cleanroommc.modularui.integration.recipeviewer.RecipeViewerGhostIngredientSlot;
import com.cleanroommc.modularui.screen.RichTooltip;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widgets.slot.ItemSlot;
import com.cleanroommc.modularui.widgets.slot.ModularSlot;

import gregtech.api.modularui2.GTGuiTextures;
import gregtech.api.util.slotlock.SlotLockState;
import gregtech.common.modularui2.sync.SlotLockSyncHandler;

/**
 * Item slot of a machine whose lock state is managed by a {@link SlotLockSyncHandler}. Renders the lock state, lets
 * the player toggle locks while the GUI is in locking mode, adjusts the slot capacity with the mouse wheel and accepts
 * ghost items dragged from NEI to lock the slot to them.
 */
public class LockableItemSlot extends ItemSlot implements RecipeViewerGhostIngredientSlot<ItemStack> {

    private static final int COLOR_PLAYER_LOCK = 0x50FFC800;
    private static final int COLOR_MACHINE_LOCK = 0x5000C8FF;
    private static final int COLOR_GHOST_FADE = 0x99C6C6C6;

    private final SlotLockSyncHandler lockHandler;
    private int slotIndex = -1;

    public LockableItemSlot(SlotLockSyncHandler lockHandler) {
        this.lockHandler = lockHandler;
        tooltip().setAutoUpdate(true)
            .tooltipBuilder(this::buildEmptyTooltip);
    }

    public int getSlotIndex() {
        return slotIndex;
    }

    private SlotLockState getState() {
        return lockHandler.getState();
    }

    @Override
    public ItemSlot slot(ModularSlot slot) {
        this.slotIndex = slot.getSlotIndex();
        return super.slot(slot);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------------------------------------------------

    @Override
    public void draw(ModularGuiContext context, WidgetThemeEntry<?> widgetTheme) {
        SlotLockState state = getState();
        boolean playerLocked = state.isPlayerLocked(slotIndex);
        boolean machineLocked = state.isMachineLocked(slotIndex);
        if (playerLocked || machineLocked) {
            GuiDraw.drawRect(1, 1, 16, 16, playerLocked ? COLOR_PLAYER_LOCK : COLOR_MACHINE_LOCK);
        }
        boolean empty = !isSynced() || getSlot().getStack() == null;
        if (empty && (playerLocked || machineLocked)) {
            ItemStack ghost = state.getLockedItem(slotIndex);
            if (ghost != null) {
                GuiDraw.drawItem(ghost, 1, 1, 16, 16, context.getCurrentDrawingZ());
                GuiDraw.drawRect(1, 1, 16, 16, COLOR_GHOST_FADE);
            } else {
                GTGuiTextures.OVERLAY_BUTTON_CROSS.draw(1, 1, 16, 16);
            }
        }
        super.draw(context, widgetTheme);
        if (playerLocked) {
            GTGuiTextures.OVERLAY_BUTTON_LOCKED.draw(10, 10, 8, 8);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Tooltips
    // ---------------------------------------------------------------------------------------------------------------

    @Override
    public void buildTooltip(ItemStack stack, RichTooltip tooltip) {
        super.buildTooltip(stack, tooltip);
        addLockLines(tooltip);
    }

    private void buildEmptyTooltip(RichTooltip tooltip) {
        addLockLines(tooltip);
    }

    private void addLockLines(RichTooltip tooltip) {
        SlotLockState state = getState();
        if (slotIndex < 0) return;
        if (state.isPlayerLocked(slotIndex)) {
            ItemStack locked = state.getLockedItem(slotIndex);
            if (locked != null) {
                tooltip.addLine(
                    StatCollector
                        .translateToLocalFormatted("GT5U.gui.slot_lock.tooltip.locked", locked.getDisplayName()));
            } else {
                tooltip.addLine(StatCollector.translateToLocal("GT5U.gui.slot_lock.tooltip.locked_empty"));
            }
        } else if (state.isMachineLocked(slotIndex)) {
            ItemStack locked = state.getLockedItem(slotIndex);
            tooltip.addLine(
                StatCollector.translateToLocalFormatted(
                    "GT5U.gui.slot_lock.tooltip.machine_locked",
                    locked != null ? locked.getDisplayName() : ""));
        }
        int capacity = state.getCapacity(slotIndex);
        String capacityText = capacity != SlotLockState.MAX_CAPACITY
            ? EnumChatFormatting.YELLOW + String.valueOf(capacity) + EnumChatFormatting.GRAY
            : String.valueOf(capacity);
        tooltip.addLine(StatCollector.translateToLocalFormatted("GT5U.gui.slot_lock.tooltip.capacity", capacityText));
        if (lockHandler.isLockingMode()) {
            tooltip.addLine(StatCollector.translateToLocal("GT5U.gui.slot_lock.tooltip.hint"));
            tooltip.addLine(StatCollector.translateToLocal("GT5U.gui.slot_lock.tooltip.hint.1"));
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Interaction
    // ---------------------------------------------------------------------------------------------------------------

    @Override
    public @NotNull Result onMousePressed(int mouseButton) {
        if (lockHandler.isLockingMode() && slotIndex >= 0) {
            if (mouseButton == 0 || mouseButton == 1) {
                lockHandler.requestToggle(slotIndex, mouseButton);
            }
            return Result.SUCCESS;
        }
        return super.onMousePressed(mouseButton);
    }

    @Override
    public boolean onMouseRelease(int mouseButton) {
        if (lockHandler.isLockingMode()) {
            return true;
        }
        return super.onMouseRelease(mouseButton);
    }

    @Override
    public void onMouseDrag(int mouseButton, long timeSinceClick) {
        if (lockHandler.isLockingMode()) return;
        super.onMouseDrag(mouseButton, timeSinceClick);
    }

    @Override
    public boolean onMouseScroll(UpOrDown scrollDirection, int amount) {
        if (!lockHandler.isLockingMode() || slotIndex < 0) return false;
        int delta = scrollDirection.modifier * (Interactable.hasShiftDown() ? 8 : 1);
        lockHandler.requestCapacityChange(slotIndex, delta);
        return true;
    }

    @Override
    public boolean handleDragAndDrop(@NotNull ItemStack draggedStack, int button) {
        if (!areAncestorsEnabled() || slotIndex < 0) return false;
        if (!lockHandler.canLockTo(slotIndex, draggedStack)) return false;
        lockHandler.requestLockToItem(slotIndex, draggedStack);
        draggedStack.stackSize = 0;
        return true;
    }

    @Override
    public @Nullable ItemStack getStackForRecipeViewer() {
        ItemStack stack = super.getStackForRecipeViewer();
        if (stack == null && slotIndex >= 0) {
            return getState().getLockedItem(slotIndex);
        }
        return stack;
    }
}
