$ErrorActionPreference = 'Stop'
$ROOT = 'C:\Users\Portakal\AppData\Local\Temp\claude\C--E-KodlamaProjeleri-JavaAndroidProjeleri-Codec2Player\1e557031-b7e8-4478-a6d6-3d9aa41d9591\scratchpad'
$WORK = Join-Path $ROOT 'c2meas'
$TC   = 'C:\E\kp\scoop\persist\android-clt\ndk\28.2.13676358\toolchains\llvm\prebuilt\windows-x86_64\bin'
$CC    = Join-Path $TC 'armv7a-linux-androideabi21-clang.cmd'
$STRIP = Join-Path $TC 'llvm-strip.exe'
$SIZE  = Join-Path $TC 'llvm-size.exe'
$PROJ = 'C:\E\KodlamaProjeleri\JavaAndroidProjeleri\Codec2Player\Proje'
$JNI  = Join-Path $PROJ 'src\main\cpp\Codec2JNI.c'
$OUTDIR = Join-Path $PROJ 'src\main\jniLibs\armeabi-v7a'

if (-not (Test-Path (Join-Path $WORK 'obj\codec2.o'))) { throw "c2meas obj yok; once build.ps1 calistir" }

# JNI (default visibility -> JNI_OnLoad export edilir)
$CF = @('-c','-O2','-fPIC','-ffunction-sections','-fdata-sections','-std=gnu11',"-I$WORK")
& $CC @CF $JNI -o (Join-Path $WORK 'obj\codec2jni.o')
if ($LASTEXITCODE -ne 0) { throw 'JNI derleme HATASI' }

$objs = Get-ChildItem (Join-Path $WORK 'obj\*.o') | Where-Object { $_.Name -notlike 'shim_*' -and $_.Name -ne 'codec2jni.o' } | ForEach-Object { $_.FullName }

New-Item -ItemType Directory -Force $OUTDIR | Out-Null
$SO = Join-Path $OUTDIR 'libcodec2player.so'
$LF = @('-shared','-Wl,--gc-sections','-Wl,--exclude-libs,ALL','-Wl,--no-undefined','-lm')
& $CC @LF (Join-Path $WORK 'obj\codec2jni.o') @objs -o $SO
if ($LASTEXITCODE -ne 0) { throw 'LINK HATASI' }
& $STRIP --strip-all $SO

'{0,-26} {1,9:N0} byte ({2:N1} KB)' -f 'libcodec2player.so:', (Get-Item $SO).Length, ((Get-Item $SO).Length/1KB)
& $SIZE $SO
# JNI_OnLoad export kontrolu
"--- dynsym (JNI_OnLoad) ---"
& (Join-Path $TC 'llvm-nm.exe') -D $SO | Select-String 'JNI_OnLoad'
