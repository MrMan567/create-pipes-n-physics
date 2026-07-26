package de.devin.pipesnphysics.display;

import net.createmod.catnip.lang.LangNumberFormat;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** The display-source metrics' shared line vocabulary: numbers with their units. */
final class DisplayLine {
    private DisplayLine() {}

    /** A flow rate, "N mB/t". */
    static MutableComponent mbRate(double v) {
        return Component.literal(LangNumberFormat.format(v)).append(tr("display_source.unit.mb"));
    }

    /** A stored volume, "N mB". */
    static MutableComponent mbAmount(double v) {
        return Component.literal(LangNumberFormat.format(v)).append(tr("display_source.unit.mb_amount"));
    }

    static MutableComponent blocks(double v) {
        return Component.literal(LangNumberFormat.format(v)).append(tr("display_source.unit.blocks"));
    }

    static MutableComponent blocksUp(double v) {
        return Component.literal(LangNumberFormat.format(v)).append(tr("display_source.unit.blocks_up"));
    }

    static MutableComponent percent(double v) {
        return Component.literal(Math.round(v) + "%");
    }

    static MutableComponent dash() {
        return Component.literal("—");
    }

    static MutableComponent tr(String key) {
        return Component.translatable("pipesnphysics." + key);
    }
}
