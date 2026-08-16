package de.devin.pipesnphysics.engine.solve;

import de.devin.pipesnphysics.engine.solve.NetworkSolver.BranchSpec;
import de.devin.pipesnphysics.engine.solve.NetworkSolver.NodeSpec;
import de.devin.pipesnphysics.engine.solve.NetworkSolver.Result;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkSolverTest {
    private static final double SUCTION_LIMIT = 8;
    private static final double TANK_CAPACITANCE = 8000;

    private static Result step(List<NodeSpec> nodes, List<BranchSpec> branches) {
        return NetworkSolver.solve(nodes, branches, 1, SUCTION_LIMIT);
    }

    /** Apply one tick's net inflow back onto reservoir heads, like the engine does. */
    private static List<NodeSpec> advance(List<NodeSpec> nodes, Result result) {
        List<NodeSpec> next = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            NodeSpec node = nodes.get(i);
            double newHead = node.capacitance() > 0
                    ? node.head() + result.netInflow()[i] / node.capacitance()
                    : node.head();
            next.add(new NodeSpec(node.capacitance(), newHead));
        }
        return next;
    }

    @Test
    void twoTanksEqualizeMonotonicallyWithoutOscillation() {
        List<NodeSpec> nodes = List.of(
                new NodeSpec(TANK_CAPACITANCE, 10),
                new NodeSpec(TANK_CAPACITANCE, 6));
        List<BranchSpec> branches = List.of(BranchSpec.passive(0, 1, 50));

        double previousGap = 4;
        for (int tick = 0; tick < 2000; tick++) {
            Result result = step(nodes, branches);
            assertTrue(result.flows()[0] >= -1e-9, "flow must never reverse during equalization");
            nodes = advance(nodes, result);

            double gap = nodes.get(0).head() - nodes.get(1).head();
            assertTrue(gap >= -1e-9, "higher tank must never drop below lower tank (overshoot)");
            assertTrue(gap <= previousGap + 1e-9, "gap must shrink monotonically");
            previousGap = gap;
        }
        assertEquals(0, previousGap, 1e-3, "tanks should settle at equal surfaces");
    }

    @Test
    void unequalFootprintsSettleAtEqualSurfacesNotEqualVolumes() {
        double smallCapacitance = 8000;
        double bigCapacitance = 32000;
        List<NodeSpec> nodes = List.of(
                new NodeSpec(smallCapacitance, 12),
                new NodeSpec(bigCapacitance, 4));
        List<BranchSpec> branches = List.of(BranchSpec.passive(0, 1, 100));

        double initialVolume = smallCapacitance * 12 + bigCapacitance * 4;
        for (int tick = 0; tick < 5000; tick++) {
            nodes = advance(nodes, step(nodes, branches));
        }

        assertEquals(nodes.get(0).head(), nodes.get(1).head(), 1e-3, "surfaces equalize");
        double finalVolume = smallCapacitance * nodes.get(0).head() + bigCapacitance * nodes.get(1).head();
        assertEquals(initialVolume, finalVolume, 1e-6, "volume conserved");
        double movedFromSmall = smallCapacitance * (12 - nodes.get(0).head());
        double movedToBig = bigCapacitance * (nodes.get(1).head() - 4);
        assertEquals(movedFromSmall, movedToBig, 1e-6);
        assertTrue(Math.abs(nodes.get(0).head() - 12) > 1, "small tank actually drained");
    }

    @Test
    void gravityTowerFlowsDownhillOnly() {
        List<NodeSpec> nodes = List.of(
                new NodeSpec(TANK_CAPACITANCE, 60),
                new NodeSpec(TANK_CAPACITANCE, 10));
        List<BranchSpec> branches = List.of(BranchSpec.passive(0, 1, 10));

        Result result = step(nodes, branches);
        assertTrue(result.flows()[0] > 0, "fluid flows from the tower down");

        List<NodeSpec> reversed = List.of(nodes.get(1), nodes.get(0));
        Result reversedResult = step(reversed, branches);
        assertTrue(reversedResult.flows()[0] < 0, "direction follows the head, not the node order");
    }

    /**
     * Lighter-than-air fluids invert gravity at FULL strength: {@link NetworkSolver#surfaceHead}
     * mirrors the liquid model exactly (elevation coefficient −1 instead of +1), independent of
     * how light the fluid is. The buoyant lift across an elevation gap equals the gap itself, so
     * gas climbs as hard as a liquid sinks. (Regression: a relative-density scale once floored
     * buoyancy near 1% of gravity, so ordinary gases equalized by volume like a liquid.) The gas
     * head anchors at the column TOP (minus the interface): two vessels topping out LEVEL read
     * equal heads at equal fills regardless of their heights — the base anchor gave a taller tank
     * a full block of phantom priority and unequal-height pairs churned instead of equalizing.
     */
    @Test
    void surfaceHeadInvertsGravityForGasAtFullStrength() {
        double low = NetworkSolver.surfaceHead(64, 68, 2, true);
        double high = NetworkSolver.surfaceHead(84, 88, 2, true);
        assertTrue(low > high, "a lower gas column outranks a higher one, so gas flows upward");
        assertEquals(20, low - high, 1e-9, "buoyant lift equals the full 20-block elevation gap");
        assertEquals(66, NetworkSolver.surfaceHead(64, 68, 2, false), 1e-9, "liquids stack downward unchanged");
        assertEquals(NetworkSolver.surfaceHead(57, 60, 1.5, true),
                NetworkSolver.surfaceHead(56, 60, 1.5, true), 1e-9,
                "equal tops + equal fills = equal gas heads, whatever the column heights");
    }

    /**
     * The buoyancy mirror end to end: for identical tanks at identical fills but different
     * elevations, a liquid sinks toward the LOWER tank while a gas rises toward the HIGHER one —
     * the same network solved in opposite directions purely from the fluid's density sign.
     */
    @Test
    void buoyancyTowerFlowsUphillMirroringGravity() {
        double lowBaseY = 64, highBaseY = 84, height = 4, fill = 2;
        List<BranchSpec> branches = List.of(BranchSpec.passive(0, 1, 40));

        List<NodeSpec> liquid = List.of(
                new NodeSpec(TANK_CAPACITANCE, NetworkSolver.surfaceHead(lowBaseY, lowBaseY + height, fill, false)),
                new NodeSpec(TANK_CAPACITANCE, NetworkSolver.surfaceHead(highBaseY, highBaseY + height, fill, false)));
        assertTrue(step(liquid, branches).flows()[0] < 0, "liquid sinks toward the lower tank");

        List<NodeSpec> gas = List.of(
                new NodeSpec(TANK_CAPACITANCE, NetworkSolver.surfaceHead(lowBaseY, lowBaseY + height, fill, true)),
                new NodeSpec(TANK_CAPACITANCE, NetworkSolver.surfaceHead(highBaseY, highBaseY + height, fill, true)));
        assertTrue(step(gas, branches).flows()[0] > 0, "gas rises toward the higher tank");
    }

    @Test
    void pumpPushesUntilHeadDifferenceMatchesPumpHead() {
        double pumpHead = 16;
        List<NodeSpec> nodes = List.of(
                new NodeSpec(TANK_CAPACITANCE, 5),
                new NodeSpec(0, 0),
                new NodeSpec(TANK_CAPACITANCE, 5));
        List<BranchSpec> branches = List.of(
                new BranchSpec(0, 1, 40, 0, +1, Double.NaN, 0),
                new BranchSpec(1, 2, 40, pumpHead, +1, Double.NaN, 0));

        double previousFlow = Double.MAX_VALUE;
        for (int tick = 0; tick < 8000; tick++) {
            Result result = step(nodes, branches);
            assertTrue(result.flows()[1] >= -1e-9, "pump branch never flows backwards");
            assertTrue(result.flows()[1] <= previousFlow + 1e-9, "flow decays monotonically toward equilibrium");
            previousFlow = result.flows()[1];
            nodes = advance(nodes, result);
        }

        double finalGap = nodes.get(2).head() - nodes.get(0).head();
        assertEquals(pumpHead, finalGap, 0.01, "pump holds exactly its head worth of surface difference");
    }

    @Test
    void weakPumpAgainstTallColumnIsBlockedByCheckValveNotReversed() {
        List<NodeSpec> nodes = List.of(
                new NodeSpec(TANK_CAPACITANCE, 5),
                new NodeSpec(0, 0),
                new NodeSpec(TANK_CAPACITANCE, 40));
        List<BranchSpec> branches = List.of(
                new BranchSpec(0, 1, 40, 0, +1, Double.NaN, 0),
                new BranchSpec(1, 2, 40, 10, +1, Double.NaN, 0));

        Result result = step(nodes, branches);
        assertEquals(0, result.flows()[0], 1e-9, "check valve blocks back-flow");
        assertEquals(0, result.flows()[1], 1e-9, "check valve blocks back-flow");
        assertFalse(result.active()[1], "overpowered pump branch is deactivated");
    }

    /**
     * A turbine is a pump with the sign flipped: it takes its rated head OUT of the line. So a fall
     * shorter than the rating turns nothing (and reads as backflow-blocked, which the probe words
     * as "the fall is too small"), a longer one flows at what is left over, and the flank check
     * valve keeps it one-way exactly as a pump's does.
     */
    @Test
    void turbineOnlyTurnsOnceTheFallExceedsItsRating() {
        double ratedHead = 2;
        double conductance = 32;

        List<NodeSpec> shortFall = List.of(
                new NodeSpec(TANK_CAPACITANCE, 6),
                new NodeSpec(TANK_CAPACITANCE, 5));
        List<BranchSpec> branches = List.of(
                new BranchSpec(0, 1, conductance, -ratedHead, +1, Double.NaN, 0));

        Result blocked = step(shortFall, branches);
        assertEquals(0, blocked.flows()[0], 1e-9, "a 1-block fall cannot turn a turbine rated for 2");
        assertTrue(blocked.backflowBlocked()[0], "the short fall reads as backflow-blocked, not settled");

        List<NodeSpec> realFall = List.of(
                new NodeSpec(TANK_CAPACITANCE, 10),
                new NodeSpec(TANK_CAPACITANCE, 5));
        Result flowing = step(realFall, branches);
        // Tolerance covers the implicit step's own shrink: the tanks move during the tick, so the
        // solved flow sits just under the instantaneous conductance x head.
        assertEquals(conductance * (5 - ratedHead), flowing.flows()[0], 1.0,
                "the turbine passes what the fall leaves after its own rated head");

        List<NodeSpec> uphill = List.of(
                new NodeSpec(TANK_CAPACITANCE, 5),
                new NodeSpec(TANK_CAPACITANCE, 10));
        assertEquals(0, step(uphill, branches).flows()[0], 1e-9, "a turbine never flows backwards");
    }

    @Test
    void threeTankStarEqualizesThroughJunction() {
        List<NodeSpec> nodes = List.of(
                new NodeSpec(TANK_CAPACITANCE, 10),
                new NodeSpec(TANK_CAPACITANCE, 8),
                new NodeSpec(TANK_CAPACITANCE, 3),
                new NodeSpec(0, 0));
        List<BranchSpec> branches = List.of(
                BranchSpec.passive(0, 3, 60),
                BranchSpec.passive(1, 3, 60),
                BranchSpec.passive(2, 3, 60));

        for (int tick = 0; tick < 4000; tick++) {
            Result result = step(nodes, branches);
            double junctionBalance = result.netInflow()[3];
            assertEquals(0, junctionBalance, 1e-9, "junction stores no fluid");
            nodes = advance(nodes, result);
        }

        assertEquals(7, nodes.get(0).head(), 1e-3);
        assertEquals(7, nodes.get(1).head(), 1e-3);
        assertEquals(7, nodes.get(2).head(), 1e-3);
    }

    @Test
    void handlerFreeLoopCarriesNoFlow() {
        List<NodeSpec> nodes = List.of(
                new NodeSpec(0, 0), new NodeSpec(0, 0), new NodeSpec(0, 0));
        List<BranchSpec> branches = List.of(
                BranchSpec.passive(0, 1, 40),
                BranchSpec.passive(1, 2, 40),
                BranchSpec.passive(2, 0, 40));

        Result result = step(nodes, branches);
        for (int e = 0; e < 3; e++) {
            assertEquals(0, result.flows()[e], 0.0, "no reservoir, no flow");
            assertFalse(result.active()[e]);
        }
        for (double head : result.heads()) {
            assertFalse(Double.isNaN(head), "heads stay finite");
        }
    }

    @Test
    void crestAboveSuctionLimitStopsTheSiphonLowerCrestRuns() {
        List<NodeSpec> tanks = List.of(
                new NodeSpec(TANK_CAPACITANCE, 60),
                new NodeSpec(TANK_CAPACITANCE, 50));

        BranchSpec tooHigh = new BranchSpec(0, 1, 40, 0, 0, 75, 0.5);
        Result blocked = step(tanks, List.of(tooHigh));
        assertEquals(0, blocked.flows()[0], 1e-9, "crest far above the heads cavitates");

        BranchSpec siphonable = new BranchSpec(0, 1, 40, 0, 0, 56, 0.5);
        Result flowing = step(tanks, List.of(siphonable));
        assertTrue(flowing.flows()[0] > 0, "a modest crest is siphoned over");
    }

    /**
     * Suction can HOLD a column over a crest, never CREATE one: a DRY crest above the reachable
     * potential must gate the branch (nothing pushes fluid up an air-filled leg), while the same
     * geometry with a primed (wet) crest siphons. A pump whose boost REACHES the crest still
     * primes a dry line — that is how a siphon gets established in the first place.
     */
    @Test
    void dryCrestDoesNotSelfPrimeButWetOrPumpedOnesFlow() {
        List<NodeSpec> tanks = List.of(
                new NodeSpec(TANK_CAPACITANCE, 60),
                new NodeSpec(TANK_CAPACITANCE, 50));

        BranchSpec dry = new BranchSpec(0, 1, 40, 0, 0, 62, 0.5, false);
        Result gated = step(tanks, List.of(dry));
        assertEquals(0, gated.flows()[0], 1e-9, "a dry crest above the surface never self-primes");
        assertTrue(gated.crestBlocked()[0], "the gate reports the air break");

        BranchSpec wet = new BranchSpec(0, 1, 40, 0, 0, 62, 0.5, true);
        assertTrue(step(tanks, List.of(wet)).flows()[0] > 0, "the primed column siphons");

        BranchSpec pumped = new BranchSpec(0, 1, 40, 6, +1, 62, 0.5, false);
        assertTrue(step(tanks, List.of(pumped)).flows()[0] > 0,
                "a pump lifting the potential over the crest primes a dry line");

        BranchSpec submerged = new BranchSpec(0, 1, 40, 0, 0, 55, 0.5, false);
        assertTrue(step(tanks, List.of(submerged)).flows()[0] > 0,
                "a crest below the supply surface needs no priming (an ordinary downhill bump)");
    }

    /**
     * The SUCTION-side dual is GRADED by the pump's own strength: its boost exists only on the
     * push flank, so a pump above a low tank establishes through its dry riser on nothing but the
     * share of head it can spend sucking ({@code pumpPullHeadFraction} — a tenth of its lift by
     * default, so a 16 RPM pump gets 0.4 blocks). Deeper than that the line stays an air break
     * until it is primed once, and the allowance can never reach past the suction limit that
     * afterwards SUSTAINS the column.
     */
    @Test
    void drySuctionCrestPrimesOnlyWithinThePumpsPullAllowance() {
        List<NodeSpec> tanks = List.of(
                new NodeSpec(TANK_CAPACITANCE, 60),
                new NodeSpec(TANK_CAPACITANCE, 50));

        // A suction flank carries NO emf; the riser's floor sits one block above the supply, dry.
        BranchSpec unpumped = new BranchSpec(0, 1, 40, 0, 0, 61.5, 61, 0.5, false);
        Result gated = step(tanks, List.of(unpumped));
        assertEquals(0, gated.flows()[0], 1e-9, "an unpumped dry riser never self-primes");
        assertTrue(gated.crestBlocked()[0], "the strict gate reports the air break");

        // 16 RPM at the stock 0.25 blocks/RPM and a tenth of it for pulling: 0.4 blocks, well
        // short of the block it would have to evacuate here.
        BranchSpec tooWeak = new BranchSpec(0, 1, 40, 0, 0, 61.5, 61, 0.5, false, 0.4);
        Result short0 = step(tanks, List.of(tooWeak));
        assertEquals(0, short0.flows()[0], 1e-9, "a pull allowance short of the crest floor gates");
        assertTrue(short0.crestBlocked()[0], "and it still reads as an air break");

        BranchSpec strongEnough = new BranchSpec(0, 1, 40, 0, 0, 61.5, 61, 0.5, false, 1.2);
        assertTrue(step(tanks, List.of(strongEnough)).flows()[0] > 0,
                "an allowance reaching the crest floor establishes through the dry riser");

        BranchSpec beyondTheLimit = new BranchSpec(0, 1, 40, 0, 0, 70.5, 70, 0.5, false, 20);
        assertEquals(0, step(tanks, List.of(beyondTheLimit)).flows()[0], 1e-9,
                "no allowance establishes past the suction limit");
    }

    /**
     * The self-prime bar is the crest cell's LIP (its floor datum), not its centre: a supply
     * whose own potential reaches the crest cell's lip wets it and pours over by plain gravity
     * (weir flow — a tank draining through a run at its own level). Only a floor ABOVE the supply
     * is a true air break. Regression: hard-gating the floor-to-centre band let one idle tick's
     * settle drain a crest cell and permanently lock a working gravity run out.
     */
    @Test
    void dryCrestFlowsOnceTheSupplyReachesItsFloor() {
        // Crest cell centred at 60.5 with its lip (the connection aperture bottom) at 60.375 —
        // the side cell of a tank whose base block sits at 60. The far-side interpolation drags
        // the head AT the crest below the supply either way; only supply-vs-floor decides.
        BranchSpec dryAtTankLevel = new BranchSpec(0, 1, 40, 0, 0, 60.5, 60.375, 0.2, false);

        List<NodeSpec> supplyAtTheLip = List.of(
                new NodeSpec(TANK_CAPACITANCE, 60.45),
                new NodeSpec(TANK_CAPACITANCE, 50));
        assertTrue(step(supplyAtTheLip, List.of(dryAtTankLevel)).flows()[0] > 0,
                "a supply reaching the dry crest's lip pours over it");

        List<NodeSpec> supplyUnderTheLip = List.of(
                new NodeSpec(TANK_CAPACITANCE, 60.3),
                new NodeSpec(TANK_CAPACITANCE, 50));
        Result gated = step(supplyUnderTheLip, List.of(dryAtTankLevel));
        assertEquals(0, gated.flows()[0], 1e-9, "below the crest's lip a dry crest still gates");
        assertTrue(gated.crestBlocked()[0], "the gate reports the air break");
    }

    @Test
    void crestNearTheSuctionLimitTapersInsteadOfCliff() {
        List<NodeSpec> tanks = List.of(
                new NodeSpec(TANK_CAPACITANCE, 60),
                new NodeSpec(TANK_CAPACITANCE, 50));

        double fullFlow = step(tanks, List.of(BranchSpec.passive(0, 1, 40))).flows()[0];
        // Crest 7 blocks above the 60 supply: inside the 8-block suction limit but
        // within the taper band, so a reduced trickle siphons over. Measured against
        // the friction-free supply elevation (not the flow-dragged solved head), which
        // is why a genuine 7-block rise is needed to taper rather than a low crest the
        // friction gradient used to drag below the limit.
        BranchSpec marginal = new BranchSpec(0, 1, 40, 0, 0, 67, 0.5);
        double taperedFlow = step(tanks, List.of(marginal)).flows()[0];

        assertTrue(taperedFlow > 0, "inside the taper band a trickle still flows");
        assertTrue(taperedFlow < fullFlow * 0.9, "near the limit, flow is visibly reduced");
    }

    /**
     * Regression for "spin the pump up and the siphon dies": a strong pump pulling
     * over a modest crest must not gate itself off. At high RPM the suction-side
     * friction drawdown drags the SOLVED junction head far below the supply, but the
     * liquid column's existence depends on the supply ELEVATION and pump lift, not on
     * that flow-rate artifact — so more RPM may never turn a working line off.
     */
    @Test
    void strongPumpDoesNotCavitationGateItsOwnSuctionSide() {
        double suctionConductance = 120.0 / 11;   // a ~10-cell suction run
        double pumpInternalConductance = 4;        // flowPerRpm / headPerRpm
        for (double rpm : new double[]{8, 32, 96, 128, 256}) {
            double pumpHead = rpm * 0.25;          // |RPM| * headPerRpm
            List<NodeSpec> nodes = List.of(
                    new NodeSpec(TANK_CAPACITANCE, 64),  // source tank
                    new NodeSpec(0, 0),                  // pump
                    new NodeSpec(TANK_CAPACITANCE, 50)); // destination tank
            List<BranchSpec> branches = List.of(
                    new BranchSpec(0, 1, suctionConductance, 0, +1, 68, 0.5),  // crest only 4 blocks up
                    new BranchSpec(1, 2, pumpInternalConductance, pumpHead, +1, Double.NaN, 0));

            Result result = step(nodes, branches);
            assertTrue(result.flows()[0] > 0,
                    "suction line must keep flowing at rpm " + rpm
                            + " (crest only 4 blocks above a 64 supply, well inside the suction limit) "
                            + "but flow was " + result.flows()[0]);
        }
    }

    /**
     * The friction-free crest gate must not LEAK a reservoir's head across a crest it
     * cannot itself clear. node0 (60) is walled off by a crest at 75 (> 60 + suction);
     * node1 (30) and node2 (10) sit either side of a crest at 40 that neither can
     * clear. Nothing may flow — in particular node0's head must not propagate past the
     * broken 75 crest to falsely prime the 40 crest and drain node1 into node2.
     */
    @Test
    void brokenCrestDoesNotLeakHeadToPrimeADownstreamCrest() {
        List<NodeSpec> nodes = List.of(
                new NodeSpec(TANK_CAPACITANCE, 60),
                new NodeSpec(TANK_CAPACITANCE, 30),
                new NodeSpec(TANK_CAPACITANCE, 10));
        List<BranchSpec> branches = List.of(
                new BranchSpec(0, 1, 40, 0, 0, 75, 0.5),   // breaks: 75 > 60 + suctionLimit
                new BranchSpec(1, 2, 40, 0, 0, 40, 0.5));  // 40 is > suctionLimit above both 30 and 10

        Result result = step(nodes, branches);
        assertEquals(0, result.flows()[0], 1e-9, "the 75-block crest walls off node0");
        assertEquals(0, result.flows()[1], 1e-9,
                "node0's head must not leak across the broken crest to prime the 40-block crest");
        assertTrue(result.crestBlocked()[1], "the downstream crest must report as broken");
    }

    /**
     * A pump must not be permanently dead-headed by a crest-broken feeder's PHANTOM pressure.
     * node0 (100) feeds the junction through a run cresting at 200 — a genuinely broken siphon.
     * In the pre-crest solve that feeder conducts at full strength and lifts the junction above
     * the pump's reachable head, so the pump's check-valve branch backflow-deactivates. The crest
     * gate then removes the feeder — and the pump, opposed by nothing, must deliver. Regression for
     * the active set not being rebuilt after crest gating (the pre-gate backflow flag stuck on).
     */
    @Test
    void pumpDeliversWhenCrestBrokenFeederNoLongerBackpressuresIt() {
        List<NodeSpec> nodes = List.of(
                new NodeSpec(TANK_CAPACITANCE, 100), // high feeder, walled off by a broken crest
                new NodeSpec(0, 0),                  // junction
                new NodeSpec(TANK_CAPACITANCE, 40),  // the pump's supply
                new NodeSpec(TANK_CAPACITANCE, 20)); // the sink
        List<BranchSpec> branches = List.of(
                new BranchSpec(0, 1, 40, 0, 0, 200, 0.5),        // breaks: 200 >> 100 + suctionLimit
                new BranchSpec(2, 1, 4, 10, +1, Double.NaN, 0),  // pump lifts 10 blocks into the junction
                new BranchSpec(1, 3, 40, 0, 0, Double.NaN, 0));

        Result result = step(nodes, branches);

        assertEquals(0, result.flows()[0], 1e-9, "the broken 200-block crest walls off the high feeder");
        assertTrue(result.crestBlocked()[0], "the feeder's crest must report broken");
        assertTrue(result.flows()[1] > 5,
                "the pump must deliver once the crest-broken feeder no longer backflow-blocks it, "
                        + "but flow was " + result.flows()[1]);
    }

    @Test
    void pumpHeadLiftsTheCrestGate() {
        List<NodeSpec> tanks = List.of(
                new NodeSpec(TANK_CAPACITANCE, 20),
                new NodeSpec(TANK_CAPACITANCE, 10));

        BranchSpec unpowered = new BranchSpec(0, 1, 40, 0, 0, 30, 0.5);
        assertEquals(0, step(tanks, List.of(unpowered)).flows()[0], 1e-9,
                "an unpowered line cannot hold a column 15 blocks above its head");

        BranchSpec pumped = new BranchSpec(0, 1, 40, 20, +1, 30, 0.5);
        assertTrue(step(tanks, List.of(pumped)).flows()[0] > 0,
                "a pump's head raises the pressure profile over the same rise");
    }

    @Test
    void volumeIsConservedOnRandomNetworks() {
        Random random = new Random(42);
        for (int trial = 0; trial < 50; trial++) {
            int reservoirCount = 2 + random.nextInt(6);
            int junctionCount = random.nextInt(6);
            int n = reservoirCount + junctionCount;

            List<NodeSpec> nodes = new ArrayList<>();
            for (int i = 0; i < reservoirCount; i++) {
                nodes.add(new NodeSpec(1000 + random.nextInt(50000), random.nextDouble() * 100));
            }
            for (int i = 0; i < junctionCount; i++) {
                nodes.add(new NodeSpec(0, 0));
            }

            List<BranchSpec> branches = new ArrayList<>();
            for (int i = 1; i < n; i++) {
                branches.add(new BranchSpec(random.nextInt(i), i,
                        1 + random.nextDouble() * 100,
                        random.nextDouble() < 0.3 ? random.nextDouble() * 30 - 10 : 0,
                        random.nextInt(3) - 1,
                        Double.NaN, 0));
            }

            Result result = step(nodes, branches);
            double total = 0;
            for (int i = 0; i < n; i++) {
                if (nodes.get(i).capacitance() > 0) {
                    total += result.netInflow()[i];
                } else {
                    assertEquals(0, result.netInflow()[i], 1e-6,
                            "junctions never store fluid (trial " + trial + ")");
                }
            }
            assertEquals(0, total, 1e-6, "total volume conserved (trial " + trial + ")");
        }
    }

    @Test
    void longRunWithPumpAndJunctionsNeverFlipsFlowDirection() {
        List<NodeSpec> nodes = List.of(
                new NodeSpec(TANK_CAPACITANCE, 12),
                new NodeSpec(0, 0),
                new NodeSpec(0, 0),
                new NodeSpec(24000, 10),
                new NodeSpec(TANK_CAPACITANCE, 2));
        List<BranchSpec> branches = List.of(
                new BranchSpec(0, 1, 30, 0, +1, Double.NaN, 0),
                new BranchSpec(1, 2, 30, 12, +1, Double.NaN, 0),
                BranchSpec.passive(2, 3, 25),
                BranchSpec.passive(2, 4, 25));

        int totalTicks = 5000;
        int[] signFlips = new int[branches.size()];
        int lastFlipTick = -1;
        double[] lastSign = new double[branches.size()];
        for (int tick = 0; tick < totalTicks; tick++) {
            Result result = step(nodes, branches);
            for (int e = 0; e < branches.size(); e++) {
                double sign = Math.abs(result.flows()[e]) < 1e-6 ? 0 : Math.signum(result.flows()[e]);
                if (sign != 0 && lastSign[e] != 0 && sign != lastSign[e]) {
                    signFlips[e]++;
                    lastFlipTick = tick;
                }
                if (sign != 0) lastSign[e] = sign;
            }
            nodes = advance(nodes, result);
        }

        for (int e = 0; e < branches.size(); e++) {
            assertTrue(signFlips[e] <= 1,
                    "branch " + e + " flipped " + signFlips[e] + " times; one regime change is physics, more is oscillation");
        }
        assertTrue(lastFlipTick < totalTicks - 1000,
                "steady state must be quiet, but a flow direction changed at tick " + lastFlipTick);
    }

    /**
     * Regression for the in-game pump deadlock: a strong pump fed through a junction,
     * all pipes at tank level. The crest gate must not let the pump's suction
     * drawdown talk itself into shutting the whole line off (h_pump pinned at
     * tankHead - pumpHead with zero flow everywhere).
     */
    @Test
    void pumpFedThroughJunctionAtPipeLevelKeepsFlowing() {
        double pumpHead = 36.25;
        List<NodeSpec> nodes = List.of(
                new NodeSpec(TANK_CAPACITANCE, 56.39),  // source tank
                new NodeSpec(0, 0),                     // junction
                new NodeSpec(0, 0),                     // pump
                new NodeSpec(TANK_CAPACITANCE, 56.00)); // destination tank
        double crest = 56.5;
        double pumpCurveConductance = 4;
        List<BranchSpec> branches = List.of(
                new BranchSpec(0, 1, 60, 0, 0, crest, 0.5),
                new BranchSpec(1, 2, 60, 0, +1, crest, 0.5),
                new BranchSpec(2, 3, pumpCurveConductance, pumpHead, +1, crest, 0.25));

        Result result = step(nodes, branches);
        assertTrue(result.flows()[2] > 5,
                "pump must move fluid, not deadlock at h = tank - pumpHead (got "
                        + result.flows()[2] + ")");
        assertEquals(result.flows()[1], result.flows()[2], 1e-6,
                "suction line feeds the pump exactly");
        for (double head : result.heads()) {
            assertTrue(head > 40, "no node may be pulled toward vacuum, got " + head);
        }
    }

    @Test
    void disconnectedAndDegenerateInputsAreHandled() {
        List<NodeSpec> nodes = List.of(
                new NodeSpec(TANK_CAPACITANCE, 10),
                new NodeSpec(0, 0),
                new NodeSpec(TANK_CAPACITANCE, 4));
        List<BranchSpec> branches = List.of(
                new BranchSpec(0, 0, 40, 0, 0, Double.NaN, 0),
                new BranchSpec(0, 2, 0, 0, 0, Double.NaN, 0),
                new BranchSpec(0, 9, 40, 0, 0, Double.NaN, 0));

        Result result = step(nodes, branches);
        for (double flow : result.flows()) assertEquals(0, flow, 0.0);
        for (double head : result.heads()) assertFalse(Double.isNaN(head));
        assertEquals(10, result.heads()[0], 1e-9, "isolated tank keeps its head");
    }

    /**
     * Above DIRECT_SOLVE_LIMIT the solver switches from Gaussian elimination to a sparse
     * Jacobi-preconditioned conjugate gradient. It must solve the SAME system: a symmetric chain (both
     * ends full) has to produce a symmetric head profile and conserve volume — a broken sparse matvec
     * would violate either. n = 200 forces the sparse path.
     */
    @Test
    void sparseSolverIsSymmetricAndConservesOnLargeNetwork() {
        int n = 200;
        List<NodeSpec> nodes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            nodes.add(new NodeSpec(TANK_CAPACITANCE, i == 0 || i == n - 1 ? 100 : 0));
        }
        List<BranchSpec> branches = new ArrayList<>();
        for (int i = 1; i < n; i++) branches.add(BranchSpec.passive(i - 1, i, 50));

        Result result = step(nodes, branches);

        double total = 0;
        for (int i = 0; i < n; i++) total += result.netInflow()[i];
        assertEquals(0, total, 1e-4, "volume conserved on the large network");
        for (int i = 0; i < n; i++) {
            assertFalse(Double.isNaN(result.heads()[i]), "head " + i + " must be finite");
            assertEquals(result.heads()[n - 1 - i], result.heads()[i], 1e-6,
                    "a symmetric input must give a symmetric head profile (node " + i + ")");
        }
    }

    /**
     * The sparse path must be stable over many ticks: a single full tank diffusing down a long chain
     * conserves total volume every step, never overshoots (no negative head), and the surface spread
     * only shrinks (the diffusion maximum principle). n = 150 stays on the sparse path.
     */
    @Test
    void sparseSolverConservesAndSettlesOverManyTicks() {
        int n = 150;
        List<NodeSpec> nodes = new ArrayList<>();
        for (int i = 0; i < n; i++) nodes.add(new NodeSpec(TANK_CAPACITANCE, i == 0 ? 100 : 0));
        List<BranchSpec> branches = new ArrayList<>();
        for (int i = 1; i < n; i++) branches.add(BranchSpec.passive(i - 1, i, 50));

        double initialVolume = 100 * TANK_CAPACITANCE;
        double previousSpread = 100;
        for (int tick = 0; tick < 300; tick++) {
            Result result = step(nodes, branches);
            nodes = advance(nodes, result);

            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE, volume = 0;
            for (NodeSpec node : nodes) {
                lo = Math.min(lo, node.head());
                hi = Math.max(hi, node.head());
                volume += node.head() * node.capacitance();
            }
            assertEquals(initialVolume, volume, 1e-2, "volume conserved across steps (tick " + tick + ")");
            assertTrue(lo >= -1e-6, "no tank overshoots below empty (tick " + tick + "), got " + lo);
            assertTrue(hi - lo <= previousSpread + 1e-6, "the surface spread must not grow (tick " + tick + ")");
            previousSpread = hi - lo;
        }
        assertTrue(previousSpread < 100, "the chain made progress toward equalization");
    }

    // ---------------------------------------------------------------- capacity box (saturation)

    /** A reservoir with explicit box limits on its end-of-tick head. */
    private static NodeSpec bounded(double capacitance, double head, double floor, double ceiling) {
        return new NodeSpec(capacitance, head, floor, ceiling);
    }

    /**
     * The goofy_network core: a pump feeds an intermediate tank that STILL HAS ROOM, but the
     * only run past it ends at a FULL terminal sink sitting above it. Without a capacity box the
     * linear solve routes fictitious current into the full sink (a bare capacitor "accepts" any
     * inflow within the step), draining the intermediate tank instead of filling it. The box
     * clamps the full sink to give-only, so the pump's flow backs up into the tank with room.
     */
    @Test
    void fullTerminalSinkBacksUpIntoAnIntermediateTankWithRoom() {
        // node0 supply (source), node1 intermediate A (room), node2 spout S (full, above A).
        // Branch 0→1 is the pump (emf 5, check valve). Branch 1→2 is the plain run A—S.
        List<NodeSpec> boxed = List.of(
                new NodeSpec(TANK_CAPACITANCE, 20),        // supply, unbounded → free to give
                bounded(TANK_CAPACITANCE, 3, 0, 4),        // A: 75% full, 1 block of room
                bounded(TANK_CAPACITANCE, 3, 2, 3));       // S: full at its ceiling, sitting above A
        List<BranchSpec> branches = List.of(
                new BranchSpec(0, 1, 4, 5, +1, Double.NaN, 0),
                BranchSpec.passive(1, 2, 100));

        Result result = step(boxed, branches);
        assertEquals(+1, result.saturation()[2], "the full terminal sink is clamped to give-only");
        assertTrue(result.flows()[0] > 0, "the pump keeps delivering from the supply");
        assertEquals(0, result.flows()[1], 1e-9, "no flow leaks into the full terminal sink");
        assertTrue(result.netInflow()[1] > 0, "the intermediate tank with room fills");
        assertEquals(0, result.netInflow()[2], 1e-9, "the full sink gains nothing");

        // Contrast: with the same topology unbounded, the full sink is a plain capacitor that
        // "accepts" fictitious inflow, stealing it from the tank that should fill.
        List<NodeSpec> unbounded = List.of(
                new NodeSpec(TANK_CAPACITANCE, 20),
                new NodeSpec(TANK_CAPACITANCE, 3),
                new NodeSpec(TANK_CAPACITANCE, 3));
        Result leak = step(unbounded, branches);
        assertTrue(leak.netInflow()[2] > 1e-6, "without the box, fluid fictitiously enters the full sink");
    }

    /**
     * The empty→receive-only half of the same box: an EMPTY tank sitting high must not be modelled
     * as a source. node0 is an empty tank whose base is above node1's fluid surface; the plain
     * linear solve would flow node0→node1 on the elevation difference, minting fluid from an empty
     * reservoir. The box clamps the empty node to receive-only, blocking the fictitious drain.
     */
    @Test
    void emptyElevatedTankIsNotAFictitiousSource() {
        List<NodeSpec> boxed = List.of(
                bounded(TANK_CAPACITANCE, 10, 10, 14),   // empty tank, base at y=10
                bounded(TANK_CAPACITANCE, 5, 0, 8));      // lower tank with fluid, surface at y=5
        List<BranchSpec> branches = List.of(BranchSpec.passive(0, 1, 100));

        Result result = step(boxed, branches);
        assertEquals(-1, result.saturation()[0], "the empty tank is clamped to receive-only");
        assertEquals(0, result.flows()[0], 1e-9, "an empty reservoir gives nothing, even from a height");
        assertEquals(0, result.netInflow()[1], 1e-9, "nothing is minted into the lower tank");
    }

    /**
     * Two FULL tanks at different elevations connected by a pipe: neither can receive, so the run
     * is a DEAD CONDUIT — no flow either way. Without the box the higher full tank would drain into
     * the lower one, overfilling it past capacity.
     */
    @Test
    void twoFullTanksFacingEachOtherFormADeadConduit() {
        List<NodeSpec> boxed = List.of(
                bounded(TANK_CAPACITANCE, 8, 4, 8),   // full, higher
                bounded(TANK_CAPACITANCE, 4, 0, 4));  // full, lower
        List<BranchSpec> branches = List.of(BranchSpec.passive(0, 1, 100));

        Result result = step(boxed, branches);
        assertEquals(+1, result.saturation()[0], "the higher tank reads full");
        assertEquals(+1, result.saturation()[1], "the lower tank reads full");
        assertEquals(0, result.flows()[0], 1e-9, "a dead conduit carries no flow either way");
        assertEquals(0, result.netInflow()[0], 1e-9);
        assertEquals(0, result.netInflow()[1], 1e-9);
    }

    /**
     * A near-full tank is NOT walled: the solver decides DIRECTION (only truly saturated nodes are
     * clamped), the transfer layer clamps MAGNITUDE to the real remaining room. A tank at 99% that a
     * pump feeds must keep receiving — walling it on the solved single-step overshoot would freeze it
     * short of full forever. This guards the start-of-tick (not solved-head) saturation criterion.
     */
    @Test
    void nearFullTankStillFillsBecauseTheSolverOnlyDecidesDirection() {
        List<NodeSpec> boxed = List.of(
                new NodeSpec(TANK_CAPACITANCE, 20),      // supply
                bounded(TANK_CAPACITANCE, 3.99, 0, 4));  // A: 99.75% full, a sliver of room
        List<BranchSpec> branches = List.of(new BranchSpec(0, 1, 4, 5, +1, Double.NaN, 0));

        Result result = step(boxed, branches);
        assertEquals(0, result.saturation()[1], "a tank with room is never clamped, however small the room");
        assertTrue(result.flows()[0] > 0, "the pump keeps delivering into the near-full tank");
        assertTrue(result.netInflow()[1] > 0, "the near-full tank keeps filling");
    }

    /**
     * The box changes nothing when no reservoir is saturated: an ordinary equalization between two
     * tanks well inside their bounds solves identically to the unbounded case.
     */
    @Test
    void boxIsInertWhenNoReservoirIsSaturated() {
        List<NodeSpec> boxed = List.of(
                bounded(TANK_CAPACITANCE, 10, 0, 16),
                bounded(TANK_CAPACITANCE, 6, 0, 16));
        List<NodeSpec> plain = List.of(
                new NodeSpec(TANK_CAPACITANCE, 10),
                new NodeSpec(TANK_CAPACITANCE, 6));
        List<BranchSpec> branches = List.of(BranchSpec.passive(0, 1, 50));

        Result boxedResult = step(boxed, branches);
        Result plainResult = step(plain, branches);
        assertEquals(plainResult.flows()[0], boxedResult.flows()[0], 1e-12,
                "an unsaturated network is unaffected by its box bounds");
        assertEquals(0, boxedResult.saturation()[0]);
        assertEquals(0, boxedResult.saturation()[1]);
    }
}
