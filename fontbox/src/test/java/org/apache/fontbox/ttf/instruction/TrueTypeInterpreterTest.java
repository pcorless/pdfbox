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
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Program-tier (Tier 2) tests: hand-assembled bytecode fed through the dispatch loop with no font.
 * These exercise the engine - dispatch, the push family, branching, function definition and calling,
 * and the {@link BytecodeStream} bounds checks - independently of any glyph.
 */
class TrueTypeInterpreterTest
{
    // opcodes used to assemble test programs
    private static final byte PUSHB1 = (byte) 0xB0; // PUSHB[0] - push one byte
    private static final byte PUSHB2 = (byte) 0xB1; // PUSHB[1] - push two bytes
    private static final byte NPUSHW = (byte) 0x41;
    private static final byte ADD = 0x60;
    private static final byte SUB = 0x61;
    private static final byte MUL = 0x63;
    private static final byte DUP = 0x20;
    private static final byte SWAP = 0x23;
    private static final byte DEPTH = 0x24;
    private static final byte ROLL = (byte) 0x8A;
    private static final byte GT = 0x52;
    private static final byte IF = 0x58;
    private static final byte ELSE = 0x1B;
    private static final byte EIF = 0x59;
    private static final byte JMPR = 0x1C;
    private static final byte FDEF = 0x2C;
    private static final byte ENDF = 0x2D;
    private static final byte CALL = 0x2B;
    private static final byte LOOPCALL = 0x2A;
    private static final byte MPPEM = 0x4B;

    private static TrueTypeInterpreter interpreter()
    {
        return new TrueTypeInterpreter(256, 16, 0, 2048);
    }

    private static int runTop(byte[] program)
    {
        ExecutionContext ctx = interpreter().executeProgram(program, 16);
        return ctx.peek(0);
    }

    @Test
    void testPushAndAdd()
    {
        // PUSHB[1] 2 3 ; ADD  ->  5
        assertEquals(5, runTop(new byte[] { PUSHB2, 2, 3, ADD }));
    }

    @Test
    void testNpushwSigned()
    {
        // NPUSHW 1 0xFFFF ; -> -1 on the stack
        assertEquals(-1, runTop(new byte[] { NPUSHW, 1, (byte) 0xFF, (byte) 0xFF }));
    }

    @Test
    void testArithmetic()
    {
        // 10 - 3 == 7
        assertEquals(7, runTop(new byte[] { PUSHB2, 10, 3, SUB }));
        // 64(=1.0) * 192(=3.0) == 192(=3.0) ... use F26Dot6: PUSHB 64, then need words; use small ints
        // 2.0 * 3.0 in F26Dot6: push 128 and 192 via NPUSHW
        assertEquals(Fixed.fromInt(6),
                runTop(new byte[] { NPUSHW, 2, 0, (byte) 128, 0, (byte) 192, MUL }));
    }

    @Test
    void testStackOps()
    {
        // DUP: push 7, dup, add -> 14
        assertEquals(14, runTop(new byte[] { PUSHB1, 7, DUP, ADD }));
        // SWAP then SUB: push 3,10 swap -> 10,3 ; SUB pops b=3,a=10 -> 7
        assertEquals(7, runTop(new byte[] { PUSHB2, 3, 10, SWAP, SUB }));
        // DEPTH after pushing three values -> 3
        assertEquals(3, runTop(new byte[] { PUSHB2, 1, 2, PUSHB1, 9, DEPTH }));
        // ROLL: 1 2 3 -> 2 3 1, top is 1
        assertEquals(1, runTop(new byte[] { PUSHB2, 1, 2, PUSHB1, 3, ROLL }));
    }

    @Test
    void testIfElseTrueBranch()
    {
        // push 1 (true) ; IF push 10 ELSE push 20 EIF -> 10
        assertEquals(10, runTop(new byte[] { PUSHB1, 1, IF, PUSHB1, 10, ELSE, PUSHB1, 20, EIF }));
    }

    @Test
    void testIfElseFalseBranch()
    {
        // push 0 (false) ; IF push 10 ELSE push 20 EIF -> 20
        assertEquals(20, runTop(new byte[] { PUSHB1, 0, IF, PUSHB1, 10, ELSE, PUSHB1, 20, EIF }));
    }

    @Test
    void testNestedIf()
    {
        // outer true, inner (5>3) true -> 99
        // PUSHB 1 ; IF [ PUSHB 5 3 ; GT ; IF PUSHB 99 ELSE PUSHB 1 EIF ] ELSE PUSHB 7 EIF
        byte[] program = new byte[] {
                PUSHB1, 1, IF,
                    PUSHB2, 5, 3, GT, IF,
                        PUSHB1, 99,
                    ELSE,
                        PUSHB1, 1,
                    EIF,
                ELSE,
                    PUSHB1, 7,
                EIF };
        assertEquals(99, runTop(program));
    }

    @Test
    void testJmpr()
    {
        // PUSHB 3 ; JMPR (jump +3 from the JMPR opcode) skips a push, lands on PUSHB 42
        // layout: [0]PUSHB1 [1]3 [2]JMPR [3]PUSHB1 [4]7(skipped) [5]PUSHB1 [6]42
        ExecutionContext ctx = interpreter().executeProgram(
                new byte[] { PUSHB1, 3, JMPR, PUSHB1, 7, PUSHB1, 42 }, 16);
        assertEquals(42, ctx.peek(0));
        assertEquals(1, ctx.getStackDepth()); // the skipped push never ran
    }

    @Test
    void testFunctionDefAndCall()
    {
        // define function 5 = "double the top" (DUP ADD); call it on 21 -> 42
        TrueTypeInterpreter interp = interpreter();
        interp.setFontProgram(new byte[] { PUSHB1, 5, FDEF, DUP, ADD, ENDF });
        interp.prepareFontProgram();
        assertEquals(1, interp.getFunctions().size());

        ExecutionContext ctx = interp.executeProgram(new byte[] { PUSHB1, 21, PUSHB1, 5, CALL }, 16);
        assertEquals(42, ctx.peek(0));
    }

    @Test
    void testLoopCall()
    {
        // function 1 = "add 1"; LOOPCALL it 3 times starting from 0 -> 3
        TrueTypeInterpreter interp = interpreter();
        interp.setFontProgram(new byte[] { PUSHB1, 1, FDEF, PUSHB1, 1, ADD, ENDF });
        interp.prepareFontProgram();

        // stack: value=0, count=3, fn=1 ; LOOPCALL pops fn then count
        ExecutionContext ctx = interp.executeProgram(
                new byte[] { PUSHB1, 0, PUSHB2, 3, 1, LOOPCALL }, 16);
        assertEquals(3, ctx.peek(0));
    }

    @Test
    void testCallDepthLimitTrips()
    {
        // function 0 calls itself unconditionally -> must trip the depth cap, not StackOverflowError
        TrueTypeInterpreter interp = interpreter();
        interp.setFontProgram(new byte[] { PUSHB1, 0, FDEF, PUSHB1, 0, CALL, ENDF });
        interp.prepareFontProgram();

        HintingException ex = assertThrows(HintingException.class,
                () -> interp.executeProgram(new byte[] { PUSHB1, 0, CALL }, 16));
        assertEquals(true, ex.getMessage().contains("call depth"));
    }

    @Test
    void testIdefDefinesOpcode()
    {
        // IDEF binds reserved opcode 0x83 to "push 42"; invoking 0x83 then runs that body.
        // PUSHB[0] 0x83 ; IDEF ; PUSHB[0] 42 ; ENDF ; <0x83>
        ExecutionContext ctx = interpreter().executeProgram(
                new byte[] { PUSHB1, (byte) 0x83, (byte) 0x89, PUSHB1, 42, ENDF, (byte) 0x83 }, 16);
        assertEquals(42, ctx.peek(0));
    }

    @Test
    void testUndefinedFunctionThrows()
    {
        assertThrows(HintingException.class,
                () -> interpreter().executeProgram(new byte[] { PUSHB1, 9, CALL }, 16));
    }

    @Test
    void testMppemReflectsPpem()
    {
        ExecutionContext ctx = interpreter().executeProgram(new byte[] { MPPEM }, 19);
        assertEquals(19, ctx.peek(0));
    }

    @Test
    void testUnsupportedOpcodeThrows()
    {
        // 0x28 is a reserved/unused opcode; unimplemented opcodes must throw, not no-op
        assertThrows(HintingException.class,
                () -> interpreter().executeProgram(new byte[] { 0x28 }, 16));
    }

    @Test
    void testControlValueScalingThroughPrep()
    {
        // raw cvt [2048] at 16 ppem, unitsPerEm 2048 -> scaled to 16px (1024 in F26Dot6)
        TrueTypeInterpreter interp = interpreter();
        interp.setControlValues(new int[] { 2048 });
        interp.setPpem(16, 16);
        // RCVT 0 -> the scaled value
        ExecutionContext ctx = interp.executeProgram(new byte[] { PUSHB1, 0, 0x45 }, 16);
        assertEquals(Fixed.fromInt(16), ctx.peek(0));
    }
}
