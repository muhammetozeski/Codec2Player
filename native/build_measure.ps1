$ErrorActionPreference = 'Stop'
$SRC  = 'C:\E\!\Downloads\Compressed\Codec2Recorder-main\Codec2Recorder-main\app\src\main\codec2\src'
$ROOT = 'C:\Users\Portakal\AppData\Local\Temp\claude\C--E-KodlamaProjeleri-JavaAndroidProjeleri-Codec2Player\1e557031-b7e8-4478-a6d6-3d9aa41d9591\scratchpad'
$WORK = Join-Path $ROOT 'c2meas'
$TC   = 'C:\E\kp\scoop\persist\android-clt\ndk\28.2.13676358\toolchains\llvm\prebuilt\windows-x86_64\bin'
$CC    = Join-Path $TC 'armv7a-linux-androideabi21-clang.cmd'
$STRIP = Join-Path $TC 'llvm-strip.exe'
$SIZE  = Join-Path $TC 'llvm-size.exe'

Remove-Item $WORK -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $WORK | Out-Null
New-Item -ItemType Directory -Force (Join-Path $WORK 'codec2') | Out-Null
New-Item -ItemType Directory -Force (Join-Path $WORK 'obj') | Out-Null

# headers
Copy-Item (Join-Path $SRC '*.h') $WORK
# version.h stub
@'
#ifndef CODEC2_VERSION_H
#define CODEC2_VERSION_H
#define CODEC2_VERSION_MAJOR 1
#define CODEC2_VERSION_MINOR 0
#define CODEC2_VERSION_PATCH 4
#define CODEC2_VERSION "1.0.4"
#endif
'@ | Set-Content -Encoding ascii (Join-Path $WORK 'codec2\version.h')

# hand-written sources
$HAND = 'dump.c','lpc.c','nlp.c','postfilter.c','sine.c','codec2.c','codec2_fft.c',
        'kiss_fft.c','kiss_fftr.c','interp.c','lsp.c','mbest.c','newamp1.c','newamp2.c',
        'phase.c','quantise.c','pack.c'
foreach ($f in $HAND) { Copy-Item (Join-Path $SRC $f) $WORK }

# generate the 8 codebook .c files
$D = Join-Path $SRC 'codebook'
function J($n){ Join-Path $D $n }
$gen = Join-Path $ROOT 'gen_cb.py'
$PY  = 'C:\E\kp\scoop\apps\python\current\python.exe'
& $PY $gen (Join-Path $WORK 'codebook.c')              lsp_cb            (J 'lsp1.txt') (J 'lsp2.txt') (J 'lsp3.txt') (J 'lsp4.txt') (J 'lsp5.txt') (J 'lsp6.txt') (J 'lsp7.txt') (J 'lsp8.txt') (J 'lsp9.txt') (J 'lsp10.txt')
& $PY $gen (Join-Path $WORK 'codebookd.c')             lsp_cbd           (J 'dlsp1.txt') (J 'dlsp2.txt') (J 'dlsp3.txt') (J 'dlsp4.txt') (J 'dlsp5.txt') (J 'dlsp6.txt') (J 'dlsp7.txt') (J 'dlsp8.txt') (J 'dlsp9.txt') (J 'dlsp10.txt')
& $PY $gen (Join-Path $WORK 'codebookjmv.c')           lsp_cbjmv         (J 'lspjmv1.txt') (J 'lspjmv2.txt') (J 'lspjmv3.txt')
& $PY $gen (Join-Path $WORK 'codebookge.c')            ge_cb             (J 'gecb.txt')
& $PY $gen (Join-Path $WORK 'codebooknewamp1.c')       newamp1vq_cb      (J 'train_120_1.txt') (J 'train_120_2.txt')
& $PY $gen (Join-Path $WORK 'codebooknewamp1_energy.c') newamp1_energy_cb (J 'newamp1_energy_q.txt')
& $PY $gen (Join-Path $WORK 'codebooknewamp2.c')       newamp2vq_cb      (J 'codes_450.txt')
& $PY $gen (Join-Path $WORK 'codebooknewamp2_energy.c') newamp2_energy_cb (J 'newamp2_energy_q.txt')

# shims
@'
#include "codec2.h"
__attribute__((visibility("default"))) struct CODEC2* c2c(int m){return codec2_create(m);}
__attribute__((visibility("default"))) void c2d(struct CODEC2*c){codec2_destroy(c);}
__attribute__((visibility("default"))) int c2spf(struct CODEC2*c){return codec2_samples_per_frame(c);}
__attribute__((visibility("default"))) int c2bpf(struct CODEC2*c){return codec2_bits_per_frame(c);}
__attribute__((visibility("default"))) void c2dec(struct CODEC2*c,short*s,unsigned char*b){codec2_decode(c,s,b);}
'@ | Set-Content -Encoding ascii (Join-Path $WORK 'shim_decode.c')
@'
#include "codec2.h"
__attribute__((visibility("default"))) struct CODEC2* c2c(int m){return codec2_create(m);}
__attribute__((visibility("default"))) void c2d(struct CODEC2*c){codec2_destroy(c);}
__attribute__((visibility("default"))) int c2spf(struct CODEC2*c){return codec2_samples_per_frame(c);}
__attribute__((visibility("default"))) int c2bpf(struct CODEC2*c){return codec2_bits_per_frame(c);}
__attribute__((visibility("default"))) void c2dec(struct CODEC2*c,short*s,unsigned char*b){codec2_decode(c,s,b);}
__attribute__((visibility("default"))) void c2enc(struct CODEC2*c,unsigned char*b,short*s){codec2_encode(c,b,s);}
'@ | Set-Content -Encoding ascii (Join-Path $WORK 'shim_full.c')

$CFLAGS = '-c','-O2','-fPIC','-ffunction-sections','-fdata-sections','-fvisibility=hidden',
          '-std=gnu11','-D__EMBEDDED__','-DGIT_HASH="none"',"-I$WORK"
$ALLC = $HAND + @('codebook.c','codebookd.c','codebookjmv.c','codebookge.c',
        'codebooknewamp1.c','codebooknewamp1_energy.c','codebooknewamp2.c','codebooknewamp2_energy.c')

Push-Location $WORK
$ok=$true
foreach ($f in $ALLC) {
    $o = Join-Path 'obj' ($f -replace '\.c$','.o')
    & $CC @CFLAGS $f -o $o
    if ($LASTEXITCODE -ne 0) { Write-Host "COMPILE FAIL: $f" -ForegroundColor Red; $ok=$false; break }
}
if (-not $ok) { Pop-Location; throw 'compile failed' }
# shims (default visibility kept; they are the gc roots)
$SHIMF = '-c','-O2','-fPIC','-std=gnu11',"-I$WORK"
& $CC @SHIMF 'shim_decode.c' -o 'obj\shim_decode.o'
& $CC @SHIMF 'shim_full.c'   -o 'obj\shim_full.o'

$objs = Get-ChildItem 'obj\*.o' | Where-Object { $_.Name -notlike 'shim_*' } | ForEach-Object { $_.FullName }

$LDF = '-shared','-Wl,--gc-sections','-Wl,--exclude-libs,ALL','-lm'

& $CC $LDF 'obj\shim_decode.o' @objs -o 'libc2dec.so'
& $CC $LDF 'obj\shim_full.o'   @objs -o 'libc2full.so'
Copy-Item 'libc2dec.so' 'libc2dec_stripped.so'; & $STRIP --strip-all 'libc2dec_stripped.so'
Copy-Item 'libc2full.so' 'libc2full_stripped.so'; & $STRIP --strip-all 'libc2full_stripped.so'

Write-Host ''
Write-Host '================ SONUC (armeabi-v7a) ================' -ForegroundColor Green
'{0,-28} {1,9:N0} byte ({2:N1} KB)' -f 'decode-only (stripli):', (Get-Item 'libc2dec_stripped.so').Length, ((Get-Item 'libc2dec_stripped.so').Length/1KB)
'{0,-28} {1,9:N0} byte ({2:N1} KB)' -f 'encode+decode (stripli):', (Get-Item 'libc2full_stripped.so').Length, ((Get-Item 'libc2full_stripped.so').Length/1KB)
Write-Host '--- llvm-size (decode-only) ---'
& $SIZE 'libc2dec_stripped.so'
Pop-Location

