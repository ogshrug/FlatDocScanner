# Third-party notices

FlatDocScanner's own code is licensed under the Apache License 2.0 (see [LICENSE](LICENSE)).
The app also ships the models and libraries below, each under its own licence.

## Models

| Model | Used for | Licence | Changes |
|---|---|---|---|
| [UVDoc](https://github.com/tanguymagne/UVDoc) | Flattening curved pages | MIT, © 2023 Tanguy Magne | Exported to ONNX (`tools/export_uvdoc_onnx.py`); weights stored as 16-bit floats (`tools/halve_model.py`) |
| [DocAligner](https://github.com/DocsaidLab/DocAligner) LCNet-100 | Finding page corners | Apache 2.0, DocsaidLab | Weights stored as 16-bit floats (`tools/halve_model.py`) |

The licence texts ship inside the app as `assets/uvdoc_LICENSE.txt` and
`assets/docaligner_LICENSE.txt`.

## Design

| Source | Used for | Licence |
|---|---|---|
| [Astryx](https://github.com/facebook/astryx) | Colour and corner-radius tokens of seven of the app's themes (`tools/make_themes.py`), taken from the `@astryxdesign/theme-*` packages, version 0.7.12. No Astryx code or typeface is included. | MIT, © Meta Platforms, Inc. and affiliates |

## Libraries in the app

| Library | Licence |
|---|---|
| [OpenCV](https://github.com/opencv/opencv) | Apache 2.0 |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) | MIT, © Microsoft Corporation |
| [Ferrite](https://github.com/AaravGupta17/Ferrite), as [Ferrite-DocScanner](https://github.com/ogshrug/Ferrite-DocScanner) (the Ferrite build only, in place of ONNX Runtime) | MIT |
| [XNNPACK](https://github.com/google/XNNPACK) (inside ONNX Runtime) | BSD 3-Clause, © Facebook, Inc. and its affiliates, © Google LLC |
| [AndroidX](https://developer.android.com/jetpack/androidx) (AppCompat, Activity, Lifecycle, RecyclerView, ViewPager2, ExifInterface, CameraX, Room) | Apache 2.0 |
| [Material Components for Android](https://github.com/material-components/material-components-android) | Apache 2.0 |
| [Kotlin](https://github.com/JetBrains/kotlin) standard library and [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Apache 2.0 |
| [ML Kit Text Recognition](https://developers.google.com/ml-kit/vision/text-recognition/v2) | Not open source; used under the [ML Kit Terms of Service](https://developers.google.com/ml-kit/terms) |

OpenCV and ONNX Runtime contain further third-party code of their own; their repositories
list it (`3rdparty/` in OpenCV, `ThirdPartyNotices.txt` in ONNX Runtime).

## Used only to build and test

JUnit 4 (Eclipse Public License 1.0), AndroidX Test and Espresso (Apache 2.0), the Android
Gradle Plugin and KSP (Apache 2.0). None of these is part of the installed app.

## Licence texts

The Apache License 2.0 is reproduced in [LICENSE](LICENSE).

### MIT License (UVDoc, ONNX Runtime, Astryx, Ferrite)

```
Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### BSD 3-Clause License (XNNPACK)

```
Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.

* Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution.

* Neither the name of the copyright holder nor the names of its
  contributors may be used to endorse or promote products derived from
  this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```
