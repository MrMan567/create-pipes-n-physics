package de.devin.pipesnphysics.display;

import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;
import de.devin.pipesnphysics.engine.boundary.FluidCaps;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * Makes a Create display link read a fluid vessel's contents off its own handler:
 * summed amount and capacity, fill percent, or the held fluid. Any cell of a
 * multiblock tank reads the whole vessel (the capability resolves through the
 * controller), and a lighter-than-air gas reads the same as a liquid.
 */
public class TankContentsDisplaySource extends MetricDisplaySource {
    public TankContentsDisplaySource() {
        super("display_source.tank_metric", TankDisplayMetric.KEYS);
    }

    @Override
    protected MutableComponent provideLine(DisplayLinkContext context, DisplayTargetStats stats) {
        IFluidHandler handler = FluidCaps.at(context.level(), context.getSourcePos(), null);
        if (handler == null) return DisplayLine.dash();
        return TankDisplayMetric.METRICS.get(metricIndex(context))
                .format(TankDisplayMetric.Readout.of(handler));
    }
}
