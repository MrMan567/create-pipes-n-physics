package de.devin.pipesnphysics.engine.turbine;

import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.INamedIconOptions;
import com.simibubi.create.foundation.gui.AllIcons;
import de.devin.pipesnphysics.client.PnpIcons;

/**
 * Which way round a Mechanical Pump runs, in scroll-value order (the ordinal IS the stored value).
 * A pump is a reversible machine: driven, it ADDS head to the line; run backwards as a TURBINE it
 * takes head back out and generates rotation from the fall instead.
 *
 * AUTO — what a freshly placed pump comes up as — needs no head measurement to decide: an undriven
 * pump simply IS a turbine that may or may not be turning, because a fall short of the rating
 * passes nothing anyway. So the wall an unpowered pump gives you survives everywhere it mattered,
 * and the only behaviour that changes is the case you would have dialed it for.
 *
 * PUMP stays ordinal 0 so a pump saved before this feature — no stored key, so the value reads 0 —
 * keeps the stock behaviour exactly, including walling a fall it could have turned on.
 */
public enum PumpMode implements INamedIconOptions {
    PUMP(PnpIcons.I_PUMP),
    TURBINE(PnpIcons.I_TURBINE),
    AUTO(AllIcons.I_FLIP);

    private final AllIcons icon;

    PumpMode(AllIcons icon) {
        this.icon = icon;
    }

    @Override
    public AllIcons getIcon() {
        return icon;
    }

    @Override
    public String getTranslationKey() {
        return "pipesnphysics.gui.pump.mode." + name().toLowerCase();
    }
}
