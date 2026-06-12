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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.junit.jupiter.api.Test;

/**
 * Golden (Tier 3) test: compares the FontBox interpreter's grid-fitted glyph points against a FreeType
 * reference dump on the same font, glyph and ppem. The reference data lives in
 * {@code ttf/hinting/<font>-<ppem>.txt}, produced offline by {@code generate_golden.py}; FreeType is
 * never a build or runtime dependency (see hinting_plan.md "Oracle licensing").
 * <p>
 * Both sides are integer F26Dot6 (64 units per pixel). The tolerance below is the residual difference
 * between this interpreter and FreeType's; it is documented and driven towards zero as the arithmetic
 * is refined.
 */
class GoldenHintingTest
{
    /**
     * Hard ceiling on any single coordinate's difference from FreeType, in F26Dot6 units (64 = 1px).
     * No point may ever be more than one pixel off.
     */
    private static final int MAX_DELTA = 64;

    /**
     * Minimum fraction of coordinates that must match FreeType to within one F26Dot6 unit (1/64 px).
     * <p>
     * Current state: ~98.6% within 1 ULP, ~60% byte-exact. The remaining outliers are concentrated on
     * the digit '3' at 11ppem (Y coordinates of its curved bowls), which differ by up to 1px - most
     * likely a DELTA/interpolation rounding detail at that one size. TODO drive this to 100% / exact.
     */
    private static final int MIN_WITHIN_ONE_PERCENT = 98;

    private static final int[] PPEMS = { 11, 13, 16, 24 };

    @Test
    void testLiberationSansAgainstFreeType() throws IOException
    {
        TrueTypeFont font;
        try (InputStream is = getClass().getResourceAsStream("/ttf/LiberationSans-Regular.ttf"))
        {
            font = new TTFParser().parse(new RandomAccessReadBuffer(is));
        }
        GlyphHinter hinter = new GlyphHinter(font);

        int worstDelta = 0;
        String worstWhere = "none";
        int compared = 0;
        int exact = 0;
        int withinOne = 0;

        for (int ppem : PPEMS)
        {
            List<GoldenGlyph> golden = loadGolden("/ttf/hinting/LiberationSans-Regular-" + ppem + ".txt");
            assertNotNull(golden);
            for (GoldenGlyph g : golden)
            {
                int[][] points = hinter.getHintedPointsF26Dot6(g.gid, ppem);
                assertNotNull(points, "no hinted points for gid " + g.gid + " at " + ppem + "ppem");
                assertTrue(points[0].length == g.x.length,
                        "point count mismatch for '" + g.ch + "' at " + ppem + "ppem: ours="
                                + points[0].length + " freetype=" + g.x.length);
                for (int i = 0; i < g.x.length; i++)
                {
                    int dx = Math.abs(points[0][i] - g.x[i]);
                    int dy = Math.abs(points[1][i] - g.y[i]);
                    int d = Math.max(dx, dy);
                    if (d == 0)
                    {
                        exact++;
                    }
                    if (d <= 1)
                    {
                        withinOne++;
                    }
                    if (d > worstDelta)
                    {
                        worstDelta = d;
                        worstWhere = "'" + g.ch + "' (gid " + g.gid + ") point " + i + " @" + ppem
                                + "ppem: ours=(" + points[0][i] + "," + points[1][i] + ") freetype=("
                                + g.x[i] + "," + g.y[i] + ")";
                    }
                    compared++;
                }
            }
        }
        int withinOnePercent = 100 * withinOne / compared;
        String summary = "compared " + compared + " coords; exact=" + exact + " ("
                + (100 * exact / compared) + "%) within1/64=" + withinOne + " (" + withinOnePercent
                + "%); worst delta " + worstDelta + "/64px at " + worstWhere;
        assertTrue(worstDelta <= MAX_DELTA, summary);
        assertTrue(withinOnePercent >= MIN_WITHIN_ONE_PERCENT, summary);
    }

    private List<GoldenGlyph> loadGolden(String resource) throws IOException
    {
        List<GoldenGlyph> glyphs = new ArrayList<>();
        try (InputStream is = getClass().getResourceAsStream(resource);
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(is, StandardCharsets.UTF_8)))
        {
            GoldenGlyph current = null;
            String line;
            while ((line = reader.readLine()) != null)
            {
                if (line.startsWith("glyph "))
                {
                    String[] parts = line.split(" ");
                    current = new GoldenGlyph(Integer.parseInt(parts[1]), parts[2]);
                    glyphs.add(current);
                }
                else if (line.startsWith("x "))
                {
                    current.x = parseInts(line.substring(2));
                }
                else if (line.startsWith("y "))
                {
                    current.y = parseInts(line.substring(2));
                }
            }
        }
        return glyphs;
    }

    private static int[] parseInts(String s)
    {
        if (s.isEmpty())
        {
            return new int[0];
        }
        String[] tokens = s.split(" ");
        int[] values = new int[tokens.length];
        for (int i = 0; i < tokens.length; i++)
        {
            values[i] = Integer.parseInt(tokens[i]);
        }
        return values;
    }

    private static final class GoldenGlyph
    {
        private final int gid;
        private final String ch;
        private int[] x;
        private int[] y;

        GoldenGlyph(int gid, String ch)
        {
            this.gid = gid;
            this.ch = ch;
        }
    }
}
