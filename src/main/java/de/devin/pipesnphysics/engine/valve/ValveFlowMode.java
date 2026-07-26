package de.devin.pipesnphysics.engine.valve;

import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.INamedIconOptions;
import com.simibubi.create.foundation.gui.AllIcons;

/**
 * The fluid valve's flow-direction dial positions, in scroll-value order (the ordinal IS the
 * stored value). The icons are what the value box shows: double-arrow for both ways, a single
 * arrow for each one-way sense. The arrows cannot know the valve's world orientation — the
 * TRANSLATION here is only the static fallback; every player-facing surface (the drag board,
 * the goggle) renders the resolved world direction ("One-way → East") instead.
 */
public enum ValveFlowMode implements INamedIconOptions {
    BOTH_WAYS(AllIcons.I_FLIP),
    /** One-way toward the POSITIVE end of the pipe axis (east / up / south). */
    ONE_WAY_FORWARD(AllIcons.I_MTD_RIGHT),
    /** One-way toward the NEGATIVE end of the pipe axis (west / down / north). */
    ONE_WAY_REVERSE(AllIcons.I_MTD_LEFT);

    private final AllIcons icon;

    ValveFlowMode(AllIcons icon) {
        this.icon = icon;
    }

    @Override
    public AllIcons getIcon() {
        return icon;
    }

    @Override
    public String getTranslationKey() {
        return "pipesnphysics.gui.valve.direction." + name().toLowerCase();
    }
}
