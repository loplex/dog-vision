# The licences of what the packages bundle

The texts the packages carry beside the project's own licence, each word for word from its source.\
[`ThirdParty.kt`](../../build-logic/src/main/kotlin/cz/loplex/dogvision/packaging/licenses/ThirdParty.kt) says which part of which package each is the licence of.

A text is fetched again when its library changes version: skiko's Skia, LWJGL, ANGLE, webpack.\
The versions are those the build bundled when the text was taken.

| File                       | Licence of                                 | From                                                                                                                                                   |
|----------------------------|--------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------|
| `Apache-2.0.txt`           | every part under Apache-2.0                | https://www.apache.org/licenses/LICENSE-2.0.txt                                                                                                        |
| `angle.txt`                | ANGLE chromium/8037                        | `META-INF/LICENSE.angle` in dev.nucleusframework:nucleus.angle-natives:8037.1                                                                          |
| `d3d12memoryallocator.txt` | D3D12MemoryAllocator, in skiko on Windows  | https://skia.googlesource.com/external/github.com/GPUOpen-LibrariesAndSDKs/D3D12MemoryAllocator/+/169895d529dfce00390a20e69c2f516066fe7a3b/LICENSE.txt |
| `dng_sdk.txt`              | the DNG SDK, in skiko on Linux             | https://android.googlesource.com/platform/external/dng_sdk/+/dbe0a676450d9b8c71bf00688bb306409b779e90/LICENSE                                          |
| `expat.txt`                | Expat, in skiko                            | https://chromium.googlesource.com/external/github.com/libexpat/libexpat/+/6154446fccefbf3ca644894f598969113b0c7bcd/expat/COPYING                       |
| `freetype.txt`             | FreeType, in skiko on Linux, under the FTL | https://chromium.googlesource.com/chromium/src/third_party/freetype2/+/264b5fbf5b912b39f98d038bf75d39be0a73f21b/docs/FTL.TXT                           |
| `glfw.txt`                 | GLFW 3.5.1, LWJGL's glfw.dll               | https://github.com/LWJGL-CI/glfw/blob/d9d6f0f1f967807ffade6598ea9a631ebaf37a56/LICENSE.md                                                              |
| `harfbuzz.txt`             | HarfBuzz, in skiko                         | https://chromium.googlesource.com/external/github.com/harfbuzz/harfbuzz/+/9cb1fee51069b206effb4736e443b038d230789d/COPYING                             |
| `icu.txt`                  | ICU, in skiko                              | https://chromium.googlesource.com/chromium/deps/icu/+/364118a1d9da24bb5b770ac3d762ac144d6da5a4/LICENSE                                                 |
| `libffi.txt`               | libffi 3.8.0, in LWJGL's core native       | https://github.com/libffi/libffi/blob/v3.8.0/LICENSE                                                                                                   |
| `libjpeg-turbo.txt`        | libjpeg-turbo, in skiko                    | https://chromium.googlesource.com/chromium/deps/libjpeg_turbo/+/e14cbfaa85529d47f9f55b0f104a579c1061f9ad/LICENSE.md                                    |
| `libjpeg-turbo-ijg.txt`    | the IJG's part of libjpeg-turbo            | https://chromium.googlesource.com/chromium/deps/libjpeg_turbo/+/e14cbfaa85529d47f9f55b0f104a579c1061f9ad/README.ijg                                    |
| `libpng.txt`               | libpng, in skiko                           | https://skia.googlesource.com/third_party/libpng/+/d5515b5b8be3901aac04e5bd8bd5c89f287bcd33/LICENSE                                                    |
| `libwebp.txt`              | libwebp, in skiko                          | https://chromium.googlesource.com/webm/libwebp/+/845d5476a866141ba35ac133f856fa62f0b7445f/COPYING                                                      |
| `libwebp-patents.txt`      | libwebp's patent grant                     | https://chromium.googlesource.com/webm/libwebp/+/845d5476a866141ba35ac133f856fa62f0b7445f/PATENTS                                                      |
| `lwjgl.txt`                | LWJGL 3.4.3                                | https://github.com/LWJGL/lwjgl3/blob/30fac9b95f99cda97312232be25ba55297bf9951/LICENSE.md                                                               |
| `skia.txt`                 | Skia m150-1f14f1166a, in skiko 0.150.1     | https://github.com/JetBrains/skia/blob/1f14f1166a/LICENSE                                                                                              |
| `webpack.txt`              | webpack 5.108.1's runtime, in the page     | https://github.com/webpack/webpack/blob/v5.108.1/LICENSE                                                                                               |
| `zlib.txt`                 | zlib, in skiko                             | https://chromium.googlesource.com/chromium/src/third_party/zlib/+/646b7f569718921d7d4b5b8e22572ff6c76f2596/LICENSE                                     |

Skia's libraries are those of its DEPS at skiko's Skia, and which of them a native holds was measured in the native:
the Linux one has FreeType, the DNG SDK and piex,
the Windows one SPIRV-Cross and D3D12MemoryAllocator, which Skia's Direct3D needs.\
piex, Wuffs, SPIRV-Cross and Abseil, the last in ANGLE, are under Apache-2.0 and take its text.
