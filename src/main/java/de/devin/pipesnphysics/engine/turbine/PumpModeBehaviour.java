package de.devin.pipesnphysics.engine.turbine;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollOptionBehaviour;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/**
 * The Mechanical Pump's role dial: PUMP (the default) or TURBINE. A {@link ScrollOptionBehaviour}
 * over {@link PumpMode}, so the value box shows the mode's icon rather than text.
 *
 * It carries its OWN {@link BehaviourType} and NBT key even though the pump has no other scroll
 * behaviour today: SmartBlockEntity keys behaviours by type, every stock ScrollValueBehaviour
 * writes the shared "ScrollValue" tag, and {@code ValueSettingsPacket} routes a board selection by
 * {@code netId} — all three collide silently the moment a second behaviour appears (the fluid
 * valve learned this the hard way, see ValveDirectionBehaviour).
 */
public class PumpModeBehaviour extends ScrollOptionBehaviour<PumpMode> {
    public static final BehaviourType<PumpModeBehaviour> TYPE = new BehaviourType<>();
    private static final String NBT_KEY = "PumpMode";

    public PumpModeBehaviour(Component label, SmartBlockEntity be, ValueBoxTransform slot) {
        super(PumpMode.class, label, be, slot);
    }

    /** The dialed role. Resolving AUTO into an actual role needs the pump's kinetic state. */
    public PumpMode mode() {
        int value = getValue();
        PumpMode[] modes = PumpMode.values();
        return value >= 0 && value < modes.length ? modes[value] : PumpMode.PUMP;
    }

    @Override
    public void write(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket) {
        nbt.putInt(NBT_KEY, value);
    }

    @Override
    public void read(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket) {
        value = nbt.getInt(NBT_KEY); // absent reads 0 = PUMP, the correct default for old saves
    }

    @Override
    public BehaviourType<?> getType() {
        return TYPE;
    }

    /** Distinct from every behaviour Create puts on a pump; see the class doc. */
    @Override
    public int netId() {
        return 14;
    }
}
