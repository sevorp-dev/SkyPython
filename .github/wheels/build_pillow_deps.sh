#!/bin/bash
# Builds Pillow's native dependencies for one Android ABI as static, position-independent
# libraries in $PILLOW_DEPS (used by build-wheels.yml as cibuildwheel's before-build, so it
# runs in cibuildwheel's Android environment: CC, AR, CFLAGS, CIBW_HOST_TRIPLET,
# ANDROID_API_LEVEL and CMAKE_TOOLCHAIN_FILE point at the NDK for the target ABI).
#
#   libjpeg-turbo  JPEG                    (IJG + BSD-3-Clause + zlib)
#   libpng         PNG glyphs in FreeType  (libpng license)
#   FreeType       ImageFont               (FreeType License)
#   libwebp        WebP                    (BSD-3-Clause)
#   libtiff        TIFF                    (libtiff license)
#   Little CMS 2   ImageCms                (MIT)
#   zlib           the system libz.so of Android (not vendored)
#
# Everything is linked statically into Pillow's extension modules, so the wheel needs no
# extra shared libraries (NEEDED stays libc/libm/libdl/libz/libpython). Pillow links only
# `-lfreetype` and `-lwebp -lwebpmux -lwebpdemux`, so libfreetype.so / libwebp.so here are
# linker scripts that add the static libraries those depend on (libpng, libsharpyuv).
# The license texts are copied to $PILLOW_DEPS/licenses/<lib>-<file>.
set -euo pipefail

: "${PILLOW_DEPS:?}" "${CIBW_HOST_TRIPLET:?}" "${CC:?}" "${CMAKE_TOOLCHAIN_FILE:?}"
HOST=$CIBW_HOST_TRIPLET
API=${ANDROID_API_LEVEL:-24}
SRC=${PILLOW_DEPS_SRC:-$PILLOW_DEPS-src}
JOBS=$(nproc)

if [ -e "$PILLOW_DEPS/.done" ]; then
    echo "Pillow dependencies already built in $PILLOW_DEPS"
    exit 0
fi
mkdir -p "$PILLOW_DEPS"/{include,lib,licenses} "$SRC"
PILLOW_DEPS=$(cd "$PILLOW_DEPS" && pwd)

# name version sha256 url [mirror url]
fetch() {
    local file=$SRC/$(basename "$4")
    if ! [ -e "$file" ]; then
        curl -fsSL --retry 3 -o "$file" "$4" || { [ -n "${5:-}" ] && curl -fsSL --retry 3 -o "$file" "$5"; }
    fi
    echo "$3  $file" | sha256sum -c - >&2
    rm -rf "$SRC/$1-$2"
    tar -xf "$file" -C "$SRC"
    echo "$SRC/$1-$2"
}

# Static PIC libraries; the CFLAGS of cibuildwheel's environment add the target's flags
# (-march for armv7, -D__BIONIC_NO_PAGE_SIZE_MACRO) and -O3.
export CFLAGS="$CFLAGS -fPIC"
export CPPFLAGS="-I$PILLOW_DEPS/include"
export LDFLAGS="$LDFLAGS -L$PILLOW_DEPS/lib"
export PKG_CONFIG_LIBDIR=$PILLOW_DEPS/lib/pkgconfig PKG_CONFIG_PATH=
CONFIGURE=(--host="$HOST" --prefix="$PILLOW_DEPS" --libdir="$PILLOW_DEPS/lib"
           --disable-shared --enable-static --with-pic --disable-dependency-tracking)

# zlib: the system library. Its headers and a link to the NDK's libz.so stub go into the
# prefix, so Pillow's setup.py (ZLIB_ROOT) finds both.
sysroot=$(cd "$(dirname "$CC")/../sysroot" && pwd)
cp "$sysroot/usr/include/zlib.h" "$sysroot/usr/include/zconf.h" "$PILLOW_DEPS/include/"
ln -sf "$sysroot/usr/lib/$HOST/$API/libz.so" "$PILLOW_DEPS/lib/libz.so"

# libjpeg-turbo (CMake, with cibuildwheel's toolchain file from the environment).
d=$(fetch libjpeg-turbo 3.2.0 6f30092cef9fb839779646608f4ee14ae3cbac989c47fa05e841b0841f09878e \
    https://github.com/libjpeg-turbo/libjpeg-turbo/releases/download/3.2.0/libjpeg-turbo-3.2.0.tar.gz)
cmake -S "$d" -B "$d/build" -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$PILLOW_DEPS" \
    -DCMAKE_INSTALL_LIBDIR=lib -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DENABLE_SHARED=OFF -DENABLE_STATIC=ON -DWITH_TURBOJPEG=OFF -DWITH_TOOLS=OFF -DWITH_TESTS=OFF
cmake --build "$d/build" -j "$JOBS"
cmake --install "$d/build"
grep -E "WITH_SIMD|SIMD" "$d/build/CMakeCache.txt" | grep -v "^//" | head -5 || true
cp "$d/LICENSE.md" "$PILLOW_DEPS/licenses/libjpeg-turbo-LICENSE.md"
cp "$d/README.ijg" "$PILLOW_DEPS/licenses/libjpeg-turbo-README.ijg.txt"

# libpng (only FreeType uses it: PNG glyphs in color fonts; Pillow decodes PNG itself).
d=$(fetch libpng 1.6.58 28eb403f51f0f7405249132cecfe82ea5c0ef97f1b32c5a65828814ae0d34775 \
    https://download.sourceforge.net/libpng/libpng-1.6.58.tar.xz \
    https://github.com/pnggroup/libpng/releases/download/v1.6.58/libpng-1.6.58.tar.xz)
(cd "$d" && ./configure "${CONFIGURE[@]}" --enable-hardware-optimizations=no --disable-tools \
    && make -j "$JOBS" install)
cp "$d/LICENSE" "$PILLOW_DEPS/licenses/libpng-LICENSE.txt"

# FreeType, with zlib (gzip-compressed fonts) and libpng, without bzip2/brotli/harfbuzz.
d=$(fetch freetype 2.14.3 36bc4f1cc413335368ee656c42afca65c5a3987e8768cc28cf11ba775e785a5f \
    https://download.savannah.gnu.org/releases/freetype/freetype-2.14.3.tar.xz \
    https://download.sourceforge.net/freetype/freetype-2.14.3.tar.xz)
(cd "$d" && ZLIB_LIBS="-lz" LIBPNG_CFLAGS="-I$PILLOW_DEPS/include/libpng16" \
    LIBPNG_LIBS="-L$PILLOW_DEPS/lib -lpng16 -lz" \
    ./configure "${CONFIGURE[@]}" --with-zlib=yes --with-png=yes --with-bzip2=no \
        --with-brotli=no --with-harfbuzz=no --with-librsvg=no \
    && make -j "$JOBS" && make install)
cp "$d/LICENSE.TXT" "$PILLOW_DEPS/licenses/freetype-LICENSE.txt"
cp "$d/docs/FTL.TXT" "$PILLOW_DEPS/licenses/freetype-FTL.txt"

# libwebp (autotools: no NDK cpufeatures, unlike its CMake build on Android).
d=$(fetch libwebp 1.6.0 e4ab7009bf0629fd11982d4c2aa83964cf244cffba7347ecd39019a9e38c4564 \
    https://storage.googleapis.com/downloads.webmproject.org/releases/webp/libwebp-1.6.0.tar.gz)
(cd "$d" && ./configure "${CONFIGURE[@]}" --enable-libwebpmux --enable-libwebpdemux \
    --disable-libwebpdecoder --disable-libwebpextras --disable-gl --disable-sdl --disable-png \
    --disable-jpeg --disable-tiff --disable-gif --disable-wic \
    && make -j "$JOBS" install)
cp "$d/COPYING" "$PILLOW_DEPS/licenses/libwebp-COPYING.txt"
cp "$d/PATENTS" "$PILLOW_DEPS/licenses/libwebp-PATENTS.txt"

# libtiff with zlib and libjpeg only.
d=$(fetch tiff 4.7.2 672bd7d10aee4606171afb864f3570b83340f6a33e2c186dc0512f7145ffdf6a \
    https://download.osgeo.org/libtiff/tiff-4.7.2.tar.gz)
(cd "$d" && ./configure "${CONFIGURE[@]}" --disable-tools --disable-tests --disable-contrib \
    --disable-docs --disable-cxx --enable-zlib --enable-jpeg --disable-libdeflate --disable-jbig \
    --disable-lerc --disable-lzma --disable-zstd --disable-webp --disable-opengl \
    --with-jpeg-include-dir="$PILLOW_DEPS/include" --with-jpeg-lib-dir="$PILLOW_DEPS/lib" \
    && make -j "$JOBS" install)
cp "$d/LICENSE.md" "$PILLOW_DEPS/licenses/libtiff-LICENSE.md"

# Little CMS 2.
d=$(fetch lcms2 2.19.1 bfc54f7bab59fbc921012014a8032e4cba4abd46db47d46b76416a8c0b2815c8 \
    https://github.com/mm2/Little-CMS/releases/download/lcms2.19.1/lcms2-2.19.1.tar.gz)
(cd "$d" && ./configure "${CONFIGURE[@]}" --without-jpeg --without-tiff \
    && make -j "$JOBS" -C src install && make -C include install)
cp "$d/LICENSE" "$PILLOW_DEPS/licenses/lcms2-LICENSE.txt"

# Linker scripts: `-lfreetype` / `-lwebp` also pull in what those static libraries need.
lib=$PILLOW_DEPS/lib
test -f "$lib/libfreetype.a" -a -f "$lib/libpng16.a" -a -f "$lib/libwebp.a" -a -f "$lib/libsharpyuv.a"
echo "INPUT($lib/libfreetype.a $lib/libpng16.a -lz)" > "$lib/libfreetype.so"
echo "INPUT($lib/libwebp.a $lib/libsharpyuv.a)" > "$lib/libwebp.so"
# No other shared libraries or libtool archives may be picked up instead of the static ones.
rm -f "$lib"/*.la
ls -l "$lib" "$PILLOW_DEPS/licenses"
touch "$PILLOW_DEPS/.done"
