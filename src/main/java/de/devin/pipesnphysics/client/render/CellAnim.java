package de.devin.pipesnphysics.client.render;

import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.engine.store.PipeStore;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * One pipe cell's client animation state, advanced once per frame from the synced content and
 * flow stamp. Everything that smooths the raw 20 tps sync lives here:
 *
 * The scroll phase is INTEGRATED (phase += speed · dt) with the speed eased toward the stamped
 * rate, so a rate re-sync or a stopping flow glides instead of teleporting the texture (the old
 * absolute-time modulo jumped on every speed change). The flow direction is held
 * {@link #HOLD_TICKS} past a cleared stamp, so a solve that moves fluid in bursts does not flip
 * the cell between flowing and resting every burst.
 *
 * The fill style tracks the plug-flow episode: fluid that reached a DRY cell draws as an
 * advancing front clipped along the flow axis (FILL_FRONT, anchored at the inbound side), a FULL
 * cell that starts draining as a receding one (DRAIN_FRONT, anchored outbound — the gap opens
 * where the fluid left from, so a draining gas no longer collapses to a top band), and standing
 * fluid keeps its waterline. An episode only ever STARTS on a change that arrives WITH a live
 * flow stamp — the held direction bridges an in-progress episode across bursty stamp gaps, but
 * a stampless change is the settle redistributing a stopped flow and draws as a waterline move
 * (plug-replaying those made every stop "flicker and visually recharge"). Both fronts evolve
 * continuously in either direction; an episode ends
 * when the SYNCED content fills the cell, the flow genuinely stops, or the synced amount stops
 * changing for {@link #STALL_TICKS} — the plug moved past and the cell now carries a steady
 * partial depth (depth-gated flow), which draws as a shallow scrolling stream, not a frozen
 * plug stub — never on extrapolation overshoot. A cell whose content vanishes fades out from its
 * last shown fraction over {@link #FADE_TICKS}, receding along its episode's shape.
 */
final class CellAnim {
    /** Ticks a cleared flow stamp keeps the direction (and a front episode) alive — bridges bursty solves. */
    private static final float HOLD_TICKS = 6f;
    /** Ticks of unchanged synced content after which a front episode has stalled into a waterline. */
    private static final float STALL_TICKS = 6f;
    /** Time constant (ticks) for easing the scroll speed toward the stamped rate. */
    private static final float EASE_TICKS = 3f;
    /** Ticks over which a just-emptied cell's fill recedes to zero instead of popping. */
    static final float FADE_TICKS = 6f;
    /** Scroll speed bounds (blocks/sec); the scroll runs at the synced advance rate, capped. */
    private static final float MIN_SCROLL = 0.4f;
    private static final float MAX_SCROLL = 4f;

    private enum Style { WATERLINE, FILL_FRONT, DRAIN_FRONT }

    private FluidStack fluid = FluidStack.EMPTY;
    private Style style = Style.WATERLINE;
    private Direction dir;
    private boolean stampLive;
    private float dirLostTick = -1f;
    private int amount = -1;
    private float syncTick;
    private float rate;
    private float speed;
    private float phase;
    private float fadeStart = -1f;
    private float fadeFrom;
    private float displayFrac;
    private float lastFrame = Float.NaN;
    float lastSeen;

    void advance(FluidStack content, int flowData, int capacity, float now) {
        float dt = Float.isNaN(lastFrame) ? 0f : now - lastFrame;
        lastFrame = now;
        lastSeen = now;
        trackFlow(flowData, dt, now);
        if (content.isEmpty()) fadeOut(now);
        else trackContent(content, capacity, now);
    }

    boolean visible() {
        return displayFrac > 0f && !fluid.isEmpty();
    }

    /** Whether the record carries no fluid and no held flow — safe to drop. */
    boolean idle() {
        return fluid.isEmpty() && dir == null;
    }

    FluidStack fluid() {
        return fluid;
    }

    float frac() {
        return displayFrac;
    }

    /** The held flow direction (null at rest) — orients the front clip. */
    Direction dir() {
        return dir;
    }

    /** The integrated texture scroll offset, always in [0,1). */
    float phase() {
        return phase;
    }

    /** Whether the cell draws as a travelling plug clipped along the flow axis this frame. */
    boolean frontClip() {
        return style != Style.WATERLINE && dir != null && displayFrac < 1f;
    }

    /** A receding plug anchors at the outbound side (the gap opens where the fluid left from). */
    boolean anchorsDownstream() {
        return style == Style.DRAIN_FRONT;
    }

    /** Ease the scroll toward the stamped rate, integrate the phase, hold a flickering direction. */
    private void trackFlow(int flowData, float dt, float now) {
        Direction stamped = PipeStore.flowDirection(flowData);
        stampLive = stamped != null;
        if (stamped != null) {
            dir = stamped;
            dirLostTick = -1f;
            rate = PipeStore.flowRate(flowData);
        } else if (dir != null) {
            if (dirLostTick < 0f) dirLostTick = now;
            rate = 0f;
            if (fadeStart < 0f && now - dirLostTick > HOLD_TICKS) {
                dir = null;
                style = Style.WATERLINE;
            }
        }
        float target = 0f;
        if (stamped != null && rate > 0f) {
            target = Math.clamp(rate * 20f, MIN_SCROLL, MAX_SCROLL)
                    * PipesNPhysicsConfig.PIPE_LEVEL_FLOW_SPEED.get().floatValue();
            // Scroll WITH the fluid: toward the downstream face.
            if (stamped.getAxisDirection() == Direction.AxisDirection.POSITIVE) target = -target;
        }
        speed += (target - speed) * Math.min(1f, dt / EASE_TICKS);
        phase = Mth.positiveModulo(phase + speed * dt / 20f, 1f);
    }

    /** Content vanished: recede the last shown fill over {@link #FADE_TICKS} in the episode's shape. */
    private void fadeOut(float now) {
        if (fluid.isEmpty()) return;
        if (fadeStart < 0f) {
            fadeStart = now;
            fadeFrom = displayFrac;
            amount = 0;
        }
        float elapsed = now - fadeStart;
        displayFrac = elapsed >= FADE_TICKS ? 0f : fadeFrom * (1f - elapsed / FADE_TICKS);
        if (displayFrac <= 0f) {
            fluid = FluidStack.EMPTY;
            style = Style.WATERLINE;
            dir = null;
        }
    }

    /** Mirror the synced content: advance the episode style, extrapolate the shown fraction. */
    private void trackContent(FluidStack content, int capacity, float now) {
        int synced = content.getAmount();
        boolean firstSight = amount < 0;
        boolean wasEmpty = amount == 0;
        boolean wasFull = !firstSight && amount >= capacity;
        fadeStart = -1f;
        fluid = content;
        // A front EPISODE only ever STARTS on a live-stamped change: the direction HOLD bridges
        // an episode already in progress across a bursty solve's stamp gaps, but a content change
        // arriving WITHOUT a stamp is the settle redistributing a stopped flow — drawing it as a
        // plug against the stale direction replayed the charge animation on every stop (cells
        // "flicker around and visually recharge" until the run settles). Those changes are
        // waterline moves.
        if (dir == null) style = Style.WATERLINE;
        else if (firstSight) style = stampLive && synced < capacity ? Style.FILL_FRONT : Style.WATERLINE;
        else if (wasEmpty) style = stampLive ? Style.FILL_FRONT : Style.WATERLINE;
        else if (wasFull && synced < amount) style = stampLive ? Style.DRAIN_FRONT : Style.WATERLINE;
        if (synced >= capacity) style = Style.WATERLINE;
        if (amount != synced) {
            amount = synced;
            syncTick = now;
        }
        // A front whose synced amount stopped changing has stalled: the plug moved past and this
        // cell holds its steady flow depth — a waterline, or the stub would freeze mid-cell.
        if (style != Style.WATERLINE && now - syncTick > STALL_TICKS) style = Style.WATERLINE;
        float frac = Math.min(1f, synced / (float) capacity);
        float drift = rate > 0f ? rate * (now - syncTick) : 0f;
        displayFrac = switch (style) {
            case FILL_FRONT -> Math.min(1f, frac + drift);
            case DRAIN_FRONT -> Math.max(0f, frac - drift);
            case WATERLINE -> frac;
        };
    }
}
