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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tricky-font detection ({@link GlyphHinter#isTrickyFamily}). Tricky CJK fonts (MingLiU, DFKai-SB, ...)
 * assemble and scale glyphs from sub-pixel-sized components via the bytecode interpreter, so they must
 * be hinted with full control rather than the v40 grayscale "backward compatibility" restrictions that
 * suppress horizontal grid-fitting. Mirrors FreeType's tt_check_trickyness_family.
 */
class TrickyFontTest
{
    @Test
    void testKnownTrickyFamiliesAreDetected()
    {
        assertTrue(GlyphHinter.isTrickyFamily("MingLiU"));
        assertTrue(GlyphHinter.isTrickyFamily("PMingLiU"));
        assertTrue(GlyphHinter.isTrickyFamily("DFKai-SB"));
        // a substring match anywhere in the name still counts (e.g. "MingLiU-Bold")
        assertTrue(GlyphHinter.isTrickyFamily("MingLiU-ExtB"));
    }

    @Test
    void testSubsetTagIsStrippedBeforeMatching()
    {
        // PDF subset prefix "XOSNQA+" must not defeat the match (the embedded HA_20120316a2.pdf case)
        assertTrue(GlyphHinter.isTrickyFamily("XOSNQA+MingLiU"));
        assertTrue(GlyphHinter.isTrickyFamily("ABCDEF+DFKai-SB"));
    }

    @Test
    void testOrdinaryFontsAreNotTricky()
    {
        assertFalse(GlyphHinter.isTrickyFamily("LiberationSans"));
        assertFalse(GlyphHinter.isTrickyFamily("ArialMT"));
        assertFalse(GlyphHinter.isTrickyFamily("Helvetica"));
        assertFalse(GlyphHinter.isTrickyFamily(null));
        // a lowercase tag-like prefix is not a subset tag and must not be stripped into a false match
        assertFalse(GlyphHinter.isTrickyFamily("Times New Roman"));
    }

    @Test
    void testTrickyDetectedByTableChecksumsWhenRenamed()
    {
        // a renamed MingLiU (e.g. embedded as "HA_MingLiu") fails the name match but is caught by the
        // cvt/fpgm/prep table checksums - these are the real values from HA_20120316c_short.pdf
        assertFalse(GlyphHinter.isTrickyFamily("HA_MingLiu"));
        assertTrue(GlyphHinter.matchesTrickyChecksums(
                0x2E4L, 0x05BCF058L,   // cvt
                0x87C4L, 0x28233BF1L,  // fpgm
                0x1E1L, 0xA344A1EBL)); // prep (MingLiU 1996- entry)
    }

    @Test
    void testNonTrickyChecksumsDoNotMatch()
    {
        assertFalse(GlyphHinter.matchesTrickyChecksums(100, 0x11111111L, 200, 0x22222222L,
                300, 0x33333333L));
        // right checksums but wrong lengths must not match
        assertFalse(GlyphHinter.matchesTrickyChecksums(
                0x999L, 0x05BCF058L, 0x999L, 0x28233BF1L, 0x999L, 0xA344A1EBL));
    }
}
