#!/bin/sh
# Renders the connecting screen's cabin picture, night and day, into the app's resources.
#
#   scripts/cabin/render.sh            the final pictures (about two minutes on a laptop)
#   scripts/cabin/render.sh preview    quick small pictures in the build folder, to look at while changing the scene
#
# Needs a C compiler, python3 and macOS's sips (any other tool that turns a BMP into a JPEG will do).
set -e
root=$(cd "$(dirname "$0")/../.." && pwd)
work="$root/build/cabin"
mkdir -p "$work"

# CarPlay's icon as raw pixels for the renderer: width, height, then RGBA.
python3 - "$root/common/src/main/res/drawable/ic_carplay.png" "$work/icon.rgba" <<'EOF'
import struct, sys, zlib
data = open(sys.argv[1], 'rb').read()
pos, idat = 8, b''
while pos < len(data):
    length, kind = struct.unpack('>I4s', data[pos:pos + 8])
    chunk = data[pos + 8:pos + 8 + length]
    if kind == b'IHDR':
        width, height, depth, colour = struct.unpack('>IIBB', chunk[:10])
    if kind == b'IDAT':
        idat += chunk
    pos += 12 + length
assert depth == 8 and colour == 6, 'the icon must be 8-bit RGBA'
raw, stride, rows, previous, at = zlib.decompress(idat), width * 4, [], bytearray(width * 4), 0
for _ in range(height):
    kind, line = raw[at], bytearray(raw[at + 1:at + 1 + stride])
    at += 1 + stride
    for x in range(stride):
        left = line[x - 4] if x >= 4 else 0
        up, corner = previous[x], previous[x - 4] if x >= 4 else 0
        if kind == 1:
            line[x] = (line[x] + left) & 255
        elif kind == 2:
            line[x] = (line[x] + up) & 255
        elif kind == 3:
            line[x] = (line[x] + ((left + up) >> 1)) & 255
        elif kind == 4:
            pa, pb, pc = abs(up - corner), abs(left - corner), abs(left + up - 2 * corner)
            line[x] = (line[x] + (left if pa <= pb and pa <= pc else up if pb <= pc else corner)) & 255
    rows.append(line)
    previous = line
open(sys.argv[2], 'wb').write(struct.pack('<II', width, height) + b''.join(rows))
EOF

cc -O3 -ffast-math -pthread "$root/scripts/cabin/render_cabin.c" -lm -o "$work/render_cabin"

if [ "$1" = "preview" ]; then
    for mode in ${2:-night day}; do
        "$work/render_cabin" "$mode" ${PREVIEW_SIZE:-840 600 1} "$work/icon.rgba" "$work/preview-$mode.bmp"
        sips -s format png "$work/preview-$mode.bmp" --out "$work/preview-$mode.png" >/dev/null
    done
    exit 0
fi

out="$root/common/src/main/res/drawable-nodpi"
mkdir -p "$out"
for mode in night day; do
    "$work/render_cabin" "$mode" 1680 1200 3 "$work/icon.rgba" "$work/cabin-$mode.bmp"
    sips -s format jpeg -s formatOptions 88 "$work/cabin-$mode.bmp" --out "$out/connecting_cabin_$mode.jpg" >/dev/null
done
