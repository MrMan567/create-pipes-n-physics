# Create Pipes n Physics

<a href="https://track.devinfritz.de/staticfx-mods/create-pipes-n-physics/roadmap">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://track.devinfritz.de/embed/staticfx-mods/create-pipes-n-physics/roadmap.svg?theme=dark">
    <img alt="Pipes'n Physics Roadmap" src="https://track.devinfritz.de/embed/staticfx-mods/create-pipes-n-physics/roadmap.svg">
  </picture>
</a>

A Create mod addon for pipes and physics. Built with **Java** and **NeoForge 1.21.1**.

## For developers — depending on the API

Pipes'n Physics exposes a small public API under `de.devin.pipesnphysics.api` so other mods
can define how their fluids behave in the network (centrifuge separations, handler roles).

### 1. Add the dependency (compile-time)

The API is resolvable from JitPack — no CurseForge/Modrinth release needed:

```gradle
repositories {
    maven { url 'https://jitpack.io' }
}
dependencies {
    // any tag, branch, or commit as the version
    compileOnly 'com.github.StaticFX:create-pipes-n-physics:v2.3.0'
}
```

### 2. Declare the dependency (runtime)

Add an entry to **your** `src/main/resources/META-INF/neoforge.mods.toml` so the loader knows
Pipes'n Physics must be present. Use `required` if your integration is essential, or `optional`
if it is a bonus:

```toml
[[dependencies.yourmodid]]
    modId = "pipesnphysics"
    type = "required"          # or "optional"
    versionRange = "[2.3.0,)"
    ordering = "AFTER"          # load Pipes'n Physics before you
    side = "BOTH"
```

If you declare it **optional**, guard your API calls so the classes only load when the mod is
present — otherwise a missing dependency throws `NoClassDefFoundError`:

```java
if (ModList.get().isLoaded("pipesnphysics")) {
    PnpCompat.register(); // a SEPARATE class; only it references de.devin.pipesnphysics.api.*
}
```

### 3. Use the API (from your mod's setup)

**Centrifuge — dynamic separation** (`CentrifugeApi`): a hook that reads the fluid (including its
data components) and returns the components it splits into.

```java
CentrifugeApi.registerSeparator(input -> {
    if (input.getFluid() != MyFluids.MIXED.get()) return null;
    return new CentrifugeRecipe(
        new FluidStack(MyFluids.MIXED.get(), 20),                       // consumed per operation
        List.of(new FluidStack(MyFluids.PART_A.get(), 10),              // outputs, densest first
                new FluidStack(MyFluids.PART_B.get(), 10)));
});
```

**Centrifuge — static separation** (datapack): drop a file in
`data/<namespace>/centrifuging/*.json` — no code required:

```json
{
  "input":   { "id": "yourmod:mixed", "amount": 20 },
  "outputs": [ { "id": "yourmod:part_a", "amount": 10 },
               { "id": "yourmod:part_b", "amount": 10 } ]
}
```

**Handler roles** (`FluidHandlerApi`): tell the engine how to treat your fluid-holding block
— `RESERVOIR` (a tank), `RELAY`, `CONDUIT`, `SINK_ONLY`, or `IGNORE`. Call after your blocks
are registered.

```java
FluidHandlerApi.setRole(MyBlocks.FLUID_DRUM.get(), FluidHandlerRole.RESERVOIR);
```

## License

This template is provided under the [MIT License](LICENSE). Your mod built from this template can use any license you choose.
