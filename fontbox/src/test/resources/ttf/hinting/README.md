# TrueType hinting verification tooling

These are developer/debugging tools for the FontBox TrueType bytecode interpreter
(`org.apache.fontbox.ttf.instruction`). They use **FreeType only as an offline oracle** — FreeType is
never linked, shipped, or a build dependency. The committed data files are plain coordinate/trace
facts, not derivatives of FreeType (see `hinting_plan.md` "Oracle licensing").

## Files

| File | Purpose |
|------|---------|
| `generate_golden.py` | Dumps FreeType's post-hinting outline points for the Tier-A fonts to `<font>-<ppem>.txt`. These back `GoldenHintingTest`. |
| `LiberationSans-Regular-*.txt` | The committed golden coordinate data (one file per ppem). |
| `trace_diff.py` | Aligns FreeType's per-instruction `ttinterp` trace against the FontBox interpreter's trace and reports the first divergence. |
| `README.md` | This file. |

## Golden coordinate test (CI)

`GoldenHintingTest` compares the interpreter's grid-fitted points against the committed `.txt` dumps.
To regenerate the dumps after intentional changes:

```sh
pip install --user freetype-py
python3 generate_golden.py
```

## Trace-diff harness (manual debugging)

When a glyph's final points differ from FreeType, the trace diff localizes the cause to a single
instruction far faster than staring at coordinates. It needs a FreeType built **with tracing** (the
stock library has it compiled out):

```sh
curl -LO https://download.savannah.gnu.org/releases/freetype/freetype-2.13.2.tar.gz
tar xzf freetype-2.13.2.tar.gz && cd freetype-2.13.2
./configure CFLAGS="-DFT_DEBUG_LEVEL_TRACE -g -O1" --disable-static
make -j
# point freetype-py at the result (replace its bundled copy or LD_PRELOAD it):
cp objs/.libs/libfreetype.so.6.* "$(python3 -c 'import freetype,os;print(os.path.dirname(freetype.__file__))')/libfreetype.so"
```

Then dump the FontBox trace for a glyph and diff it:

```sh
# 1. FontBox trace (from the fontbox module dir):
mvn -pl fontbox test -Dtest=GlyphTraceTool -Denforcer.skip=true \
    -Dtrace.gid=164 -Dtrace.ppem=11 -Dtrace.out=/tmp/our-trace.txt

# 2. diff against FreeType (from this directory):
FT2_DEBUG=ttinterp:7 python3 trace_diff.py --gid 164 --ppem 11 --ours /tmp/our-trace.txt --stack
```

Both traces use the line format `<pc>  <MNEMONIC>  # <stack window, top first>`. The tool compares the
program counter (catches control-flow divergence) and, with `--stack`, the operand window (catches a
diverging computed value — this is how the `DIV`-rounding bug was found). If neither diverges, the
remaining difference is a *silent* point-position computation in a point-moving opcode (MDRP/MIRP/IP/…)
that never reaches the stack — compare final point positions to find it.

The interpreter side is driven by `TrueTypeInterpreter.setTracer(ExecutionTracer)`; it is off in
normal operation.
