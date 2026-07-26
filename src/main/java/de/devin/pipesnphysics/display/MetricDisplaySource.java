package de.devin.pipesnphysics.display;

import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.source.SingleLineDisplaySource;
import com.simibubi.create.foundation.gui.ModularGuiLineBuilder;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

/**
 * A display source whose link GUI offers one "Metric" scroll of readout options: owns
 * the option widget, labeling, the refresh cadence, and the stored-index lookup.
 * Subclasses supply the option lang sub-keys (under their prefix) and the line itself.
 */
public abstract class MetricDisplaySource extends SingleLineDisplaySource {
    private final String optionPrefix;
    private final List<String> metricKeys;

    protected MetricDisplaySource(String optionPrefix, List<String> metricKeys) {
        this.optionPrefix = optionPrefix;
        this.metricKeys = metricKeys;
    }

    /** The link's stored metric selection, clamped into the option list. */
    protected int metricIndex(DisplayLinkContext context) {
        return Math.clamp(context.sourceConfig().getInt("Metric"), 0, metricKeys.size() - 1);
    }

    @Override
    protected boolean allowsLabeling(DisplayLinkContext context) {
        return true;
    }

    @Override
    public int getPassiveRefreshTicks() {
        return 10;
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void initConfigurationWidgets(DisplayLinkContext context, ModularGuiLineBuilder builder, boolean isFirstLine) {
        super.initConfigurationWidgets(context, builder, isFirstLine);
        if (isFirstLine) return;
        List<Component> options = metricKeys.stream()
                .map(key -> (Component) Component.translatable("pipesnphysics." + optionPrefix + "." + key))
                .toList();
        builder.addSelectionScrollInput(0, 120,
                (input, label) -> input.forOptions(options)
                        .titled(Component.translatable("pipesnphysics." + optionPrefix + ".title")),
                "Metric");
    }
}
