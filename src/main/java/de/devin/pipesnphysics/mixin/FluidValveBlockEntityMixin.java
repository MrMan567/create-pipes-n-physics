package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.fluids.pipes.valve.FluidValveBlock;
import com.simibubi.create.content.fluids.pipes.valve.FluidValveBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.CenteredSideValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.client.GoggleText;
import de.devin.pipesnphysics.client.PipeStatusText;
import de.devin.pipesnphysics.engine.EngineTickHandler;
import de.devin.pipesnphysics.engine.net.PipeStatusClient;
import de.devin.pipesnphysics.engine.net.PipeStatusPayload;
import de.devin.pipesnphysics.engine.valve.ValveThrottle;
import net.createmod.catnip.animation.LerpedFloat;
import net.createmod.catnip.lang.LangBuilder;
import net.createmod.catnip.lang.LangNumberFormat;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Gives Create's fluid valve a fine-grained throttle: a 0-90 degree opening the valve passes
 * proportionally (90 = fully open). It is opened/closed THREE ways, all landing on the same angle:
 * raw SHAFT ROTATION cranks it (positive speed opens, negative closes, idle holds — Create's own
 * "spin to open", integrated onto the 0-90 scale so a motor or gearshift drives it like any other
 * kinetic block); a Valve Handle adds its precise set angle ({@link ValveHandleBlockEntityMixin}
 * via {@code adjustThrottle} — the handle's kinetic burst is a fixed chunk, NOT its set angle, so
 * we apply its INTENT and briefly suppress the shaft integration so the crank is not counted
 * twice); and a scroll-value box on the side faces sets it directly. The handle visual tracks the
 * angle, the solver reads it through {@link ValveThrottle} to scale the run's conductance, and the
 * goggle shows the throughput. Inert when the engine or the throttle feature is off in config.
 */
@Mixin(value = FluidValveBlockEntity.class, remap = false)
public abstract class FluidValveBlockEntityMixin extends KineticBlockEntity implements ValveThrottle {
    @Unique
    private static final int FULL_OPEN_DEGREES = 90;
    /**
     * Ticks after a Valve Handle applies its precise set angle during which raw shaft rotation is
     * IGNORED. The handle also spins the valve's shaft (a fixed ~10-tick kinetic burst), so without
     * this the one crank would land twice — once as intent, once integrated from the burst.
     */
    @Unique
    private static final int HANDLE_CRANK_COOLDOWN_TICKS = 12;
    @Unique
    private ScrollValueBehaviour pipesnphysics$throttle;
    /** Fractional-degree carry, so a slow shaft still cranks the angle one whole degree at a time. */
    @Unique
    private double pipesnphysics$shaftCarry;
    /** Countdown of the post-handle suppression window ({@link #HANDLE_CRANK_COOLDOWN_TICKS}). */
    @Unique
    private int pipesnphysics$handleCrankCooldown;
    @Shadow
    LerpedFloat pointer;

    private FluidValveBlockEntityMixin() { super(null, null, null); }

    @Inject(method = "addBehaviours", at = @At("TAIL"))
    private void pipesnphysics$addThrottle(List<BlockEntityBehaviour> behaviours, CallbackInfo ci) {
        ScrollValueBehaviour throttle = new ScrollValueBehaviour(
                Component.translatable("pipesnphysics.gui.valve.throttle"),
                (SmartBlockEntity) (Object) this,
                new CenteredSideValueBoxTransform((state, side) -> {
                    if (!(state.getBlock() instanceof FluidValveBlock)) return false;
                    Axis shaft = state.getValue(FluidValveBlock.FACING).getAxis();
                    return side.getAxis() != shaft && side.getAxis() != FluidValveBlock.getPipeAxis(state);
                }))
                .between(0, FULL_OPEN_DEGREES)
                .withFormatter(angle -> angle + "°")
                .withCallback(angle -> {
                    pipesnphysics$wakeNetwork();
                    pipesnphysics$aimPointer();
                })
                .onlyActiveWhen(() -> PipesNPhysicsConfig.ENABLE_VALVE_THROTTLE.get());
        throttle.value = FULL_OPEN_DEGREES;
        pipesnphysics$throttle = throttle;
        behaviours.add(throttle);
    }

    /**
     * A valve saved before this feature has no {@code "ScrollValue"} tag, and Create's
     * {@link ScrollValueBehaviour#read} reads an absent key as 0 — which would load every
     * existing valve fully shut. Re-assert the open default whenever the tag lacks the key
     * (a synced packet always carries it, so the client is unaffected).
     */
    @Inject(method = "read", at = @At("TAIL"))
    private void pipesnphysics$keepThrottleDefault(CompoundTag tag, HolderLookup.Provider registries,
                                                   boolean clientPacket, CallbackInfo ci) {
        if (pipesnphysics$throttle != null && !tag.contains("ScrollValue")) {
            pipesnphysics$throttle.value = FULL_OPEN_DEGREES;
        }
    }

    @Override
    public float pipesnphysics$valveThrottle() {
        if (pipesnphysics$throttle == null || !PipesNPhysicsConfig.ENABLE_VALVE_THROTTLE.get()) return 1f;
        double open = pipesnphysics$throttle.getValue() / (double) FULL_OPEN_DEGREES;
        return (float) PipesNPhysicsConfig.VALVE_CHARACTERISTIC.get().factor(open);
    }

    @Override
    public void pipesnphysics$adjustThrottle(int delta) {
        if (pipesnphysics$throttle == null || !PipesNPhysicsConfig.ENABLE_VALVE_THROTTLE.get()) return;
        // A Valve Handle applied its precise intent; ignore the shaft burst it is about to spin
        // through us (set before the value check so a clamped no-op crank still suppresses).
        pipesnphysics$handleCrankCooldown = HANDLE_CRANK_COOLDOWN_TICKS;
        int next = Mth.clamp(pipesnphysics$throttle.getValue() + delta, 0, FULL_OPEN_DEGREES);
        if (next != pipesnphysics$throttle.getValue()) pipesnphysics$throttle.setValue(next); // syncs + re-aims the handle
    }

    /**
     * Crank the throttle from raw shaft rotation each server tick — Create's fluid valve is opened by
     * spinning its shaft, which the throttle rewrite had disconnected (only the handle/scroll moved it).
     * Positive speed opens, negative closes, and a stopped shaft holds the angle (no live-speed gate, so
     * an idle valve never snaps shut). The rate mirrors Create's own pointer chase ({@code |speed|/16/20}
     * of full travel per tick) mapped onto 0-90 degrees, with a fractional carry so a slow shaft still
     * advances. Skipped for {@link #HANDLE_CRANK_COOLDOWN_TICKS} after a handle crank (that burst is
     * already applied as intent), and inert with the feature off (Create's native shaft behaviour runs).
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void pipesnphysics$crankFromShaft(CallbackInfo ci) {
        if (pipesnphysics$throttle == null || level == null || level.isClientSide()) return;
        if (!PipesNPhysicsConfig.ENABLE_VALVE_THROTTLE.get()) return;
        if (pipesnphysics$handleCrankCooldown > 0) {
            pipesnphysics$handleCrankCooldown--;
            return;
        }
        float speed = getSpeed();
        if (speed == 0) {
            pipesnphysics$shaftCarry = 0;
            return;
        }
        double rate = Mth.clamp(Math.abs(speed) / 16.0 / 20.0, 0, 1) * FULL_OPEN_DEGREES;
        pipesnphysics$shaftCarry += Math.signum(speed) * rate;
        int whole = (int) pipesnphysics$shaftCarry;
        if (whole == 0) return;
        pipesnphysics$shaftCarry -= whole;
        int next = Mth.clamp(pipesnphysics$throttle.getValue() + whole, 0, FULL_OPEN_DEGREES);
        if (next != pipesnphysics$throttle.getValue()) {
            pipesnphysics$throttle.setValue(next); // the callback wakes the network + re-aims the handle
        }
    }

    @Unique
    private void pipesnphysics$wakeNetwork() {
        if (level != null && !level.isClientSide()) EngineTickHandler.markChanged(level, worldPosition);
    }

    /** Aim the handle at the current opening: the pointer chases the throttle fraction, so the
     *  valve's handle sits at exactly however far it has been cranked. Inert when the feature is off. */
    @Unique
    private void pipesnphysics$aimPointer() {
        if (pipesnphysics$throttle == null || !PipesNPhysicsConfig.ENABLE_VALVE_THROTTLE.get()) return;
        float target = pipesnphysics$throttle.getValue() / (float) FULL_OPEN_DEGREES;
        float chaseSpeed = Math.max(0.05f, Mth.clamp(Math.abs(getSpeed()) / 16f / 20f, 0f, 1f));
        pointer.chase(target, chaseSpeed, LerpedFloat.Chaser.LINEAR);
        if (level != null && !level.isClientSide()) sendData();
    }

    @Inject(method = "onSpeedChanged", at = @At("TAIL"))
    private void pipesnphysics$retargetOnSpeed(float previousSpeed, CallbackInfo ci) {
        pipesnphysics$aimPointer(); // hold the handle at the throttle even after the shaft stops
    }

    /**
     * The throttle IS the valve's open position now, so feed Create's open/close checks a gate
     * derived from it: OPEN whenever the angle is above 0, shut at 0. Create otherwise flips
     * {@code ENABLED} only at a fully-turned (pointer == 1) handle, which a partially cranked
     * valve never reaches. Falls back to the real pointer when the feature is off.
     */
    @Redirect(method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/createmod/catnip/animation/LerpedFloat;getValue()F"))
    private float pipesnphysics$gateEnabled(LerpedFloat instance) {
        if (pipesnphysics$throttle == null || !PipesNPhysicsConfig.ENABLE_VALVE_THROTTLE.get()) {
            return instance.getValue();
        }
        return pipesnphysics$throttle.getValue() > 0 ? 1f : 0f;
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        boolean base = super.addToGoggleTooltip(tooltip, isPlayerSneaking);
        if (!PipesNPhysicsConfig.SHOW_PIPE_GOGGLE_INFO.get()) return base;
        if (!PipesNPhysicsConfig.ENABLE_ENGINE.get()) return base;
        if (!PipesNPhysicsConfig.ENABLE_VALVE_THROTTLE.get()) return base;
        Level world = level;
        if (world == null || !world.isClientSide() || pipesnphysics$throttle == null) return base;

        int angle = pipesnphysics$throttle.getValue();

        GoggleText.lang("gui.goggles.valve_stats").style(ChatFormatting.WHITE).forGoggles(tooltip);

        // The opening bar is the share of flow the valve passes — the characteristic-curved factor,
        // NOT the raw angle, so a nonlinear curve reads honestly (the degrees show on the scroll box).
        // Only meaningful when it actually passes; a closed (0-degree) valve shows just the reason.
        if (angle > 0) {
            float share = pipesnphysics$valveThrottle();
            int percent = Math.round(100f * share);
            LangBuilder opening = GoggleText.lang("gui.goggles.valve_opening")
                    .style(ChatFormatting.GRAY)
                    .add(GoggleText.text(percent + "%").style(ChatFormatting.WHITE))
                    .add(GoggleText.text("  ").style(ChatFormatting.DARK_GRAY));
            GoggleText.appendBars(opening, Math.round(10f * share), 10);
            opening.forGoggles(tooltip, 1);
        }

        pipesnphysics$addStateLine(tooltip, world, angle);
        return true;
    }

    /** Why fluid is or isn't moving: cranked shut, the live rate, or the run's real stop. */
    @Unique
    private void pipesnphysics$addStateLine(List<Component> tooltip, Level world, int angle) {
        if (angle == 0) {
            GoggleText.lang("gui.goggles.valve_shut_throttle").style(ChatFormatting.GOLD).forGoggles(tooltip, 1);
            return;
        }
        long now = world.getGameTime();
        PipeStatusClient.requestIfStale(worldPosition, now);
        PipeStatusPayload data = PipeStatusClient.current(worldPosition, now);
        if (data == null) return;
        if (data.status() == PipeStatusPayload.STATUS_FLOWING) {
            LangBuilder line = GoggleText.lang("gui.goggles.flow")
                    .style(ChatFormatting.GRAY)
                    .add(GoggleText.text(LangNumberFormat.format(data.mbPerTick())).style(ChatFormatting.WHITE))
                    .add(GoggleText.lang("gui.goggles.mb_per_tick").style(ChatFormatting.DARK_GRAY));
            if (!data.fluid().isEmpty()) {
                line.add(GoggleText.text("(" + data.fluid().getHoverName().getString() + ")")
                        .style(ChatFormatting.AQUA));
            }
            line.forGoggles(tooltip, 1);
            return;
        }
        // Open and unthrottled but nothing moving — show the run's actual stop (sink full,
        // a shut valve elsewhere, settled levels) rather than a misleading "nothing to move".
        GoggleText.lang(PipeStatusText.reasonKey(data)).style(PipeStatusText.color(data.status())).forGoggles(tooltip, 1);
    }
}
