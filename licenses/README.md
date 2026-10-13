# License distribution

Original ParallelNest source and the isolation probe: Apache License 2.0, full text in the root LICENSE.

The full resolved runtime dependency inventory and upstream Cargo license/notice texts are distributed in `app/src/main/assets/third-party-notices.txt` and can be read inside the app under About → Read complete dependency notices. This includes MIT, Apache-2.0 and other upstream notices for the locked Cargo graph. Maven coordinates are generated from the release runtime classpath, with their POM license declarations. Apache-2.0 text is included for AndroidX, Material, Kotlin, Coroutines and the applicable native crates.

Regenerate after dependency changes:

```sh
./gradlew :app:runtimeCoordinates
python scripts/dependency-notices.py
```

The references in docs/FEASIBILITY.md are researched projects; their engine source is not distributed with ParallelNest. No GPL engine has been incorporated. Build tools, SDK and NDK are development prerequisites; they are not redistributable runtime engine payloads in the APK.
