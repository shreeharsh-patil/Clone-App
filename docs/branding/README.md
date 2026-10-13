# Clone Apps logo

[Transparent original PNG](clone-apps-logo.png). Generated with the built-in image generation tool on 2026-10-13. The original alpha channel is preserved; no image manipulation or recoloring was applied.

The overlapping cyan-blue and violet tiles form an abstract C. Their sculpted glass finish carries the app's existing blue/purple palette into the launcher icon. No text is embedded, keeping the mark legible at small sizes and allowing the app name to remain accessible native text.

The Android adaptive icon places the transparent artwork over #121212 with 10% foreground insets. Android handles launcher masks and scaling. The application ID and native JNI package remain unchanged so existing installations can upgrade.

## Verification

The PNG is 1254×1254 RGBA with real transparent pixels. Original SHA-256: `05808d7bced8518c9a239a4d3404f28fc2fdea4ffd8dcee3129146862296f6dc`.

The renamed debug APK and instrumentation APK built successfully. Six Kotlin unit tests and seven Android 16 emulator checks passed (home screen, native metadata/bridge/Keystore checks, Bluetooth consent/lifecycle and selected-file URI boundaries). Both ARM64 and x86_64 native libraries passed 16 KiB APK/ELF alignment verification. [Android's actual app-info rendering](android-app-icon.png) confirms the new adaptive icon and Clone Apps label. These checks do not establish physical hardware compatibility or complete the separate hardware acceptance matrix.

## Generation prompt

Use case: logo-brand

Asset type: original Android launcher logo for the app named Clone Apps

Primary request: a beautiful artistic yet highly legible premium app symbol conveying duplicating apps and independent parallel spaces. Create one finished logo, not a sheet of options.

Subject: two interlocking offset rounded-square app tiles, forming a flowing abstract C through clever clean negative space. A luminous cyan-blue front ribbon/tile and violet-purple rear tile, with tasteful sculpted folded-glass depth, smooth elegant curves, restrained iridescent highlights and precise silhouettes. Distinctive sophisticated digital craftsmanship, simple enough to recognize at 48 pixels.

Color palette: vibrant sky blue #39A8F4 through cyan, and rich violet #A773F8; subtle white highlights. Fits a #121212 dark Android UI.

Composition: centered single emblem on a square canvas, symmetric visual balance, generous transparent padding all around, emblem occupying about 68 percent of canvas. All important details within the central safe region. True transparent background.

Constraints: emblem only, no lettering, no wordmark, no slogans, no borders, no watermark, no scenery or presentation mockup, no background square. Crisp clean edges, no wispy particles, no tiny details, avoid generic Android robot, avoid padlocks or security symbolism. Deliver polished high resolution logo with transparent alpha.
