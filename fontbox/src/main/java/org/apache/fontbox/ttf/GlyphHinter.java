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
package org.apache.fontbox.ttf;

import java.awt.geom.GeneralPath;
import java.io.IOException;

import org.apache.fontbox.ttf.instruction.BytecodeStream;
import org.apache.fontbox.ttf.instruction.ExecutionContext;
import org.apache.fontbox.ttf.instruction.Fixed;
import org.apache.fontbox.ttf.instruction.GraphicsState;
import org.apache.fontbox.ttf.instruction.TrueTypeInterpreter;
import org.apache.fontbox.ttf.instruction.Zone;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Applies TrueType bytecode hinting (grid-fitting) to a font's glyphs, producing grid-fitted paths.
 * <p>
 * One hinter is created per {@link TrueTypeFont}. It lazily builds a {@link TrueTypeInterpreter} from
 * the font's {@code maxp}/{@code head}/{@code cvt}/{@code fpgm}/{@code prep} tables, runs the font
 * program once, and re-runs the control value program whenever the ppem changes. For each glyph it
 * scales the outline into the pixel grid (appending the phantom points), runs the glyph's instructions
 * and scales the grid-fitted result back into font units, so the rest of the rendering pipeline - which
 * scales font units to device pixels at exactly this ppem - reproduces the grid-fitting.
 * <p>
 * Hinting is best-effort: anything malformed, unsupported, or not applicable (a composite glyph, a
 * glyph with no instructions, a ppem the {@code gasp} table excludes) falls back to {@code null}, and
 * the caller renders the raw outline. One bad glyph never disables hinting for the rest of the font.
 * Setting the system property {@code org.apache.fontbox.ttf.hinting} to {@code false} disables hinting
 * entirely.
 *
 * @author Apache PDFBox
 */
class GlyphHinter
{
    private static final Logger LOG = LogManager.getLogger(GlyphHinter.class);

    /** System property to disable hinting entirely (the regression escape hatch). */
    static final String HINTING_PROPERTY = "org.apache.fontbox.ttf.hinting";

    private final TrueTypeFont font;

    private boolean initialized;
    private boolean available;
    private TrueTypeInterpreter interpreter;
    private GaspTable gasp;
    private int unitsPerEm;
    private int currentPpem = -1;

    GlyphHinter(TrueTypeFont font)
    {
        this.font = font;
    }

    private static boolean isDisabled()
    {
        return "false".equalsIgnoreCase(System.getProperty(HINTING_PROPERTY));
    }

    private synchronized void initialize() throws IOException
    {
        if (initialized)
        {
            return;
        }
        initialized = true;
        available = false;

        MaximumProfileTable maxp = font.getMaximumProfile();
        FontProgramTable fpgm = font.getFontProgram();
        ControlValueProgramTable prep = font.getControlValueProgram();
        ControlValueTable cvt = font.getControlValues();

        // hinting is only meaningful if the font carries a bytecode program
        if (maxp == null || (fpgm == null && prep == null))
        {
            return;
        }

        unitsPerEm = font.getUnitsPerEm();
        gasp = font.getGasp();

        interpreter = new TrueTypeInterpreter(maxp.getMaxStackElements(), maxp.getMaxStorage(),
                maxp.getMaxTwilightPoints(), unitsPerEm);
        interpreter.setFontProgram(fpgm != null ? fpgm.getProgram() : null);
        interpreter.setControlValueProgram(prep != null ? prep.getProgram() : null);
        interpreter.setControlValues(cvt != null ? cvt.getValues() : null);
        interpreter.prepareFontProgram();
        available = true;
    }

    /**
     * Returns the grid-fitted path of the glyph at the given ppem, or {@code null} if hinting does not
     * apply and the caller should render the raw outline.
     *
     * @param gid the glyph id
     * @param ppem the pixels-per-em to grid-fit to
     * @return the hinted path in font units, or null
     */
    synchronized GeneralPath getPath(int gid, int ppem)
    {
        Hinted hinted = hint(gid, ppem);
        if (hinted == null)
        {
            return null;
        }
        // scale the grid-fitted coordinates back into font units (drop the phantom points)
        int[] hintedX = new int[hinted.pointCount];
        int[] hintedY = new int[hinted.pointCount];
        int[] curX = hinted.zone.getCurrentX();
        int[] curY = hinted.zone.getCurrentY();
        for (int i = 0; i < hinted.pointCount; i++)
        {
            hintedX[i] = toFontUnits(curX[i], ppem);
            hintedY[i] = toFontUnits(curY[i], ppem);
        }
        return new GlyphRenderer(hinted.gd, hintedX, hintedY).getPath();
    }

    /**
     * Returns the grid-fitted glyph points in F26Dot6 device coordinates (the raw interpreter output,
     * before scaling back to font units, and excluding the phantom points), or {@code null} if hinting
     * does not apply. This is the form compared against a FreeType reference dump by the golden tests.
     *
     * @param gid the glyph id
     * @param ppem the pixels-per-em to grid-fit to
     * @return a {@code {x[], y[]}} pair in F26Dot6, or null
     */
    synchronized int[][] getHintedPointsF26Dot6(int gid, int ppem)
    {
        Hinted hinted = hint(gid, ppem);
        if (hinted == null)
        {
            return null;
        }
        int[] x = new int[hinted.pointCount];
        int[] y = new int[hinted.pointCount];
        System.arraycopy(hinted.zone.getCurrentX(), 0, x, 0, hinted.pointCount);
        System.arraycopy(hinted.zone.getCurrentY(), 0, y, 0, hinted.pointCount);
        return new int[][] { x, y };
    }

    /** Maximum composite nesting depth, to bound recursion on pathological fonts. */
    private static final int MAX_COMPONENT_DEPTH = 8;

    /** Runs all gating, then grid-fits the glyph, returning the executed zone or null on fallback. */
    private Hinted hint(int gid, int ppem)
    {
        if (isDisabled() || ppem <= 0)
        {
            return null;
        }
        try
        {
            initialize();
            if (!available)
            {
                return null;
            }
            // gasp gate: if a gasp table is present and does not request grid-fitting here, skip
            if (gasp != null && !gasp.isGridFit(ppem))
            {
                return null;
            }
            setActivePpem(ppem);
            return hint(gid, ppem, 0);
        }
        catch (IOException | RuntimeException e)
        {
            LOG.warn("hinting failed for glyph {} at {}ppem, using raw outline", gid, ppem, e);
            return null;
        }
    }

    private void setActivePpem(int ppem) throws IOException
    {
        if (ppem != currentPpem)
        {
            interpreter.setPpem(ppem, ppem);
            currentPpem = ppem;
        }
    }

    /** Grid-fits one glyph (simple or composite), recursing into components. */
    private Hinted hint(int gid, int ppem, int depth) throws IOException
    {
        if (depth > MAX_COMPONENT_DEPTH)
        {
            return null;
        }
        GlyphData glyph = font.getGlyph().getGlyph(gid);
        if (glyph == null)
        {
            return null;
        }
        GlyphDescription gd = glyph.getDescription();
        if (!(gd instanceof GlyfDescript))
        {
            return null;
        }
        if (gd.isComposite())
        {
            gd.resolve();
            if (gd.getPointCount() == 0)
            {
                return null;
            }
            return hintComposite(glyph, (GlyfCompositeDescript) gd, gid, ppem, depth);
        }
        if (gd.getContourCount() == 0 || gd.getPointCount() == 0)
        {
            // empty glyph (e.g. space, newline): nothing to hint
            return null;
        }
        int[] instructions = ((GlyfDescript) gd).getInstructions();
        if (instructions == null || instructions.length == 0)
        {
            return null;
        }
        int pointCount = gd.getPointCount();
        Zone zone = buildZone(glyph, gd, gid, ppem, pointCount, gd.getContourCount());
        runProgram(zone, instructions, ppem);
        return new Hinted(gd, zone, pointCount);
    }

    /**
     * Grid-fits a composite glyph the way FreeType does: each component is hinted on its own, then
     * transformed and offset into the composite's coordinate space, the phantom points appended, and
     * finally the composite's own instructions (if any) run over the assembled outline.
     */
    private Hinted hintComposite(GlyphData glyph, GlyfCompositeDescript composite, int gid, int ppem,
            int depth) throws IOException
    {
        int pointCount = composite.getPointCount();
        int contourCount = composite.getContourCount();
        Zone zone = new Zone(pointCount + 4, contourCount);
        int[] curX = zone.getCurrentX();
        int[] curY = zone.getCurrentY();
        int[] orgX = zone.getOriginalX();
        int[] orgY = zone.getOriginalY();
        boolean[] onCurve = zone.getOnCurve();

        for (GlyfCompositeComp comp : composite.getComponents())
        {
            assembleComponent(comp, ppem, depth, curX, curY, orgX, orgY, onCurve);
        }
        int[] ends = zone.getContourEnds();
        for (int c = 0; c < contourCount; c++)
        {
            ends[c] = composite.getEndPtOfContours(c);
        }
        appendPhantomPoints(glyph, gid, ppem, pointCount, orgX, orgY, curX, curY);

        int[] instructions = composite.getInstructions();
        if (instructions != null && instructions.length > 0)
        {
            runProgram(zone, instructions, ppem);
        }
        return new Hinted(composite, zone, pointCount);
    }

    /**
     * Hints one component glyph and writes its transformed/offset points into the composite's zone
     * arrays. The component's grid-fitted outline goes to the current arrays and its scaled-but-unhinted
     * outline to the original arrays, so the composite's instructions can measure original distances.
     */
    private void assembleComponent(GlyfCompositeComp comp, int ppem, int depth, int[] curX,
            int[] curY, int[] orgX, int[] orgY, boolean[] onCurve) throws IOException
    {
        int componentGid = comp.getGlyphIndex();
        int first = comp.getFirstIndex();

        GlyphData componentGlyph = font.getGlyph().getGlyph(componentGid);
        GlyphDescription cgd = componentGlyph != null ? componentGlyph.getDescription() : null;
        if (cgd == null)
        {
            return;
        }
        if (cgd.isComposite())
        {
            cgd.resolve();
        }
        int count = cgd.getPointCount();

        // scaled-but-unhinted component points (the "original" outline)
        int[] cOrgX = new int[count];
        int[] cOrgY = new int[count];
        for (int k = 0; k < count; k++)
        {
            cOrgX[k] = Fixed.scale(cgd.getXCoordinate(k), ppem, unitsPerEm);
            cOrgY[k] = Fixed.scale(cgd.getYCoordinate(k), ppem, unitsPerEm);
            onCurve[first + k] = (cgd.getFlags(k) & GlyfDescript.ON_CURVE) != 0;
        }

        // grid-fitted component points (its own instructions executed); fall back to unhinted
        int[] cCurX = cOrgX;
        int[] cCurY = cOrgY;
        Hinted hintedComponent = hint(componentGid, ppem, depth + 1);
        if (hintedComponent != null && hintedComponent.pointCount == count)
        {
            cCurX = hintedComponent.zone.getCurrentX();
            cCurY = hintedComponent.zone.getCurrentY();
        }

        // device-space offset (FreeType does not grid-round the component offset here, even when
        // ROUND_XY_TO_GRID is set, so neither do we)
        int offsetX = Fixed.scale(comp.getXTranslate(), ppem, unitsPerEm);
        int offsetY = Fixed.scale(comp.getYTranslate(), ppem, unitsPerEm);

        for (int k = 0; k < count; k++)
        {
            orgX[first + k] = comp.scaleX(cOrgX[k], cOrgY[k]) + offsetX;
            orgY[first + k] = comp.scaleY(cOrgX[k], cOrgY[k]) + offsetY;
            curX[first + k] = comp.scaleX(cCurX[k], cCurY[k]) + offsetX;
            curY[first + k] = comp.scaleY(cCurX[k], cCurY[k]) + offsetY;
        }
    }

    /** Clones the saved post-prep state, resets it for the glyph, and runs the program over the zone. */
    private void runProgram(Zone zone, int[] instructions, int ppem)
    {
        GraphicsState gs = interpreter.getSavedState().copy();
        gs.resetForGlyph();
        ExecutionContext ctx = interpreter.newContext(gs);
        ctx.setPpem(ppem);
        ctx.setGlyphZone(zone);
        interpreter.run(ctx, new BytecodeStream(toByteArray(instructions)));
    }

    /** The result of grid-fitting one glyph: its description, the executed zone, and its point count
     * (without the appended phantom points). */
    private static final class Hinted
    {
        private final GlyphDescription gd;
        private final Zone zone;
        private final int pointCount;

        Hinted(GlyphDescription gd, Zone zone, int pointCount)
        {
            this.gd = gd;
            this.zone = zone;
            this.pointCount = pointCount;
        }
    }

    private Zone buildZone(GlyphData glyph, GlyphDescription gd, int gid, int ppem, int pointCount,
            int contourCount) throws IOException
    {
        // four phantom points are appended after the glyph's own points
        Zone zone = new Zone(pointCount + 4, contourCount);
        int[] curX = zone.getCurrentX();
        int[] curY = zone.getCurrentY();
        int[] orgX = zone.getOriginalX();
        int[] orgY = zone.getOriginalY();
        boolean[] onCurve = zone.getOnCurve();
        for (int i = 0; i < pointCount; i++)
        {
            int x = Fixed.scale(gd.getXCoordinate(i), ppem, unitsPerEm);
            int y = Fixed.scale(gd.getYCoordinate(i), ppem, unitsPerEm);
            orgX[i] = x;
            orgY[i] = y;
            curX[i] = x;
            curY[i] = y;
            onCurve[i] = (gd.getFlags(i) & GlyfDescript.ON_CURVE) != 0;
        }
        int[] ends = zone.getContourEnds();
        for (int c = 0; c < contourCount; c++)
        {
            ends[c] = gd.getEndPtOfContours(c);
        }
        appendPhantomPoints(glyph, gid, ppem, pointCount, orgX, orgY, curX, curY);
        return zone;
    }

    private void appendPhantomPoints(GlyphData glyph, int gid, int ppem, int pointCount, int[] orgX,
            int[] orgY, int[] curX, int[] curY) throws IOException
    {
        HorizontalMetricsTable hmtx = font.getHorizontalMetrics();
        int advanceWidth = hmtx != null ? hmtx.getAdvanceWidth(gid) : unitsPerEm;
        int leftSideBearing = hmtx != null ? hmtx.getLeftSideBearing(gid) : 0;
        int originX = glyph.getXMinimum() - leftSideBearing;
        int yMax = glyph.getYMaximum();

        // pp1 = origin, pp2 = origin + advance (horizontal); pp3/pp4 are the vertical pair
        int[] px = { originX, originX + advanceWidth, 0, 0 };
        int[] py = { 0, 0, yMax, yMax - unitsPerEm };
        for (int i = 0; i < 4; i++)
        {
            int index = pointCount + i;
            orgX[index] = Fixed.scale(px[i], ppem, unitsPerEm);
            orgY[index] = Fixed.scale(py[i], ppem, unitsPerEm);
            // FreeType rounds the phantom points to the grid before running the glyph program
            curX[index] = Fixed.round(orgX[index]);
            curY[index] = Fixed.round(orgY[index]);
        }
    }

    /** Scales an F26Dot6 device coordinate back to font units. */
    private int toFontUnits(int f26dot6, int ppem)
    {
        long numerator = (long) f26dot6 * unitsPerEm;
        long denominator = (long) ppem * Fixed.ONE;
        long half = denominator / 2;
        return (int) ((numerator >= 0 ? numerator + half : numerator - half) / denominator);
    }

    private static byte[] toByteArray(int[] instructions)
    {
        byte[] bytes = new byte[instructions.length];
        for (int i = 0; i < instructions.length; i++)
        {
            bytes[i] = (byte) instructions[i];
        }
        return bytes;
    }
}
