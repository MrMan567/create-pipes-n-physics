package de.devin.pipesnphysics.datagen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.devin.pipesnphysics.PipesNPhysics;
import de.devin.pipesnphysics.client.ponder.PnpPonderPlugin;
import net.createmod.ponder.foundation.PonderIndex;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.LanguageProvider;
import net.neoforged.neoforge.data.event.GatherDataEvent;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/**
 * Generates the shipped en_us.json: the hand-maintained entries from lang/default/en_us.json merged
 * with every ponder title and narration string harvested from the scene code, so ponder text lives
 * only in the storyboards. Run ./gradlew runData after editing scenes or the default lang file.
 */
public class PnpLanguageProvider extends LanguageProvider {
    private static final String DEFAULT_LANG = "/assets/" + PipesNPhysics.ID + "/lang/default/en_us.json";

    public static void gather(GatherDataEvent event) {
        event.getGenerator().addProvider(true, new PnpLanguageProvider(event.getGenerator().getPackOutput()));
    }

    private PnpLanguageProvider(PackOutput output) {
        super(output, PipesNPhysics.ID, "en_us");
    }

    @Override
    protected void addTranslations() {
        addHandwritten();
        addPonderText();
    }

    /** Every key from the hand-maintained default lang file, verbatim. */
    private void addHandwritten() {
        try (InputStream stream = getClass().getResourceAsStream(DEFAULT_LANG)) {
            if (stream == null) throw new IllegalStateException("missing " + DEFAULT_LANG);
            Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8);
            JsonObject entries = JsonParser.parseReader(reader).getAsJsonObject();
            entries.entrySet().forEach(entry -> add(entry.getKey(), entry.getValue().getAsString()));
        } catch (Exception e) {
            throw new IllegalStateException("could not read " + DEFAULT_LANG, e);
        }
    }

    /** Compiles every registered ponder scene and collects its title/.text() literals. */
    private void addPonderText() {
        PonderIndex.addPlugin(new PnpPonderPlugin());
        PonderIndex.getLangAccess().provideLang(PipesNPhysics.ID, this::add);
    }
}
