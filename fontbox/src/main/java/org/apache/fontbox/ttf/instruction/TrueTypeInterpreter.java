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

import java.util.HashMap;
import java.util.Map;

/**
 * The TrueType bytecode interpreter: the VM driver and the 256-entry opcode dispatch table.
 * <p>
 * This is the Phase 2 skeleton. It implements the execution engine (the {@link BytecodeStream} cursor
 * driven dispatch loop, function definition and calling with a depth cap, branching, the push family
 * and the stack/arithmetic/logical/storage opcodes) plus the size lifecycle ({@code fpgm} executed
 * once, {@code prep} executed per ppem change with the result saved as the per-glyph template). The
 * point-moving opcodes that grid-fit a glyph (MDRP, MIRP, IUP, IP, ...) are added in Phase 3; until
 * then their dispatch slots throw {@link HintingException}, which the caller catches per glyph.
 *
 * @author Apache PDFBox
 */
public class TrueTypeInterpreter
{
    /** Maximum {@code CALL}/{@code LOOPCALL} nesting depth, matching FreeType. */
    public static final int MAX_CALL_DEPTH = 64;

    /** A single opcode handler. */
    @FunctionalInterface
    private interface OpHandler
    {
        void execute(ExecutionContext ctx);
    }

    // opcodes referenced by the engine itself (control flow / push)
    private static final int NPUSHB = 0x40;
    private static final int NPUSHW = 0x41;
    private static final int PUSHB_BASE = 0xB0;
    private static final int PUSHW_BASE = 0xB8;
    private static final int ELSE = 0x1B;
    private static final int IF = 0x58;
    private static final int EIF = 0x59;
    private static final int FDEF = 0x2C;
    private static final int ENDF = 0x2D;

    private final OpHandler[] dispatch = new OpHandler[256];
    private final Map<Integer, FunctionDef> functions = new HashMap<>();

    private final int maxStackElements;
    private final int maxStorage;
    private final int maxTwilightPoints;
    private final int unitsPerEm;

    private byte[] fontProgram;
    private byte[] controlValueProgram;
    private int[] rawControlValues = new int[0];
    private int[] scaledControlValues = new int[0];

    private int ppem;
    private int pointSize;
    private GraphicsState savedState;

    /**
     * @param maxStackElements operand stack capacity (from maxp)
     * @param maxStorage storage area size (from maxp)
     * @param maxTwilightPoints twilight zone size (from maxp)
     * @param unitsPerEm the font's unitsPerEm (from head)
     */
    public TrueTypeInterpreter(int maxStackElements, int maxStorage, int maxTwilightPoints,
            int unitsPerEm)
    {
        this.maxStackElements = maxStackElements;
        this.maxStorage = maxStorage;
        this.maxTwilightPoints = maxTwilightPoints;
        this.unitsPerEm = unitsPerEm;
        buildDispatch();
    }

    // --- configuration ---------------------------------------------------

    /** @param program the raw {@code fpgm} bytecode, or null */
    public void setFontProgram(byte[] program)
    {
        this.fontProgram = program;
    }

    /** @param program the raw {@code prep} bytecode, or null */
    public void setControlValueProgram(byte[] program)
    {
        this.controlValueProgram = program;
    }

    /** @param values the raw control values in font units, or null */
    public void setControlValues(int[] values)
    {
        this.rawControlValues = values != null ? values : new int[0];
    }

    // --- lifecycle -------------------------------------------------------

    /**
     * Runs the font program ({@code fpgm}) once, populating the function table. Safe to call when
     * there is no font program.
     */
    public void prepareFontProgram()
    {
        functions.clear();
        if (fontProgram == null || fontProgram.length == 0)
        {
            return;
        }
        ExecutionContext ctx = newContext(new GraphicsState());
        run(ctx, new BytecodeStream(fontProgram));
    }

    /**
     * Establishes a new ppem: scales the control values, runs the control value program ({@code prep})
     * from a default graphics state, and saves the resulting state as the per-glyph template.
     *
     * @param ppemValue the pixels-per-em to render at
     * @param pointSizeValue the point size
     */
    public void setPpem(int ppemValue, int pointSizeValue)
    {
        this.ppem = ppemValue;
        this.pointSize = pointSizeValue;
        scaleControlValues();

        GraphicsState gs = new GraphicsState();
        if (controlValueProgram != null && controlValueProgram.length > 0)
        {
            ExecutionContext ctx = newContext(gs);
            run(ctx, new BytecodeStream(controlValueProgram));
        }
        savedState = gs;
    }

    private void scaleControlValues()
    {
        scaledControlValues = new int[rawControlValues.length];
        for (int i = 0; i < rawControlValues.length; i++)
        {
            scaledControlValues[i] = Fixed.scale(rawControlValues[i], ppem, unitsPerEm);
        }
    }

    /**
     * Builds a fresh execution context wired to this interpreter's sizes, scaled control values and
     * the current ppem.
     *
     * @param gs the graphics state the context starts from
     * @return a new execution context
     */
    public ExecutionContext newContext(GraphicsState gs)
    {
        ExecutionContext ctx = new ExecutionContext(this, gs, maxStackElements, maxStorage,
                scaledControlValues, maxTwilightPoints);
        ctx.setUnitsPerEm(unitsPerEm);
        ctx.setPpem(ppem);
        ctx.setPointSize(pointSize);
        return ctx;
    }

    /**
     * Test/utility entry point: runs a standalone bytecode program from the saved (post-{@code prep})
     * state, or a default state if no size has been set, and returns the resulting context so callers
     * can inspect the stack and state.
     *
     * @param program the bytecode to run
     * @param ppemValue the ppem to run at
     * @return the execution context after the program completes
     */
    public ExecutionContext executeProgram(byte[] program, int ppemValue)
    {
        this.ppem = ppemValue;
        GraphicsState gs = savedState != null ? savedState.copy() : new GraphicsState();
        ExecutionContext ctx = newContext(gs);
        run(ctx, new BytecodeStream(program));
        return ctx;
    }

    /** @return the saved post-{@code prep} graphics state, or null if no size has been set */
    public GraphicsState getSavedState()
    {
        return savedState;
    }

    /** @return the function table populated by {@code fpgm} */
    public Map<Integer, FunctionDef> getFunctions()
    {
        return functions;
    }

    // --- execution engine ------------------------------------------------

    /**
     * Runs a bytecode stream to completion (or until an {@code ENDF} returns from a function body),
     * dispatching each opcode through the table.
     *
     * @param ctx the execution context
     * @param s the stream to run
     */
    public void run(ExecutionContext ctx, BytecodeStream s)
    {
        BytecodeStream previous = ctx.getStream();
        ctx.setStream(s);
        try
        {
            while (s.hasNext() && !ctx.isReturnFromFunction())
            {
                s.markInstructionStart();
                int opcode = s.nextByte();
                dispatch[opcode].execute(ctx);
            }
        }
        finally
        {
            ctx.setStream(previous);
        }
    }

    /**
     * Calls the function with the given number, running its body until the matching {@code ENDF}.
     *
     * @param ctx the execution context
     * @param functionNumber the function to call
     * @throws HintingException if the function is undefined or the call depth is exceeded
     */
    public void callFunction(ExecutionContext ctx, int functionNumber)
    {
        FunctionDef def = functions.get(functionNumber);
        if (def == null)
        {
            throw new HintingException("call to undefined function " + functionNumber);
        }
        if (ctx.getCallDepth() >= MAX_CALL_DEPTH)
        {
            throw new HintingException("maximum call depth " + MAX_CALL_DEPTH + " exceeded");
        }
        ctx.enterCall();
        try
        {
            BytecodeStream body = new BytecodeStream(def.getProgram());
            body.seek(def.getEntryPoint());
            run(ctx, body);
            ctx.setReturnFromFunction(false);
        }
        finally
        {
            ctx.leaveCall();
        }
    }

    private void defineFunction(ExecutionContext ctx)
    {
        int functionNumber = ctx.pop();
        BytecodeStream s = ctx.getStream();
        functions.put(functionNumber, new FunctionDef(s.getCode(), s.position()));
        skipFunctionBody(s);
    }

    private void skipFunctionBody(BytecodeStream s)
    {
        while (s.hasNext())
        {
            int opcode = s.nextByte();
            if (opcode == ENDF)
            {
                return;
            }
            skipPushOperands(opcode, s);
        }
        throw new HintingException("FDEF without matching ENDF");
    }

    /**
     * On a false {@code IF}, skips forward to the matching {@code ELSE} or {@code EIF}, accounting for
     * nested {@code IF} blocks and for the inline operands of push instructions. Leaves the stream
     * positioned just after the terminator.
     */
    private void skipToElseOrEif(BytecodeStream s)
    {
        int depth = 0;
        while (s.hasNext())
        {
            int opcode = s.nextByte();
            if (opcode == IF)
            {
                depth++;
            }
            else if (opcode == EIF)
            {
                if (depth == 0)
                {
                    return;
                }
                depth--;
            }
            else if (opcode == ELSE && depth == 0)
            {
                return;
            }
            else
            {
                skipPushOperands(opcode, s);
            }
        }
        throw new HintingException("IF without matching EIF");
    }

    /**
     * After a true {@code IF} branch reaches its {@code ELSE}, skips the else-branch to the matching
     * {@code EIF}.
     */
    private void skipToEif(BytecodeStream s)
    {
        int depth = 0;
        while (s.hasNext())
        {
            int opcode = s.nextByte();
            if (opcode == IF)
            {
                depth++;
            }
            else if (opcode == EIF)
            {
                if (depth == 0)
                {
                    return;
                }
                depth--;
            }
            else
            {
                skipPushOperands(opcode, s);
            }
        }
        throw new HintingException("ELSE without matching EIF");
    }

    /** Advances the stream past the inline operands of a push opcode; a no-op for other opcodes. */
    private void skipPushOperands(int opcode, BytecodeStream s)
    {
        if (opcode == NPUSHB)
        {
            s.skip(s.nextByte());
        }
        else if (opcode == NPUSHW)
        {
            s.skip(2 * s.nextByte());
        }
        else if (opcode >= PUSHB_BASE && opcode <= PUSHB_BASE + 7)
        {
            s.skip(opcode - PUSHB_BASE + 1);
        }
        else if (opcode >= PUSHW_BASE && opcode <= PUSHW_BASE + 7)
        {
            s.skip(2 * (opcode - PUSHW_BASE + 1));
        }
    }

    // --- dispatch table --------------------------------------------------

    private void buildDispatch()
    {
        for (int i = 0; i < dispatch.length; i++)
        {
            final int opcode = i;
            dispatch[i] = ctx ->
            {
                throw new HintingException(
                        String.format("unsupported TrueType opcode 0x%02X", opcode));
            };
        }

        installPushOps();
        installStackOps();
        installArithmeticOps();
        installLogicalOps();
        installFlowOps();
        installStateOps();
        installStorageAndCvtOps();
        installMiscOps();
    }

    private void installPushOps()
    {
        dispatch[NPUSHB] = ctx ->
        {
            int n = ctx.getStream().nextByte();
            for (int i = 0; i < n; i++)
            {
                ctx.push(ctx.getStream().nextByte());
            }
        };
        dispatch[NPUSHW] = ctx ->
        {
            int n = ctx.getStream().nextByte();
            for (int i = 0; i < n; i++)
            {
                ctx.push(ctx.getStream().nextWord());
            }
        };
        for (int k = 0; k < 8; k++)
        {
            final int count = k + 1;
            dispatch[PUSHB_BASE + k] = ctx ->
            {
                for (int i = 0; i < count; i++)
                {
                    ctx.push(ctx.getStream().nextByte());
                }
            };
            dispatch[PUSHW_BASE + k] = ctx ->
            {
                for (int i = 0; i < count; i++)
                {
                    ctx.push(ctx.getStream().nextWord());
                }
            };
        }
    }

    private void installStackOps()
    {
        dispatch[0x20] = ctx -> ctx.push(ctx.peek(0));                 // DUP
        dispatch[0x21] = ExecutionContext::pop;                       // POP
        dispatch[0x22] = ExecutionContext::clearStack;                // CLEAR
        dispatch[0x23] = ctx ->                                       // SWAP
        {
            int a = ctx.pop();
            int b = ctx.pop();
            ctx.push(a);
            ctx.push(b);
        };
        dispatch[0x24] = ctx -> ctx.push(ctx.getStackDepth());        // DEPTH
        dispatch[0x25] = ctx -> ctx.push(ctx.peek(ctx.pop() - 1));    // CINDEX
        dispatch[0x26] = ctx ->                                       // MINDEX
        {
            int k = ctx.pop();
            int[] tmp = new int[k];
            for (int i = 0; i < k; i++)
            {
                tmp[i] = ctx.pop();
            }
            for (int i = k - 2; i >= 0; i--)
            {
                ctx.push(tmp[i]);
            }
            ctx.push(tmp[k - 1]);
        };
        dispatch[0x8A] = ctx ->                                       // ROLL
        {
            int c = ctx.pop();
            int b = ctx.pop();
            int a = ctx.pop();
            ctx.push(b);
            ctx.push(c);
            ctx.push(a);
        };
    }

    private void installArithmeticOps()
    {
        dispatch[0x60] = ctx -> binary(ctx, (a, b) -> a + b);            // ADD
        dispatch[0x61] = ctx -> binary(ctx, (a, b) -> a - b);            // SUB
        dispatch[0x62] = ctx -> binary(ctx, Fixed::div);                 // DIV
        dispatch[0x63] = ctx -> binary(ctx, Fixed::mul);                 // MUL
        dispatch[0x64] = ctx -> ctx.push(Math.abs(ctx.pop()));           // ABS
        dispatch[0x65] = ctx -> ctx.push(-ctx.pop());                    // NEG
        dispatch[0x66] = ctx -> ctx.push(Fixed.floor(ctx.pop()));        // FLOOR
        dispatch[0x67] = ctx -> ctx.push(Fixed.ceil(ctx.pop()));         // CEILING
        dispatch[0x8B] = ctx -> binary(ctx, Math::max);                  // MAX
        dispatch[0x8C] = ctx -> binary(ctx, Math::min);                  // MIN
    }

    private void installLogicalOps()
    {
        dispatch[0x50] = ctx -> binary(ctx, (a, b) -> bool(a < b));      // LT
        dispatch[0x51] = ctx -> binary(ctx, (a, b) -> bool(a <= b));     // LTEQ
        dispatch[0x52] = ctx -> binary(ctx, (a, b) -> bool(a > b));      // GT
        dispatch[0x53] = ctx -> binary(ctx, (a, b) -> bool(a >= b));     // GTEQ
        dispatch[0x54] = ctx -> binary(ctx, (a, b) -> bool(a == b));     // EQ
        dispatch[0x55] = ctx -> binary(ctx, (a, b) -> bool(a != b));     // NEQ
        dispatch[0x56] = ctx -> ctx.push(bool(((Fixed.round(ctx.pop()) >> 6) & 1) != 0)); // ODD
        dispatch[0x57] = ctx -> ctx.push(bool(((Fixed.round(ctx.pop()) >> 6) & 1) == 0)); // EVEN
        dispatch[0x5A] = ctx -> binary(ctx, (a, b) -> bool(a != 0 && b != 0)); // AND
        dispatch[0x5B] = ctx -> binary(ctx, (a, b) -> bool(a != 0 || b != 0)); // OR
        dispatch[0x5C] = ctx -> ctx.push(bool(ctx.pop() == 0));          // NOT
    }

    private void installFlowOps()
    {
        dispatch[IF] = ctx ->
        {
            if (ctx.pop() == 0)
            {
                skipToElseOrEif(ctx.getStream());
            }
        };
        dispatch[ELSE] = ctx -> skipToEif(ctx.getStream());
        dispatch[EIF] = ctx -> { /* no-op terminator */ };
        dispatch[0x1C] = ctx ->                                          // JMPR
        {
            int offset = ctx.pop();
            BytecodeStream s = ctx.getStream();
            s.seek(s.instructionStart() + offset);
        };
        dispatch[0x78] = ctx ->                                          // JROT
        {
            int e = ctx.pop();
            int offset = ctx.pop();
            if (e != 0)
            {
                BytecodeStream s = ctx.getStream();
                s.seek(s.instructionStart() + offset);
            }
        };
        dispatch[0x79] = ctx ->                                          // JROF
        {
            int e = ctx.pop();
            int offset = ctx.pop();
            if (e == 0)
            {
                BytecodeStream s = ctx.getStream();
                s.seek(s.instructionStart() + offset);
            }
        };
        dispatch[FDEF] = this::defineFunction;
        dispatch[ENDF] = ctx -> ctx.setReturnFromFunction(true);
        dispatch[0x2B] = ctx -> callFunction(ctx, ctx.pop());            // CALL
        dispatch[0x2A] = ctx ->                                          // LOOPCALL
        {
            int functionNumber = ctx.pop();
            int count = ctx.pop();
            for (int i = 0; i < count; i++)
            {
                callFunction(ctx, functionNumber);
            }
        };
    }

    private void installStateOps()
    {
        dispatch[0x17] = ctx -> ctx.getGraphicsState().setLoop(ctx.pop());            // SLOOP
        dispatch[0x10] = ctx -> ctx.getGraphicsState().setRp0(ctx.pop());            // SRP0
        dispatch[0x11] = ctx -> ctx.getGraphicsState().setRp1(ctx.pop());            // SRP1
        dispatch[0x12] = ctx -> ctx.getGraphicsState().setRp2(ctx.pop());            // SRP2
        dispatch[0x1A] = ctx -> ctx.getGraphicsState().setMinimumDistance(ctx.pop()); // SMD
        dispatch[0x5E] = ctx -> ctx.getGraphicsState().setDeltaBase(ctx.pop());      // SDB
        dispatch[0x5F] = ctx -> ctx.getGraphicsState().setDeltaShift(ctx.pop());     // SDS
        dispatch[0x1D] = ctx -> ctx.getGraphicsState().setControlValueCutIn(ctx.pop()); // SCVTCI
        dispatch[0x1E] = ctx -> ctx.getGraphicsState().setSingleWidthCutIn(ctx.pop()); // SSWCI
        dispatch[0x1F] = ctx -> ctx.getGraphicsState().setSingleWidthValue(ctx.pop()); // SSW

        dispatch[0x18] = roundState(GraphicsState.ROUND_TO_GRID);        // RTG
        dispatch[0x19] = roundState(GraphicsState.ROUND_TO_HALF_GRID);   // RTHG
        dispatch[0x3D] = roundState(GraphicsState.ROUND_TO_DOUBLE_GRID); // RTDG
        dispatch[0x7C] = roundState(GraphicsState.ROUND_UP_TO_GRID);     // RUTG
        dispatch[0x7D] = roundState(GraphicsState.ROUND_DOWN_TO_GRID);   // RDTG
        dispatch[0x7A] = roundState(GraphicsState.ROUND_OFF);            // ROFF
        dispatch[0x76] = ctx ->                                          // SROUND
        {
            ctx.pop();
            ctx.getGraphicsState().setRoundState(GraphicsState.ROUND_SUPER);
        };
        dispatch[0x77] = ctx ->                                          // S45ROUND
        {
            ctx.pop();
            ctx.getGraphicsState().setRoundState(GraphicsState.ROUND_SUPER_45);
        };
    }

    private void installStorageAndCvtOps()
    {
        dispatch[0x43] = ctx ->                                          // RS
        {
            int index = ctx.pop();
            ctx.push(read(ctx.getStorage(), index, "storage"));
        };
        dispatch[0x42] = ctx ->                                          // WS
        {
            int value = ctx.pop();
            int index = ctx.pop();
            write(ctx.getStorage(), index, value, "storage");
        };
        dispatch[0x45] = ctx ->                                          // RCVT
        {
            int index = ctx.pop();
            ctx.push(read(ctx.getControlValues(), index, "cvt"));
        };
        dispatch[0x44] = ctx ->                                          // WCVTP
        {
            int value = ctx.pop();
            int index = ctx.pop();
            write(ctx.getControlValues(), index, value, "cvt");
        };
        dispatch[0x70] = ctx ->                                          // WCVTF
        {
            int value = ctx.pop();
            int index = ctx.pop();
            write(ctx.getControlValues(), index,
                    Fixed.scale(value, ctx.getPpem(), ctx.getUnitsPerEm()), "cvt");
        };
    }

    private void installMiscOps()
    {
        dispatch[0x4B] = ctx -> ctx.push(ctx.getPpem());                 // MPPEM
        dispatch[0x4C] = ctx -> ctx.push(ctx.getPointSize());            // MPS
        dispatch[0x4F] = ExecutionContext::pop;                          // DEBUG (pops, no-op)
        dispatch[0x7E] = ExecutionContext::pop;                          // SANGW (deprecated, pops)
        dispatch[0x7F] = ctx -> { /* AA - deprecated no-op */ };         // AA
        dispatch[0x88] = ctx ->                                          // GETINFO
        {
            int selector = ctx.pop();
            int result = 0;
            if ((selector & 0x0001) != 0)
            {
                // report a rasterizer version; grayscale/rotation/stretch bits are added in Phase 3
                result |= 35;
            }
            ctx.push(result);
        };
    }

    // --- handler helpers -------------------------------------------------

    @FunctionalInterface
    private interface IntBinaryOp
    {
        int apply(int a, int b);
    }

    private static void binary(ExecutionContext ctx, IntBinaryOp op)
    {
        int b = ctx.pop();
        int a = ctx.pop();
        ctx.push(op.apply(a, b));
    }

    private static int bool(boolean value)
    {
        return value ? 1 : 0;
    }

    private static OpHandler roundState(int state)
    {
        return ctx -> ctx.getGraphicsState().setRoundState(state);
    }

    private static int read(int[] array, int index, String name)
    {
        if (index < 0 || index >= array.length)
        {
            throw new HintingException(name + " index out of range: " + index);
        }
        return array[index];
    }

    private static void write(int[] array, int index, int value, String name)
    {
        if (index < 0 || index >= array.length)
        {
            throw new HintingException(name + " index out of range: " + index);
        }
        array[index] = value;
    }
}
