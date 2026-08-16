package de.devin.pipesnphysics.client;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.foundation.item.TooltipHelper;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import net.createmod.catnip.lang.FontHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import java.util.List;

/**
 * Explains the Mechanical Pump's two roles on the ITEM, in Create's own shift-to-read style: what
 * it does while a shaft drives it, what it does while nothing does, and that a dial on its side
 * can pin either.
 *
 * It rides {@link ItemTooltipEvent} rather than Create's {@code TooltipModifier.REGISTRY}, because
 * that registry refuses a second entry for an item that already has one ("already has an associated
 * value") and its providers only fill in for items with NO entry at all. The pump is Create's item
 * and already carries Create's description, so appending on the event is the one place an addendum
 * fits. Create builds its own tooltips from the same event, so ours lands under theirs.
 */
public final class PumpTooltip {
    /** Each pair is a gray "when" line and the indented sentence under it. */
    private static final String[] SECTIONS = {"driven", "undriven", "dial"};

    private PumpTooltip() {}

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        if (!Screen.hasShiftDown()) return;
        if (!event.getItemStack().is(AllBlocks.MECHANICAL_PUMP.get().asItem())) return;
        // The roles only exist while the feature is on, and the value lives on the SERVER spec:
        // a tooltip can be drawn before that spec is loaded (no world), so ask before reading.
        if (!PipesNPhysicsConfig.SERVER_SPEC.isLoaded()) return;
        if (!PipesNPhysicsConfig.ENABLE_HYDRO_TURBINE.get()) return;

        List<Component> tooltip = event.getToolTip();
        tooltip.add(CommonComponents.EMPTY);
        for (String section : SECTIONS) {
            tooltip.add(Component.translatable("pipesnphysics.tooltip.pump." + section)
                    .withStyle(ChatFormatting.GRAY));
            tooltip.addAll(TooltipHelper.cutTextComponent(
                    Component.translatable("pipesnphysics.tooltip.pump." + section + "_text"),
                    FontHelper.Palette.STANDARD_CREATE.primary(),
                    FontHelper.Palette.STANDARD_CREATE.highlight(), 1));
        }
    }
}
