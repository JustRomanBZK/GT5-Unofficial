package gregtech.common.gui.modularui.hatch;

import net.minecraft.util.StatCollector;
import net.minecraftforge.fluids.FluidStack;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.value.sync.BooleanSyncValue;
import com.cleanroommc.modularui.value.sync.FluidSlotSyncHandler;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;

import gregtech.api.metatileentity.implementations.MTEHatchInput;
import gregtech.api.modularui2.GTGuiTextures;
import gregtech.api.modularui2.GTWidgetThemes;
import gregtech.api.modularui2.common.CommonWidgets;
import gregtech.common.gui.modularui.hatch.base.MTEHatchBaseGui;
import gregtech.common.modularui2.widget.FluidLockSlotWidget;

public class MTEHatchInputGui extends MTEHatchBaseGui<MTEHatchInput> {

    public MTEHatchInputGui(MTEHatchInput machine) {
        super(machine);
    }

    @Override
    protected ParentWidget<?> createContentSection(ModularPanel panel, PanelSyncManager syncManager) {
        Flow mainRow = Flow.row()
            .coverChildren()
            .childPadding(1);

        mainRow.child(createScreen(panel, syncManager, machine.getFluidTank()));
        mainRow.child(
            createIO(panel, syncManager, machine.getInputSlot(), machine.getOutputSlot(), machine.getFluidTank()));
        mainRow.childIf(supportsFluidFilterScreen(), () -> createFilterScreen(panel, syncManager));

        return super.createContentSection(panel, syncManager).child(mainRow);
    }

    @Override
    protected boolean supportsFluidFilterScreen() {
        return machine.acceptsFluidLock(null);
    }

    /**
     * Screen showing the fluid lock of the hatch. Unlike the digital tank, an input hatch can also be locked to
     * nothing (by locking it to a recipe from NEI), which is displayed here.
     */
    @Override
    protected ParentWidget<?> createFilterScreen(ModularPanel panel, PanelSyncManager syncManager) {
        ParentWidget<?> screen = CommonWidgets.createFluidScreen(FLUID_FILTER_SCREEN_WIDTH, FLUID_FILTER_SCREEN_HEIGHT);

        Flow textColumn = Flow.column()
            .childPadding(1)
            .crossAxisAlignment(Alignment.CrossAxis.START);

        FluidLockSlotWidget fluidLockSlotWidget = new FluidLockSlotWidget(machine);
        // The locked fluid is synced by the slot, the lock flag has to be synced separately for the label
        BooleanSyncValue fluidLockedSync = new BooleanSyncValue(machine::isFluidLocked);
        syncManager.syncValue("fluidLocked", fluidLockedSync);

        textColumn.child(
            IKey.lang("GT5U.gui.fluid_lock.label")
                .asWidget()
                .widgetTheme(GTWidgetThemes.DISPLAY_TEXT_WHITE));

        textColumn.child(IKey.dynamic(() -> {
            FluidStack fluid = fluidLockSlotWidget.getFluid();
            if (fluid != null) {
                return fluid.getLocalizedName();
            }
            return StatCollector.translateToLocal(
                fluidLockedSync.getBoolValue() ? "GT5U.gui.fluid_lock.nothing" : "GT5U.gui.fluid_lock.none");
        })
            .asWidget()
            .widgetTheme(GTWidgetThemes.DISPLAY_TEXT_WHITE));

        screen.child(textColumn);

        screen.child(
            fluidLockSlotWidget.syncHandler(new FluidSlotSyncHandler(fluidLockSlotWidget).phantom(true))
                .bottomRel(0)
                .rightRel(0)
                .background(GTGuiTextures.SLOT_FLUID_TANK));

        return screen;
    }
}
