# Anime4K attribution

Prototype source: https://github.com/bloc97/Anime4K
Pinned upstream commit: `7684e9586f8dcc738af08a1cdceb024cc184f426`.
Chosen component: `glsl/Deblur/Anime4K_Deblur_DoG.glsl` (Anime4K v3.2 DoG family).

AnimeVault uses a mobile-specific single-pass derivative of the DoG luminance residual,
threshold curve and local min/max clamp. The original shader uses multiple passes and a
7-tap separable Gaussian; the Android prototype uses a 3x3 approximation so it can be
measured safely on a mid-range mobile GPU before considering heavier CNN presets.

The effect is applied only during playback. Downloaded media bytes are not modified.
