#!/usr/bin/env bash
# Writes the videos the instrumented tests play and convert, into app/src/androidTest/assets.
#
# Each is 10 frames a second for 1 second, in four quadrants of one colour each, so that a test can
# tell which way up a frame came out and whether its colours survived: red top left, green top right,
# blue bottom left, grey bottom right. They are H.264, tagged BT.709 as phones write their videos,
# with a sine tone as AAC.
#
# - quadrants.mp4 is 256 x 144 pixels, no smaller because a hardware decoder refuses a video below a
#   size of its own.
# - quadrants-turned.mp4 is the same video stored with a display rotation that turns it a quarter
#   clockwise, as a phone held upright records it.
# - quadrants-small.mp4 is 96 x 64, which Qualcomm's hardware decoder refuses, so that a phone with it
#   has to fall back to another decoder.
# - quadrants-hlg.mp4 is quadrants.mp4's picture as 10-bit video tagged BT.2020 HLG, as a phone records
#   HDR, so that a test can tell it is played as HDR and comes out upright. Its values are the SDR
#   ones, so it shows nothing of how it is tone-mapped. It is VP9, without sound, as the emulator
#   decodes neither H.265 nor AV1 at 10 bits.
#
# Needs ffmpeg with libx264 and libvpx-vp9. Run it from anywhere; it overwrites the files.
set -euo pipefail

assets="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/app/src/androidTest/assets"

encoding=(
    -c:v "libx264" -crf "10" -g "10" -bf "0"
    -colorspace "bt709" -color_primaries "bt709" -color_trc "bt709"
    -c:a "aac" -b:a "64k" -shortest -map_metadata "-1" -fflags "+bitexact"
)

hlg_encoding=(
    -c:v "libvpx-vp9" -profile:v "2" -pix_fmt "yuv420p10le" -crf "10" -b:v "0" -g "10"
    -colorspace "bt2020nc" -color_primaries "bt2020" -color_trc "arib-std-b67"
    -an -map_metadata "-1" -fflags "+bitexact"
)

sound=(-f "lavfi" -i "sine=frequency=440:sample_rate=44100:duration=1")

# The quadrants at width x height, as an ffmpeg lavfi source.
picture() {
    local width="$1" height="$2"
    local w=$(( width / 2 )) h=$(( height / 2 ))
    printf '%s' \
        "color=c=0x808080:s=${width}x${height}:r=10:d=1," \
        "drawbox=x=0:y=0:w=$w:h=$h:c=0xC82828:t=fill," \
        "drawbox=x=$w:y=0:w=$w:h=$h:c=0x28C828:t=fill," \
        "drawbox=x=0:y=$h:w=$w:h=$h:c=0x2828C8:t=fill," \
        "scale=out_color_matrix=bt709:out_range=tv:flags=accurate_rnd+full_chroma_int,format=yuv420p"
}

run() {
    ffmpeg -v "error" -y "$@"
}

mkdir -p "$assets"
upright="$assets/quadrants.mp4"

run -f "lavfi" -i "$(picture 256 144)" "${sound[@]}" "${encoding[@]}" "$upright"

# A display rotation is counter-clockwise: 270 turns the stored frame a quarter clockwise.
run -display_rotation "270" -i "$upright" -c "copy" "$assets/quadrants-turned.mp4"

run -f "lavfi" -i "$(picture 96 64)" "${sound[@]}" "${encoding[@]}" "$assets/quadrants-small.mp4"

run -f "lavfi" -i "$(picture 256 144)" "${hlg_encoding[@]}" "$assets/quadrants-hlg.mp4"
