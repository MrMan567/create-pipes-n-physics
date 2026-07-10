package de.devin.pipesnphysics.recipe;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import de.devin.pipesnphysics.PipesNPhysics;
import de.devin.pipesnphysics.api.CentrifugeApi;
import de.devin.pipesnphysics.api.CentrifugeRecipe;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Loads and holds the centrifuging recipes from {@code data/<ns>/centrifuging/*.json}. A plain
 * datapack reload listener rather than a full {@code RecipeType}: these recipes are fluid-only and
 * consumed internally by {@link de.devin.pipesnphysics.engine.CentrifugeProcessor}, so the vanilla
 * item-recipe machinery would only add friction. Held in a volatile list swapped atomically on
 * reload; read from the server tick.
 */
public class CentrifugeRecipes extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = new Gson();
    private static volatile List<CentrifugeRecipe> recipes = List.of();

    public CentrifugeRecipes() {
        super(GSON, "centrifuging");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> raw, ResourceManager manager, ProfilerFiller profiler) {
        List<CentrifugeRecipe> parsed = new ArrayList<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : raw.entrySet()) {
            CentrifugeRecipe.CODEC.parse(JsonOps.INSTANCE, entry.getValue())
                    .resultOrPartial(error -> PipesNPhysics.LOGGER.error(
                            "Skipping invalid centrifuging recipe {}: {}", entry.getKey(), error))
                    .ifPresent(parsed::add);
        }
        recipes = List.copyOf(parsed);
        PipesNPhysics.LOGGER.info("Loaded {} centrifuging recipe(s)", recipes.size());
        // Install ourselves as the API's datapack source (inverts the dependency: recipe -> api).
        CentrifugeApi.updateDatapackSource(CentrifugeRecipes::find, !recipes.isEmpty());
    }

    /** The recipe whose input matches {@code held} (fluid + minimum amount), or null if none. */
    private static CentrifugeRecipe find(FluidStack held) {
        for (CentrifugeRecipe recipe : recipes) {
            if (recipe.matches(held)) return recipe;
        }
        return null;
    }
}
