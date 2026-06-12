/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.fontbox.ttf.instruction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tier-1 unit tests for the point-moving opcodes: each builds a glyph zone, runs a short program
 * through the interpreter, and asserts the resulting coordinates. Coordinates are in F26Dot6 (64 per
 * pixel). Byte-exact agreement with FreeType is proven later by the Phase 5 golden tests; these check
 * the per-opcode logic.
 */
class PointOpsTest
{
    private static TrueTypeInterpreter interpreter()
    {
        return new TrueTypeInterpreter(256, 16, 16, 2048);
    }

    /** Builds a single-contour glyph zone with the given x coordinates (original == current). */
    private static Zone lineZone(int... xs)
    {
        Zone zone = new Zone(xs.length, 1);
        for (int i = 0; i < xs.length; i++)
        {
            zone.getOriginalX()[i] = xs[i];
            zone.getCurrentX()[i] = xs[i];
        }
        zone.getContourEnds()[0] = xs.length - 1;
        return zone;
    }

    private static ExecutionContext context(TrueTypeInterpreter interp, Zone glyph)
    {
        ExecutionContext ctx = interp.newContext(new GraphicsState());
        ctx.setPpem(16);
        ctx.setGlyphZone(glyph);
        return ctx;
    }

    @Test
    void testProjectionAndMove()
    {
        ExecutionContext ctx = new ExecutionContext(null, new GraphicsState(), 16, 0, null, 0);
        Zone zone = lineZone(100, 0);
        // default projection/freedom is the x axis: project returns the x coordinate
        assertEquals(100, ctx.project(zone.getCurrentX()[0], zone.getCurrentY()[0]));
        ctx.movePoint(zone, 0, 28); // move +28 along x
        assertEquals(128, zone.getCurrentX()[0]);
        assertTrue(zone.getTouchedX()[0]);
        assertFalse(zone.getTouchedY()[0]);
    }

    @Test
    void testMdapRoundsToGrid()
    {
        TrueTypeInterpreter interp = interpreter();
        Zone zone = lineZone(100); // 1.5625px
        ExecutionContext ctx = context(interp, zone);
        // PUSHB[0] 0 ; MDAP[1] (round)
        interp.run(ctx, new BytecodeStream(new byte[] { (byte) 0xB0, 0, 0x2F }));
        assertEquals(128, zone.getCurrentX()[0]); // rounded to 2px
        assertTrue(zone.getTouchedX()[0]);
    }

    @Test
    void testMdrpRelativeToRp0()
    {
        TrueTypeInterpreter interp = interpreter();
        Zone zone = lineZone(0, 100); // rp0 = point 0 at x=0, point 1 at 1.5625px
        ExecutionContext ctx = context(interp, zone);
        // PUSHB[0] 1 ; MDRP[round] (0xC8) - move point 1 to a grid-rounded distance from rp0
        interp.run(ctx, new BytecodeStream(new byte[] { (byte) 0xB0, 1, (byte) 0xC8 }));
        assertEquals(128, zone.getCurrentX()[1]); // distance 100 rounds to 128
    }

    @Test
    void testMirpUsesControlValue()
    {
        TrueTypeInterpreter interp = interpreter();
        // raw cvt 256 at 16ppem / 2048 upem scales to 128 (2px)
        interp.setControlValues(new int[] { 256 });
        interp.setPpem(16, 16);
        Zone zone = lineZone(0, 100);
        ExecutionContext ctx = interp.newContext(new GraphicsState());
        ctx.setPpem(16);
        ctx.setGlyphZone(zone);
        // PUSHB[1] 1 0 (point=1 pushed first, cvtIndex=0 on top) ; MIRP[round] (0xE8)
        interp.run(ctx, new BytecodeStream(new byte[] { (byte) 0xB1, 1, 0, (byte) 0xE8 }));
        assertEquals(128, zone.getCurrentX()[1]);
    }

    @Test
    void testMsirpSetsExactDistance()
    {
        TrueTypeInterpreter interp = interpreter();
        Zone zone = lineZone(0, 100);
        ExecutionContext ctx = context(interp, zone);
        // PUSHB[1] 1 64 (point=1, distance=1px) ; MSIRP[0] (0x3A)
        interp.run(ctx, new BytecodeStream(new byte[] { (byte) 0xB1, 1, 64, 0x3A }));
        assertEquals(64, zone.getCurrentX()[1]); // exactly 1px from rp0 at x=0
    }

    @Test
    void testAlignRp()
    {
        TrueTypeInterpreter interp = interpreter();
        Zone zone = lineZone(0, 100); // rp0 at 0
        ExecutionContext ctx = context(interp, zone);
        // PUSHB[0] 1 ; ALIGNRP (0x3C) - align point 1 onto rp0
        interp.run(ctx, new BytecodeStream(new byte[] { (byte) 0xB0, 1, 0x3C }));
        assertEquals(0, zone.getCurrentX()[1]);
    }

    @Test
    void testIupInterpolatesUntouched()
    {
        TrueTypeInterpreter interp = interpreter();
        // three points on one contour; the middle one is untouched
        Zone zone = lineZone(0, 50, 100);
        zone.getTouchedX()[0] = true;
        zone.getTouchedX()[2] = true;
        zone.getCurrentX()[2] = 120; // the right anchor was hinted +20
        ExecutionContext ctx = context(interp, zone);
        // IUP[1] (x axis)
        interp.run(ctx, new BytecodeStream(new byte[] { 0x31 }));
        // p1 interpolates proportionally: 0 + 50*(120-0)/100 = 60
        assertEquals(60, zone.getCurrentX()[1]);
        // touched anchors are never moved by IUP
        assertEquals(0, zone.getCurrentX()[0]);
        assertEquals(120, zone.getCurrentX()[2]);
    }

    @Test
    void testIupShiftsWhenSingleTouchedPoint()
    {
        TrueTypeInterpreter interp = interpreter();
        Zone zone = lineZone(0, 50, 100);
        zone.getTouchedX()[1] = true;
        zone.getCurrentX()[1] = 70; // the only touched point moved +20
        ExecutionContext ctx = context(interp, zone);
        interp.run(ctx, new BytecodeStream(new byte[] { 0x31 })); // IUP[1]
        // with one touched point, every other point shifts by the same delta (+20)
        assertEquals(20, zone.getCurrentX()[0]);
        assertEquals(120, zone.getCurrentX()[2]);
    }

    @Test
    void testIpInterpolatesBetweenReferencePoints()
    {
        TrueTypeInterpreter interp = interpreter();
        Zone zone = lineZone(0, 50, 100);
        // set rp1=0, rp2=2, move the anchors, then IP point 1
        zone.getCurrentX()[2] = 120;
        GraphicsState gs = new GraphicsState();
        gs.setRp1(0);
        gs.setRp2(2);
        ExecutionContext ctx = interp.newContext(gs);
        ctx.setPpem(16);
        ctx.setGlyphZone(zone);
        // PUSHB[0] 1 ; IP (0x39)
        interp.run(ctx, new BytecodeStream(new byte[] { (byte) 0xB0, 1, 0x39 }));
        assertEquals(60, zone.getCurrentX()[1]);
    }

    @Test
    void testSvtcaSetsProjectionVector()
    {
        TrueTypeInterpreter interp = interpreter();
        ExecutionContext ctx = interp.newContext(new GraphicsState());
        // SVTCA[0] (y axis) ; GPV
        interp.run(ctx, new BytecodeStream(new byte[] { 0x00, 0x0C }));
        assertEquals(Fixed.ONE_F2DOT14, ctx.peek(0)); // pv.y
        assertEquals(0, ctx.peek(1));                 // pv.x
    }

    @Test
    void testRoundOpcode()
    {
        // PUSHB[0] 100 ; ROUND[0] (0x68) -> 128 under default round-to-grid
        ExecutionContext ctx = interpreter().executeProgram(new byte[] { (byte) 0xB0, 100, 0x68 }, 16);
        assertEquals(128, ctx.peek(0));
    }

    @Test
    void testGcReadsProjectedCoordinate()
    {
        TrueTypeInterpreter interp = interpreter();
        Zone zone = lineZone(192); // 3px
        ExecutionContext ctx = context(interp, zone);
        // PUSHB[0] 0 ; GC[0] (0x46) current coordinate
        interp.run(ctx, new BytecodeStream(new byte[] { (byte) 0xB0, 0, 0x46 }));
        assertEquals(192, ctx.peek(0));
    }
}
