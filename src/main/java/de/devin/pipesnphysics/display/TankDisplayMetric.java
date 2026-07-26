package de.devin.pipesnphysics.display;

import net.minecraft.network.chat.MutableComponent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import java.util.List;

import static de.devin.pipesnphysics.display.DisplayLine.amountOfCapacity;
import static de.devin.pipesnphysics.display.DisplayLine.dash;
import static de.devin.pipesnphysics.display.DisplayLine.mbAmount;
import static de.devin.pipesnphysics.display.DisplayLine.percent;

/**
 * One selectable readout a display link can pull off a fluid vessel. Each metric turns
 * the vessel's summed handler contents into a single display line; the {@code Metric}
 * scroll option in the link GUI indexes into {@link #METRICS}.
 */
public enum TankDisplayMetric {
    SUMMARY("summary", r -> amountOfCapacity(r.amount(), r.capacity())),
    AMOUNT("amount", r -> mbAmount(r.amount())),
    CAPACITY("capacity", r -> mbAmount(r.capacity())),
    FILL("fill", r -> percent(r.fillPercent())),
    FLUID("fluid", r -> r.fluid().isEmpty() ? dash() : r.fluid().getHoverName().copy());

    /** The scroll options, in order. */
    public static final List<TankDisplayMetric> METRICS = List.of(values());
    /** The option lang sub-keys, in scroll order. */
    public static final List<String> KEYS = METRICS.stream().map(TankDisplayMetric::key).toList();

    private final String key;
    private final Formatter formatter;

    TankDisplayMetric(String key, Formatter formatter) {
        this.key = key;
        this.formatter = formatter;
    }

    /** The option's lang sub-key; combined with the source's prefix for the label. */
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
     * A vessel's contents summed over every handler tank (a multi-segment basin counts
     * all its fluids, matching the engine's total-fill column), with the first held
     * fluid as the face.
     */
    public record Readout(FluidStack fluid, int amount, int capacity) {
        public static Readout of(IFluidHandler handler) {
            FluidStack face = FluidStack.EMPTY;
            int amount = 0, capacity = 0;
            for (int i = 0; i < handler.getTanks(); i++) {
                FluidStack held = handler.getFluidInTank(i);
                amount += held.getAmount();
                capacity += handler.getTankCapacity(i);
                if (face.isEmpty() && !held.isEmpty()) face = held;
            }
            return new Readout(face, amount, capacity);
        }

        public double fillPercent() {
            return capacity > 0 ? 100.0 * amount / capacity : 0;
        }
    }
}
