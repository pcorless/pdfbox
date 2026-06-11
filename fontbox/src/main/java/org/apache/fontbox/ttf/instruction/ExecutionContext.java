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
 * All mutable state for a single run of the interpreter, bundled into one object so opcode handlers
 * share a uniform {@code execute(ExecutionContext)} signature and new state can be added without
 * touching every handler. It holds the operand stack, storage area, scaled control values, the two
 * point zones, the {@link GraphicsState}, the current {@link BytecodeStream}, and the ppem the program
 * is running at.
 *
 * @author Apache PDFBox
 */
public class ExecutionContext
{
    private final TrueTypeInterpreter interpreter;
    private final GraphicsState graphicsState;

    private final int[] stack;
    private int stackPointer;

    private final int[] storage;
    private final int[] controlValues;

    private final Zone twilightZone;
    private Zone glyphZone;

    private int ppem;
    private int pointSize;
    private int unitsPerEm;

    private BytecodeStream stream;
    private int callDepth;
    private boolean returnFromFunction;

    /**
     * @param interpreter the owning interpreter (for function calls)
     * @param graphicsState the graphics state this run starts from
     * @param maxStackElements operand stack capacity
     * @param maxStorage storage area size
     * @param controlValues the scaled control values (F26Dot6), or null
     * @param maxTwilightPoints number of twilight-zone points
     */
    public ExecutionContext(TrueTypeInterpreter interpreter, GraphicsState graphicsState,
            int maxStackElements, int maxStorage, int[] controlValues, int maxTwilightPoints)
    {
        this.interpreter = interpreter;
        this.graphicsState = graphicsState;
        this.stack = new int[Math.max(maxStackElements, 1)];
        this.storage = new int[Math.max(maxStorage, 0)];
        this.controlValues = controlValues != null ? controlValues : new int[0];
        this.twilightZone = new Zone(Math.max(maxTwilightPoints, 0), 0);
    }

    /** @return the owning interpreter */
    public TrueTypeInterpreter getInterpreter()
    {
        return interpreter;
    }

    /** @return the graphics state */
    public GraphicsState getGraphicsState()
    {
        return graphicsState;
    }

    // --- operand stack ---------------------------------------------------

    /**
     * Pushes a value onto the operand stack.
     *
     * @param value the value to push
     * @throws HintingException on stack overflow
     */
    public void push(int value)
    {
        if (stackPointer >= stack.length)
        {
            throw new HintingException("interpreter stack overflow at " + stackPointer);
        }
        stack[stackPointer++] = value;
    }

    /**
     * Pops a value from the operand stack.
     *
     * @return the popped value
     * @throws HintingException on stack underflow
     */
    public int pop()
    {
        if (stackPointer <= 0)
        {
            throw new HintingException("interpreter stack underflow");
        }
        return stack[--stackPointer];
    }

    /**
     * Returns the value {@code n} positions below the top without removing it ({@code peek(0)} is the
     * top of stack).
     *
     * @param n depth below the top
     * @return the value at that depth
     * @throws HintingException if the depth is out of range
     */
    public int peek(int n)
    {
        int index = stackPointer - 1 - n;
        if (index < 0 || index >= stackPointer)
        {
            throw new HintingException("interpreter stack peek out of range: " + n);
        }
        return stack[index];
    }

    /** @return the current stack depth */
    public int getStackDepth()
    {
        return stackPointer;
    }

    /** Empties the operand stack. */
    public void clearStack()
    {
        stackPointer = 0;
    }

    // --- storage and control values --------------------------------------

    /** @return the storage area */
    public int[] getStorage()
    {
        return storage;
    }

    /** @return the scaled control values in F26Dot6 */
    public int[] getControlValues()
    {
        return controlValues;
    }

    // --- zones -----------------------------------------------------------

    /** @return the twilight zone (zone 0) */
    public Zone getTwilightZone()
    {
        return twilightZone;
    }

    /** @return the glyph zone (zone 1), or null if no glyph is loaded */
    public Zone getGlyphZone()
    {
        return glyphZone;
    }

    /** @param zone the glyph zone (zone 1) */
    public void setGlyphZone(Zone zone)
    {
        this.glyphZone = zone;
    }

    /**
     * Resolves a zone pointer (0 = twilight, 1 = glyph) to its {@link Zone}.
     *
     * @param zonePointer the zone pointer value
     * @return the corresponding zone
     * @throws HintingException if the pointer is invalid or the glyph zone is unset
     */
    public Zone getZone(int zonePointer)
    {
        if (zonePointer == 0)
        {
            return twilightZone;
        }
        if (zonePointer == 1)
        {
            if (glyphZone == null)
            {
                throw new HintingException("glyph zone referenced but not loaded");
            }
            return glyphZone;
        }
        throw new HintingException("invalid zone pointer: " + zonePointer);
    }

    // --- sizing ----------------------------------------------------------

    /** @return the active pixels-per-em */
    public int getPpem()
    {
        return ppem;
    }

    /** @param value the active pixels-per-em */
    public void setPpem(int value)
    {
        this.ppem = value;
    }

    /** @return the point size */
    public int getPointSize()
    {
        return pointSize;
    }

    /** @param value the point size */
    public void setPointSize(int value)
    {
        this.pointSize = value;
    }

    /** @return the font's unitsPerEm */
    public int getUnitsPerEm()
    {
        return unitsPerEm;
    }

    /** @param value the font's unitsPerEm */
    public void setUnitsPerEm(int value)
    {
        this.unitsPerEm = value;
    }

    // --- execution cursor and call state ---------------------------------

    /** @return the current bytecode stream */
    public BytecodeStream getStream()
    {
        return stream;
    }

    /** @param stream the current bytecode stream */
    public void setStream(BytecodeStream stream)
    {
        this.stream = stream;
    }

    /** @return the current call nesting depth */
    public int getCallDepth()
    {
        return callDepth;
    }

    /** Increments the call nesting depth. */
    public void enterCall()
    {
        callDepth++;
    }

    /** Decrements the call nesting depth. */
    public void leaveCall()
    {
        callDepth--;
    }

    /** @return true if an {@code ENDF} asked the current function body to return */
    public boolean isReturnFromFunction()
    {
        return returnFromFunction;
    }

    /** @param value whether the current function body should return */
    public void setReturnFromFunction(boolean value)
    {
        this.returnFromFunction = value;
    }
}
