package de.devin.pipesnphysics.display;

import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.engine.net.PipeStatusPayload;
import net.createmod.catnip.lang.LangNumberFormat;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

import static de.devin.pipesnphysics.display.DisplayLine.amountOfCapacity;
import static de.devin.pipesnphysics.display.DisplayLine.blocks;
import static de.devin.pipesnphysics.display.DisplayLine.blocksUp;
import static de.devin.pipesnphysics.display.DisplayLine.dash;
import static de.devin.pipesnphysics.display.DisplayLine.mbAmount;
import static de.devin.pipesnphysics.display.DisplayLine.mbRate;
import static de.devin.pipesnphysics.display.DisplayLine.percent;
import static de.devin.pipesnphysics.display.DisplayLine.tr;

/**
 * One selectable readout a display link can pull off a pipe network cell. Each
 * metric turns a solved {@link Readout} into a single display line; the pipe and
 * pump sources each expose an ordered subset (a {@code Metric} scroll option in the
 * link GUI), so the stored index maps back to a metric within that source's list.
 */
public enum PipeDisplayMetric {
    FLOW("flow", r -> mbRate(r.data().mbPerTick())),
    FLUID("fluid", r -> fluid(r.data())),
    DIRECTION("direction", r -> direction(r.data())),
    PRESSURE("pressure", r -> r.data().hasPressure() ? blocks(r.data().pressureBlocks()) : dash()),
    LIFT_LEFT("lift", r -> r.data().hasHeadroom() ? blocksUp(r.data().headroomBlocks()) : dash()),
    STATUS("status", r -> status(r.data())),
    SUMMARY("summary", PipeDisplayMetric::pipeSummary),
    FILL("fill", PipeDisplayMetric::pipeFill),

    CAPACITY("capacity", r -> mbRate(r.cap())),
    THROUGHPUT("throughput", r -> percent(r.throughput())),
    CAN_LIFT("lift", r -> blocksUp(r.canLift())),
    LIMITER("limiter", PipeDisplayMetric::limiter),
    PUMP_SUMMARY("summary", PipeDisplayMetric::pumpSummary);

    /** The pipe source's options, in scroll order (append-only — links store the index). */
    public static final List<PipeDisplayMetric> PIPE_METRICS =
            List.of(FLOW, FLUID, DIRECTION, PRESSURE, LIFT_LEFT, STATUS, SUMMARY, FILL);
    /** The pump source's options, in scroll order. */
    public static final List<PipeDisplayMetric> PUMP_METRICS =
            List.of(FLOW, CAPACITY, THROUGHPUT, CAN_LIFT, LIMITER, FLUID, STATUS, PUMP_SUMMARY);

    private final String key;
    private final Formatter formatter;

    PipeDisplayMetric(String key, Formatter formatter) {
        this.key = key;
        this.formatter = formatter;
    }

    /** The option's lang sub-key; combined with each source's prefix for the label. */
    public String key() {
        return key;
    }

    public MutableComponent format(Readout readout) {
        return formatter.format(readout);
    }

    @FunctionalInterface
    interface Formatter {
        MutableComponent format(Readout readout);
    }

    /**
     * The per-tick picture a metric reads: the probed cell state plus the pump's
     * curve limits (both zero for a plain pipe cell).
     */
    public record Readout(PipeStatusPayload data, double cap, double canLift) {
        double throughput() {
            return cap > 1e-6 ? Math.clamp(100.0 * data.mbPerTick() / cap, 0, 100) : 0;
        }
    }

    private static MutableComponent pipeSummary(Readout r) {
        PipeStatusPayload d = r.data();
        if (d.status() != PipeStatusPayload.STATUS_FLOWING || d.mbPerTick() <= 0) {
            // A non-flowing pipe still HOLDS fluid (a settling run drains toward its
            // profile over many ticks) — show the held volume beside the state.
            MutableComponent line = status(d);
            if (d.holdsMb() > 0) line.append(" · ").append(mbAmount(d.holdsMb()));
            return line;
        }
        MutableComponent line = mbRate(d.mbPerTick());
        if (d.flowDirection() != null) line.append(" ").append(tr("direction." + d.flowDirection().getName()));
        if (!d.fluid().isEmpty()) line.append(" (").append(d.fluid().getHoverName()).append(")");
        return line;
    }

    /** The cell's stored volume against the per-cell capacity; wire mode stores nothing. */
    private static MutableComponent pipeFill(Readout r) {
        int capacity = PipesNPhysicsConfig.PIPE_VOLUME_PER_CELL.get();
        return capacity > 0 ? amountOfCapacity(r.data().holdsMb(), capacity) : dash();
    }

    private static MutableComponent pumpSummary(Readout r) {
        return Component.literal(LangNumberFormat.format(r.data().mbPerTick()))
                .append(" / ").append(Component.literal(LangNumberFormat.format(r.cap())))
                .append(tr("display_source.unit.mb"))
                .append(" (").append(Component.literal(Math.round(r.throughput()) + "%")).append(")");
    }

    private static MutableComponent limiter(Readout r) {
        PipeStatusPayload d = r.data();
        if (d.mbPerTick() <= 0 || !d.hasPumpLoad() || r.canLift() <= 1e-6) return dash();
        if (r.throughput() >= 95) return tr("display_source.limiter.none");
        float headFactor = (float) ((r.canLift() - d.pumpHeadAgainst()) / r.canLift());
        return tr(headFactor < d.pumpFrictionFactor() ? "display_source.limiter.lift" : "display_source.limiter.pipe");
    }

    private static MutableComponent status(PipeStatusPayload d) {
        String state = switch (d.status()) {
            case PipeStatusPayload.STATUS_FLOWING -> "flowing";
            case PipeStatusPayload.STATUS_BLOCKED -> "blocked";
            case PipeStatusPayload.STATUS_STALLED -> "full";
            case PipeStatusPayload.STATUS_NO_HEAD -> "no_head";
            case PipeStatusPayload.STATUS_NOT_CONNECTED -> "not_connected";
            default -> "idle";
        };
        return tr("display_source.status." + state);
    }

    private static MutableComponent fluid(PipeStatusPayload d) {
        return d.fluid().isEmpty() ? dash() : d.fluid().getHoverName().copy();
    }

    private static MutableComponent direction(PipeStatusPayload d) {
        Direction dir = d.flowDirection();
        return dir == null ? dash() : tr("direction." + dir.getName());
    }
}
