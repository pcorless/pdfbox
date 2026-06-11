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

/**
 * The TrueType interpreter graphics state: projection/freedom vectors, reference and zone pointers,
 * round state, and the various cut-ins and distances. Two operations matter for correctness and were
 * the source of bugs in prior implementations:
 * <ul>
 *   <li>{@link #copy()} performs a <em>deep</em> copy of the {@link Vector} fields, so mutating a
 *       per-glyph clone never contaminates the saved post-{@code prep} template.</li>
 *   <li>{@link #resetForGlyph()} resets only the fields the TrueType spec re-initialises at the start
 *       of each glyph program (the vectors, reference points, zone pointers and loop counter), while
 *       leaving the values established by {@code prep} (round state, cut-ins, delta base/shift, etc.)
 *       intact.</li>
 * </ul>
 *
 * @author Apache PDFBox
 */
public class GraphicsState
{
    /** Round to grid - the default round state. */
    public static final int ROUND_TO_GRID = 0;
    /** Round to half grid. */
    public static final int ROUND_TO_HALF_GRID = 1;
    /** Round to double grid. */
    public static final int ROUND_TO_DOUBLE_GRID = 2;
    /** Round down to grid. */
    public static final int ROUND_DOWN_TO_GRID = 3;
    /** Round up to grid. */
    public static final int ROUND_UP_TO_GRID = 4;
    /** Rounding off. */
    public static final int ROUND_OFF = 5;
    /** Super round (set by SROUND). */
    public static final int ROUND_SUPER = 6;
    /** Super round 45 degrees (set by S45ROUND). */
    public static final int ROUND_SUPER_45 = 7;

    private Vector projectionVector;
    private Vector freedomVector;
    private Vector dualProjectionVector;

    private int rp0;
    private int rp1;
    private int rp2;

    private int zp0;
    private int zp1;
    private int zp2;

    private int loop;
    private int roundState;

    private int minimumDistance;
    private int controlValueCutIn;
    private int singleWidthCutIn;
    private int singleWidthValue;
    private int deltaBase;
    private int deltaShift;
    private boolean autoFlip;
    private int scanControl;
    private int scanType;
    private int instructControl;

    /**
     * Creates a graphics state with the spec-mandated default values. This is the state before the
     * font's {@code fpgm}/{@code prep} programs run.
     */
    public GraphicsState()
    {
        projectionVector = Vector.xAxis();
        freedomVector = Vector.xAxis();
        dualProjectionVector = Vector.xAxis();
        rp0 = 0;
        rp1 = 0;
        rp2 = 0;
        zp0 = 1;
        zp1 = 1;
        zp2 = 1;
        loop = 1;
        roundState = ROUND_TO_GRID;
        minimumDistance = Fixed.ONE;          // 1 pixel
        controlValueCutIn = 17 * Fixed.ONE / 16; // 17/16 pixel = 68
        singleWidthCutIn = 0;
        singleWidthValue = 0;
        deltaBase = 9;
        deltaShift = 3;
        autoFlip = true;
        scanControl = 0;
        scanType = 0;
        instructControl = 0;
    }

    private GraphicsState(GraphicsState src)
    {
        projectionVector = src.projectionVector.copy();
        freedomVector = src.freedomVector.copy();
        dualProjectionVector = src.dualProjectionVector.copy();
        rp0 = src.rp0;
        rp1 = src.rp1;
        rp2 = src.rp2;
        zp0 = src.zp0;
        zp1 = src.zp1;
        zp2 = src.zp2;
        loop = src.loop;
        roundState = src.roundState;
        minimumDistance = src.minimumDistance;
        controlValueCutIn = src.controlValueCutIn;
        singleWidthCutIn = src.singleWidthCutIn;
        singleWidthValue = src.singleWidthValue;
        deltaBase = src.deltaBase;
        deltaShift = src.deltaShift;
        autoFlip = src.autoFlip;
        scanControl = src.scanControl;
        scanType = src.scanType;
        instructControl = src.instructControl;
    }

    /**
     * Returns an independent deep copy, with the {@link Vector} fields cloned rather than shared.
     *
     * @return a deep copy of this graphics state
     */
    public GraphicsState copy()
    {
        return new GraphicsState(this);
    }

    /**
     * Resets the per-glyph graphics state fields to their defaults, as the TrueType spec requires at
     * the start of each glyph's instruction stream. The projection, freedom and dual-projection
     * vectors return to the x axis; the reference points reset to 0; the zone pointers reset to the
     * glyph zone (1); the loop counter resets to 1. Fields configured by {@code prep} (round state,
     * cut-ins, minimum distance, delta base/shift, single width, auto-flip, scan control) are left
     * untouched.
     */
    public void resetForGlyph()
    {
        projectionVector.set(Fixed.ONE_F2DOT14, 0);
        freedomVector.set(Fixed.ONE_F2DOT14, 0);
        dualProjectionVector.set(Fixed.ONE_F2DOT14, 0);
        rp0 = 0;
        rp1 = 0;
        rp2 = 0;
        zp0 = 1;
        zp1 = 1;
        zp2 = 1;
        loop = 1;
    }

    /** @return the projection vector */
    public Vector getProjectionVector()
    {
        return projectionVector;
    }

    /** @return the freedom vector */
    public Vector getFreedomVector()
    {
        return freedomVector;
    }

    /** @return the dual projection vector */
    public Vector getDualProjectionVector()
    {
        return dualProjectionVector;
    }

    /** @return reference point 0 */
    public int getRp0()
    {
        return rp0;
    }

    /** @param value reference point 0 */
    public void setRp0(int value)
    {
        rp0 = value;
    }

    /** @return reference point 1 */
    public int getRp1()
    {
        return rp1;
    }

    /** @param value reference point 1 */
    public void setRp1(int value)
    {
        rp1 = value;
    }

    /** @return reference point 2 */
    public int getRp2()
    {
        return rp2;
    }

    /** @param value reference point 2 */
    public void setRp2(int value)
    {
        rp2 = value;
    }

    /** @return zone pointer 0 */
    public int getZp0()
    {
        return zp0;
    }

    /** @param value zone pointer 0 */
    public void setZp0(int value)
    {
        zp0 = value;
    }

    /** @return zone pointer 1 */
    public int getZp1()
    {
        return zp1;
    }

    /** @param value zone pointer 1 */
    public void setZp1(int value)
    {
        zp1 = value;
    }

    /** @return zone pointer 2 */
    public int getZp2()
    {
        return zp2;
    }

    /** @param value zone pointer 2 */
    public void setZp2(int value)
    {
        zp2 = value;
    }

    /** @return the loop counter */
    public int getLoop()
    {
        return loop;
    }

    /** @param value the loop counter */
    public void setLoop(int value)
    {
        loop = value;
    }

    /** @return the round state */
    public int getRoundState()
    {
        return roundState;
    }

    /** @param value the round state */
    public void setRoundState(int value)
    {
        roundState = value;
    }

    /** @return the minimum distance in F26Dot6 */
    public int getMinimumDistance()
    {
        return minimumDistance;
    }

    /** @param value the minimum distance in F26Dot6 */
    public void setMinimumDistance(int value)
    {
        minimumDistance = value;
    }

    /** @return the control value cut-in in F26Dot6 */
    public int getControlValueCutIn()
    {
        return controlValueCutIn;
    }

    /** @param value the control value cut-in in F26Dot6 */
    public void setControlValueCutIn(int value)
    {
        controlValueCutIn = value;
    }

    /** @return the single width cut-in in F26Dot6 */
    public int getSingleWidthCutIn()
    {
        return singleWidthCutIn;
    }

    /** @param value the single width cut-in in F26Dot6 */
    public void setSingleWidthCutIn(int value)
    {
        singleWidthCutIn = value;
    }

    /** @return the single width value in F26Dot6 */
    public int getSingleWidthValue()
    {
        return singleWidthValue;
    }

    /** @param value the single width value in F26Dot6 */
    public void setSingleWidthValue(int value)
    {
        singleWidthValue = value;
    }

    /** @return the delta base */
    public int getDeltaBase()
    {
        return deltaBase;
    }

    /** @param value the delta base */
    public void setDeltaBase(int value)
    {
        deltaBase = value;
    }

    /** @return the delta shift */
    public int getDeltaShift()
    {
        return deltaShift;
    }

    /** @param value the delta shift */
    public void setDeltaShift(int value)
    {
        deltaShift = value;
    }

    /** @return whether auto-flip is enabled */
    public boolean isAutoFlip()
    {
        return autoFlip;
    }

    /** @param value whether auto-flip is enabled */
    public void setAutoFlip(boolean value)
    {
        autoFlip = value;
    }

    /** @return the scan control flags */
    public int getScanControl()
    {
        return scanControl;
    }

    /** @param value the scan control flags */
    public void setScanControl(int value)
    {
        scanControl = value;
    }

    /** @return the scan type */
    public int getScanType()
    {
        return scanType;
    }

    /** @param value the scan type */
    public void setScanType(int value)
    {
        scanType = value;
    }

    /** @return the instruction control flags */
    public int getInstructControl()
    {
        return instructControl;
    }

    /** @param value the instruction control flags */
    public void setInstructControl(int value)
    {
        instructControl = value;
    }
}
