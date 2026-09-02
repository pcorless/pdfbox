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
import org.apache.fontbox.ttf.instruction.ExecutionTracer;
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
 * Hinting as a whole is switched on and off by {@link TrueTypeFont#isHintingEnabled()}; while it is
 * off every glyph falls back to {@code null}.
 *
 * @author Apache PDFBox
 */
class GlyphHinter
{
    private static final Logger LOG = LogManager.getLogger(GlyphHinter.class);

    private final TrueTypeFont font;

    private boolean initialized;
    private boolean available;
    private boolean tricky;
    private boolean warned;
    private TrueTypeInterpreter interpreter;
    private GaspTable gasp;
    private int unitsPerEm;
    private int currentPpem = -1;

    /**
     * Family-name fragments of "tricky" fonts (after FreeType's {@code tt_check_trickyness_family}).
     * These mostly CJK fonts rely on the bytecode interpreter to assemble and scale glyphs from
     * sub-pixel-sized components, so the v40 grayscale "backward compatibility" movement restrictions
     * (which suppress x grid-fitting) would distort them. For these fonts we hint with full control,
     * exactly as FreeType disables backward compatibility when {@code FT_FACE_FLAG_TRICKY} is set.
     */
    private static final String[] TRICKY_FAMILIES = {
        "cpop", "DFGirl-W6-WIN-BF", "DFGothic-EB", "DFGyoSho-Lt", "DFHei", "DFHSGothic-W5",
        "DFHSMincho-W3", "DFHSMincho-W7", "DFKaiSho-SB", "DFKaiShu", "DFKai-SB", "DFMing", "DLC",
        "HuaTianKaiTi?", "HuaTianSongTi?", "Ming(for ISO10646)", "MingLiU", "MingMedium", "PMingLiU",
        "MingLi43"
    };

    /**
     * Checksum/length of the {@code cvt }, {@code fpgm} and {@code prep} tables of known tricky fonts,
     * each row {@code {cvtSum, cvtLen, fpgmSum, fpgmLen, prepSum, prepLen}} (after FreeType's
     * {@code tt_check_trickyness_sfnt_ids}). This catches tricky fonts whose family name was changed -
     * e.g. an embedded MingLiU renamed to "HA_MingLiu" - which the name list above would miss. A length
     * of 0 means the font is expected not to have that table.
     */
    private static final long[][] TRICKY_SFNT_IDS = {
        {0x05BCF058L, 0x2E4L, 0x28233BF1L, 0x87C4L, 0xA344A1EAL, 0x1E1L}, // MingLiU 1995
        {0x05BCF058L, 0x2E4L, 0x28233BF1L, 0x87C4L, 0xA344A1EBL, 0x1E1L}, // MingLiU 1996-
        {0x12C3EBB2L, 0x350L, 0xB680EE64L, 0x87A7L, 0xCE939563L, 0x758L}, // DFGothic-EB
        {0x11E5EAD4L, 0x350L, 0xCE5956E9L, 0xBC85L, 0x8272F416L, 0x45L}, // DFGyoSho-Lt
        {0x1257EB46L, 0x350L, 0xF699D160L, 0x715FL, 0xD222F568L, 0x3BCL}, // DFHei-Md-HK-BF
        {0x1262EB4EL, 0x350L, 0xE86A5D64L, 0x7940L, 0x7850F729L, 0x5FFL}, // DFHSGothic-W5
        {0x122DEB0AL, 0x350L, 0x3D16328AL, 0x859BL, 0xA93FC33BL, 0x2CBL}, // DFHSMincho-W3
        {0x125FEB26L, 0x350L, 0xA5ACC982L, 0x7EE1L, 0x90999196L, 0x41FL}, // DFHSMincho-W7
        {0x11E5EAD4L, 0x350L, 0x5A30CA3BL, 0x9063L, 0x13A42602L, 0x7EL}, // DFKaiShu
        {0x11E5EAD4L, 0x350L, 0xA6E78C01L, 0x8998L, 0x13A42602L, 0x7EL}, // DFKaiShu variant
        {0x11E5EAD4L, 0x360L, 0x9DB282B2L, 0xC06EL, 0x53E6D7CAL, 0x82L}, // DFKaiShu-Md-HK-BF
        {0x1243EB18L, 0x350L, 0xBA0A8C30L, 0x74ADL, 0xF3D83409L, 0x37BL}, // DFMing-Bd-HK-BF
        {0x07DCF546L, 0x308L, 0x40FE7C90L, 0x8E2AL, 0x608174B5L, 0x7AL}, // DLCLiShu
        {0xEB891238L, 0x308L, 0xD2E4DCD4L, 0x676FL, 0x8EA5F293L, 0x3B8L}, // DLCHayBold
        {0xFFFBFFFCL, 0x8L, 0x9C9E48B8L, 0xBEA2L, 0x70020112L, 0x8L}, // HuaTianKaiTi
        {0xFFFBFFFCL, 0x8L, 0x0A5A0483L, 0x17C39L, 0x70020112L, 0x8L}, // HuaTianSongTi
        {0x00000000L, 0x0L, 0x40C92555L, 0xE5L, 0xA39B58E3L, 0x117CL}, // NEC fadpop7.ttf
        {0x00000000L, 0x0L, 0x33C41652L, 0xE5L, 0x26D6C52AL, 0xF6AL}, // NEC fadrei5.ttf
        {0x00000000L, 0x0L, 0x6DB1651DL, 0x19DL, 0x6C6E4B03L, 0x2492L}, // NEC fangot7.ttf
        {0x00000000L, 0x0L, 0x40C92555L, 0xE5L, 0xDE51FAD0L, 0x117CL}, // NEC fangyo5.ttf
        {0x00000000L, 0x0L, 0x85E47664L, 0xE5L, 0xA6C62831L, 0x1CAAL}, // NEC fankyo5.ttf
        {0x00000000L, 0x0L, 0x2D891CFDL, 0x19DL, 0xA0604633L, 0x1DE8L}, // NEC fanrgo5.ttf
        {0x00000000L, 0x0L, 0x40AA774CL, 0x1CBL, 0x9B5CAA96L, 0x1F9AL}, // NEC fangot5.ttc
        {0x00000000L, 0x0L, 0x0D3DE9CBL, 0x141L, 0xD4127766L, 0x2280L}, // NEC fanmin3.ttc
        {0x00000000L, 0x0L, 0x4A692698L, 0x1F0L, 0x340D4346L, 0x1FCAL}, // NEC FA-Gothic 1996
        {0x00000000L, 0x0L, 0xCD34C604L, 0x166L, 0x6CF31046L, 0x22B0L}, // NEC FA-Minchou 1996
        {0x00000000L, 0x0L, 0x5DA75315L, 0x19DL, 0x40745A5FL, 0x22E0L}, // NEC FA-RoundGothicB 1996
        {0x00000000L, 0x0L, 0xF055FC48L, 0x1C2L, 0x3900DED3L, 0x1E18L}, // NEC FA-RoundGothicM 1996
        {0x00170003L, 0x60L, 0xDBB4306EL, 0x58AAL, 0xD643482AL, 0x35L}, // MINGLI.TTF 1992
        {0x1269EB58L, 0x350L, 0x5CD5957AL, 0x6A4EL, 0xF758323AL, 0x380L}, // DFHei-Bd-WIN-HK-BF
        {0x122FEB0BL, 0x350L, 0x7F10919AL, 0x70A9L, 0x7CD7E7B7L, 0x25CL}, // DFMing-Md-WIN-HK-BF
    };

    GlyphHinter(TrueTypeFont font)
    {
        this.font = font;
    }

    /** True if the font is a known "tricky" font whose glyphs depend on full bytecode hinting. */
    private static boolean isTrickyFont(TrueTypeFont font)
    {
        try
        {
            NamingTable naming = font.getNaming();
            if (isTrickyFamily(naming != null ? naming.getFontFamily() : null))
            {
                return true;
            }
        }
        catch (IOException e)
        {
            // fall through to the checksum check
        }
        return isTrickyByChecksum(font);
    }

    /** Matches the cvt/fpgm/prep table checksums against the known-tricky list (renamed-font fallback). */
    private static boolean isTrickyByChecksum(TrueTypeFont font)
    {
        TTFTable cvt = font.getTableMap().get("cvt ");
        TTFTable fpgm = font.getTableMap().get("fpgm");
        TTFTable prep = font.getTableMap().get("prep");
        return matchesTrickyChecksums(len(cvt), sum(cvt), len(fpgm), sum(fpgm), len(prep), sum(prep));
    }

    private static long len(TTFTable table)
    {
        return table != null ? table.getLength() : -1;
    }

    private static long sum(TTFTable table)
    {
        return table != null ? table.getCheckSum() & 0xFFFFFFFFL : 0;
    }

    /**
     * True if the cvt/fpgm/prep (length, checksum) pairs match a known tricky font. A length of -1 means
     * the table is absent, which matches a list entry whose expected length is 0.
     */
    static boolean matchesTrickyChecksums(long cvtLen, long cvtSum, long fpgmLen, long fpgmSum,
            long prepLen, long prepSum)
    {
        for (long[] ids : TRICKY_SFNT_IDS)
        {
            if (tableMatches(cvtLen, cvtSum, ids[1], ids[0])
                    && tableMatches(fpgmLen, fpgmSum, ids[3], ids[2])
                    && tableMatches(prepLen, prepSum, ids[5], ids[4]))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean tableMatches(long length, long checksum, long expectedLength,
            long expectedChecksum)
    {
        if (length < 0)
        {
            return expectedLength == 0;
        }
        return length == expectedLength && checksum == expectedChecksum;
    }

    /** True if {@code family} (a font family name, possibly with a subset tag) is a known tricky font. */
    static boolean isTrickyFamily(String family)
    {
        if (family == null)
        {
            return false;
        }
        String name = stripSubsetTag(family);
        for (String trick : TRICKY_FAMILIES)
        {
            if (name.contains(trick))
            {
                return true;
            }
        }
        return false;
    }

    /** Strips a PDF subset tag ("ABCDEF+") prefix, mirroring FreeType's tt_skip_pdffont_random_tag. */
    private static String stripSubsetTag(String name)
    {
        if (name.length() > 7 && name.charAt(6) == '+')
        {
            boolean tag = true;
            for (int i = 0; i < 6; i++)
            {
                if (name.charAt(i) < 'A' || name.charAt(i) > 'Z')
                {
                    tag = false;
                    break;
                }
            }
            if (tag)
            {
                return name.substring(7);
            }
        }
        return name;
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
        tricky = isTrickyFont(font);

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

    /**
     * Runs the control value program untraced, then grid-fits the glyph with an execution tracer
     * attached, so the per-instruction trace can be diffed against FreeType's {@code ttinterp} trace.
     * For development/debugging only.
     *
     * @param gid the glyph id
     * @param ppem the pixels-per-em
     * @param out where to write the trace
     * @param tracePoint a glyph point index to log per instruction, or -1
     * @throws IOException if the font could not be read
     */
    synchronized void traceGlyph(int gid, int ppem, java.io.PrintStream out, int tracePoint)
            throws IOException
    {
        initialize();
        if (!available)
        {
            return;
        }
        setActivePpem(ppem);
        interpreter.setTracer(new ExecutionTracer(out, tracePoint));
        try
        {
            hint(gid, ppem, 0);
        }
        finally
        {
            interpreter.setTracer(null);
        }
    }

    /** Maximum composite nesting depth, to bound recursion on pathological fonts. */
    private static final int MAX_COMPONENT_DEPTH = 8;

    /** Runs all gating, then grid-fits the glyph, returning the executed zone or null on fallback. */
    private Hinted hint(int gid, int ppem)
    {
        if (!TrueTypeFont.isHintingEnabled() || ppem <= 0)
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
            // gasp gate: if a gasp table is present and does not request grid-fitting here, skip.
            // Tricky fonts are exempt: they need the bytecode to assemble/scale their glyphs at every
            // size, so FreeType ignores the gasp grid-fit bit for them (otherwise the glyph falls back
            // to its raw, unassembled outline at small ppem - e.g. MingLiU below 9ppem / low zoom).
            if (!tricky && gasp != null && !gasp.isGridFit(ppem))
            {
                return null;
            }
            setActivePpem(ppem);
            return hint(gid, ppem, 0);
        }
        catch (IOException | RuntimeException e)
        {
            logFailure(gid, ppem, e);
            return null;
        }
    }

    /**
     * Reports a glyph that could not be hinted. Only the first failure in a font is a warning carrying
     * the stack trace; the rest go to debug. Hinting is attempted per {@code (glyph, ppem)} pair, so a
     * font whose bytecode never runs - a malformed program, or one using something unimplemented - would
     * otherwise emit thousands of identical stack traces for a page of CJK text.
     */
    private void logFailure(int gid, int ppem, Exception e)
    {
        if (warned)
        {
            LOG.debug("hinting failed for glyph {} at {}ppem, using raw outline", gid, ppem, e);
            return;
        }
        warned = true;
        LOG.warn("hinting failed for glyph {} at {}ppem in font {}, using raw outline; further "
                + "failures in this font are logged at debug level", gid, ppem, fontName(), e);
    }

    /** The font's PostScript name for the warning above, best-effort - we are already handling a fault. */
    private String fontName()
    {
        try
        {
            return font.getName();
        }
        catch (IOException e)
        {
            return "<unknown>";
        }
    }

    /**
     * Re-runs the control value program if the ppem changed. The guard is load-bearing, not just an
     * optimisation: {@code setPpem} clears the storage area and twilight zone before running
     * {@code prep}, so re-running it per glyph would wipe the values {@code prep} seeded for the glyph
     * programs to read.
     */
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
        runProgram(zone, instructions, ppem, false);
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

        for (GlyfCompositeComp comp : composite.getComponents())
        {
            assembleComponent(comp, ppem, depth, zone);
        }
        int[] ends = zone.getContourEnds();
        for (int c = 0; c < contourCount; c++)
        {
            ends[c] = composite.getEndPtOfContours(c);
        }
        appendPhantomPoints(glyph, gid, ppem, pointCount, zone);

        int[] instructions = composite.getInstructions();
        if (instructions != null && instructions.length > 0)
        {
            runProgram(zone, instructions, ppem, true);
        }
        return new Hinted(composite, zone, pointCount);
    }

    /**
     * Hints one component glyph and writes its transformed/offset points into the composite's zone
     * arrays. The component's grid-fitted outline goes to the current arrays and its scaled-but-unhinted
     * outline to the original arrays, so the composite's instructions can measure original distances.
     */
    private void assembleComponent(GlyfCompositeComp comp, int ppem, int depth, Zone zone)
            throws IOException
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
        boolean[] onCurve = zone.getOnCurve();

        // scaled-but-unhinted component points (used as a fallback) and the unscaled font-unit ones
        int[] cOrgX = new int[count];
        int[] cOrgY = new int[count];
        int[] cUnsX = new int[count];
        int[] cUnsY = new int[count];
        for (int k = 0; k < count; k++)
        {
            cUnsX[k] = cgd.getXCoordinate(k);
            cUnsY[k] = cgd.getYCoordinate(k);
            cOrgX[k] = Fixed.scale(cUnsX[k], ppem, unitsPerEm);
            cOrgY[k] = Fixed.scale(cUnsY[k], ppem, unitsPerEm);
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
        // ROUND_XY_TO_GRID is set, so neither do we); the unscaled offset stays in font units
        int offsetX = Fixed.scale(comp.getXTranslate(), ppem, unitsPerEm);
        int offsetY = Fixed.scale(comp.getYTranslate(), ppem, unitsPerEm);
        int unsOffsetX = comp.getXTranslate();
        int unsOffsetY = comp.getYTranslate();

        int[] curX = zone.getCurrentX();
        int[] curY = zone.getCurrentY();
        int[] orgX = zone.getOriginalX();
        int[] orgY = zone.getOriginalY();
        int[] unsX = zone.getUnscaledX();
        int[] unsY = zone.getUnscaledY();
        for (int k = 0; k < count; k++)
        {
            curX[first + k] = comp.scaleX(cCurX[k], cCurY[k]) + offsetX;
            curY[first + k] = comp.scaleY(cCurX[k], cCurY[k]) + offsetY;
            // FreeType bakes each hinted component into the composite and copies cur -> org before
            // running the composite program, so the original equals the assembled hinted position
            // (a SHC/MDRP in the composite then measures zero movement for an unmoved component point)
            orgX[first + k] = curX[first + k];
            orgY[first + k] = curY[first + k];
            unsX[first + k] = comp.scaleX(cUnsX[k], cUnsY[k]) + unsOffsetX;
            unsY[first + k] = comp.scaleY(cUnsX[k], cUnsY[k]) + unsOffsetY;
        }
    }

    /** Clones the saved post-prep state, resets it for the glyph, and runs the program over the zone. */
    private void runProgram(Zone zone, int[] instructions, int ppem, boolean composite)
    {
        GraphicsState gs = interpreter.getSavedState().copy();
        gs.resetForGlyph();
        ExecutionContext ctx = interpreter.newContext(gs);
        ctx.setPpem(ppem);
        ctx.setGlyphZone(zone);
        // v40 grayscale "backward compatibility" applies to the glyph program only, never fpgm/prep
        // (which build control values via twilight-zone x/y moves that must not be suppressed), and is
        // disabled for tricky fonts, which need full bytecode hinting to assemble and scale their glyphs
        ctx.setBackwardCompatibility(!tricky);
        ctx.setComposite(composite);
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
        int[] unsX = zone.getUnscaledX();
        int[] unsY = zone.getUnscaledY();
        boolean[] onCurve = zone.getOnCurve();
        for (int i = 0; i < pointCount; i++)
        {
            int fx = gd.getXCoordinate(i);
            int fy = gd.getYCoordinate(i);
            unsX[i] = fx;
            unsY[i] = fy;
            int x = Fixed.scale(fx, ppem, unitsPerEm);
            int y = Fixed.scale(fy, ppem, unitsPerEm);
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
        appendPhantomPoints(glyph, gid, ppem, pointCount, zone);
        return zone;
    }

    private void appendPhantomPoints(GlyphData glyph, int gid, int ppem, int pointCount, Zone zone)
            throws IOException
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
            zone.getUnscaledX()[index] = px[i];
            zone.getUnscaledY()[index] = py[i];
            zone.getOriginalX()[index] = Fixed.scale(px[i], ppem, unitsPerEm);
            zone.getOriginalY()[index] = Fixed.scale(py[i], ppem, unitsPerEm);
            // FreeType rounds the phantom points to the grid before running the glyph program
            zone.getCurrentX()[index] = Fixed.round(zone.getOriginalX()[index]);
            zone.getCurrentY()[index] = Fixed.round(zone.getOriginalY()[index]);
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
