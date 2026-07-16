package de.devin.pipesnphysics.display;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.source.SingleLineDisplaySource;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;
import com.simibubi.create.foundation.gui.ModularGuiLineBuilder;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.display.PipeDisplayMetric.Readout;
import de.devin.pipesnphysics.engine.net.PipeStatusPayload;
import de.devin.pipesnphysics.engine.probe.PipeProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

/**
 * Makes a Create display link read a live pipe-network cell: it solves the network
 * at the source block (via the read-only {@link PipeProbe}) and writes one selected
 * metric per tick. The pipe and pump variants are the same source configured with a
 * different metric list — the pump one also reads the pump's RPM to derive its curve
 * cap and lift, which the pipe metrics never touch.
 */
public class PipeNetworkDisplaySource extends SingleLineDisplaySource {
    private final List<PipeDisplayMetric> metrics;
    private final String optionPrefix;
    private final boolean pump;

    public PipeNetworkDisplaySource(List<PipeDisplayMetric> metrics, String optionPrefix, boolean pump) {
        this.metrics = metrics;
        this.optionPrefix = optionPrefix;
        this.pump = pump;
    }

    @Override
    protected MutableComponent provideLine(DisplayLinkContext context, DisplayTargetStats stats) {
        if (!(context.level() instanceof ServerLevel level)) return EMPTY_LINE;
        BlockPos pos = context.getSourcePos();
        PipeStatusPayload data = PipeProbe.probe(level, pos);
        if (data.status() == PipeStatusPayload.STATUS_NOT_CONNECTED) return Component.literal("—");

        double cap = 0, canLift = 0;
        if (pump) {
            float speed = level.getBlockEntity(pos) instanceof KineticBlockEntity k ? Math.abs(k.getSpeed()) : 0f;
            cap = speed * PipesNPhysicsConfig.PUMP_FLOW_PER_RPM.get();
            canLift = speed * PipesNPhysicsConfig.PUMP_HEAD_PER_RPM.get();
        }
        int index = Math.clamp(context.sourceConfig().getInt("Metric"), 0, metrics.size() - 1);
        return metrics.get(index).format(new Readout(data, cap, canLift));
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
        List<Component> options = metrics.stream()
                .map(m -> (Component) Component.translatable("pipesnphysics." + optionPrefix + "." + m.key()))
                .toList();
        builder.addSelectionScrollInput(0, 120,
                (input, label) -> input.forOptions(options)
                        .titled(Component.translatable("pipesnphysics." + optionPrefix + ".title")),
                "Metric");
    }
}
