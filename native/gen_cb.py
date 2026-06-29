#!/usr/bin/env python3
# Faithful port of codec2 generate_codebook.c (Bruce Perens), enough to emit
# byte-identically-sized codebook .c files. Usage:
#   gen_cb.py <out.c> <array_name> <in1.txt> [in2.txt ...]
import sys, math

HEADER = (
"/* THIS IS A GENERATED FILE. Edit generate_codebook.c and its input */\n\n"
"/*\n"
" * This intermediary file and the files that used to create it are under \n"
" * The LGPL. See the file COPYING.\n"
" */\n\n"
'#include "defines.h"\n\n'
)

def fmt_g(f):
    # mimic C printf("%g")
    return "%g" % f

def load(path):
    toks = []
    with open(path, "r", encoding="utf-8", errors="ignore") as fh:
        for line in fh:
            h = line.find("#")
            if h >= 0:
                line = line[:h]
            for t in line.replace(",", " ").split():
                # keep only things that look numeric
                try:
                    toks.append(float(t))
                except ValueError:
                    pass
    k = int(toks[0]); m = int(toks[1])
    cb = toks[2:2 + k * m]
    if len(cb) != k * m:
        sys.stderr.write("%s: short read %d != %d\n" % (path, len(cb), k * m))
        sys.exit(1)
    return {"k": k, "m": m, "cb": cb}

def dump_array(out, b, index):
    limit = b["k"] * b["m"]
    out.append("#ifdef __EMBEDDED__\n")
    out.append("static const float codes%d[] = {\n" % index)
    out.append("#else\n")
    out.append("static float codes%d[] = {\n" % index)
    out.append("#endif\n")
    line = []
    for i in range(limit):
        s = "  " + fmt_g(b["cb"][i])
        if i < limit - 1:
            s += ","
        line.append(s)
        if ((i + 1) % b["k"]) == 0:
            out.append("".join(line) + "\n")
            line = []
    if line:
        out.append("".join(line))
    out.append("};\n")

def dump_struct(out, b, index):
    out.append("  {\n")
    out.append("    %d,\n" % b["k"])
    out.append("    %d,\n" % int(round(math.log(b["m"]) / math.log(2))))
    out.append("    %d,\n" % b["m"])
    out.append("    codes%d\n" % index)
    out.append("  }")

def main():
    out_path = sys.argv[1]
    name = sys.argv[2]
    files = sys.argv[3:]
    cbs = [load(f) for f in files]
    out = [HEADER]
    for i, (f, b) in enumerate(zip(files, cbs)):
        out.append("  /* %s */\n" % f)
        dump_array(out, b, i)
    out.append("\nconst struct lsp_codebook %s[] = {\n" % name)
    for i, f in enumerate(files):
        out.append("  /* %s */\n" % f)
        dump_struct(out, cbs[i], i)
        out.append(",\n")
    out.append("  { 0, 0, 0, 0 }\n")
    out.append("};\n")
    with open(out_path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("".join(out))
    print("wrote %s (%d codebooks)" % (out_path, len(files)))

if __name__ == "__main__":
    main()
