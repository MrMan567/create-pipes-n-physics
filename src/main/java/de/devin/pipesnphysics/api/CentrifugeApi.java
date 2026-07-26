package de.devin.pipesnphysics.api;

import net.neoforged.neoforge.fluids.FluidStack;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A separation is defined either as a datapack recipe in
 * the centrifuging folder, or in code with a CentrifugeSeparator when the outputs depend on the
 * fluid's data components. The engine asks find what a spinning tank's fluid splits into: the datapack
 * source first, so a pack can override a hook, then registered separators in registration order.
 * This package depends on nothing internal — the datapack loader installs its own lookup through
 * updateDatapackSource. Register from your mod's setup; find runs on the server thread.
 */
public final class CentrifugeApi {
    private static final List<CentrifugeSeparator> SEPARATORS = new CopyOnWriteArrayList<>();
    private static volatile CentrifugeSeparator datapackSource;
    private static volatile boolean datapackPresent;

    private CentrifugeApi() {}

    /** Register a behavioral hook that decides, per input fluid, how it splits when centrifuged. */
    public static void registerSeparator(CentrifugeSeparator separator) {
        SEPARATORS.add(separator);
    }

    /**
     * Internal: the datapack loader installs its lookup and current presence here on each reload, so
     * this package keeps no reference back to it. Not for external callers.
     */
    public static void updateDatapackSource(CentrifugeSeparator source, boolean present) {
        datapackSource = source;
        datapackPresent = present;
    }

    /**
     * The separation for a held fluid: a matching datapack recipe first, else the first registered
     * separator that handles it; null if nothing separates this fluid.
     */
    public static CentrifugeRecipe find(FluidStack held) {
        CentrifugeSeparator datapack = datapackSource;
        if (datapack != null) {
            CentrifugeRecipe recipe = datapack.separate(held);
            if (recipe != null && recipe.matches(held)) return recipe;
        }
        for (CentrifugeSeparator separator : SEPARATORS) {
            CentrifugeRecipe recipe = separator.separate(held);
            if (recipe != null && recipe.matches(held)) return recipe;
        }
        return null;
    }

    /** Whether any separation is defined at all, so the engine can skip the work when none is. */
    public static boolean hasAny() {
        return datapackPresent || !SEPARATORS.isEmpty();
    }
}
