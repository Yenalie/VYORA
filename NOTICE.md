# Third-party notices

VYORA's liquid-glass UI (bottom navigation, glass engine) is derived from other
open-source projects. **VYORA as a whole
must be distributed under GPL-3.0** (see `LICENSE`) and its source made available to
anyone you give the app to.

| Component | Where | Source | License |
|---|---|---|---|
| Liquid-glass modifier, floating tab bar | `ui/glass/GlassEffect.kt`, `ui/glass/floatingtabbar/`
| Backdrop engine (blur, lens, highlight, shaders) | `ui/glass/backdrop/`, `ui/glass/interaction/` | Kyant0/backdrop v2.0.0 — https://github.com/Kyant0/backdrop | Apache-2.0 |
| Continuous-corner shapes | `ui/glass/shapes/` | Kyant0/Capsule (vendored via Convx) — https://github.com/Kyant0/Capsule | Apache-2.0 |
| `VyoraNavBar.kt` | `ui/glass/` | Adapted from Convx's `AppFloatingNavBar` | GPL-3.0 |
| `VyoraMiniAccessory.kt` | `ui/player/` | Adapted from Convx's `FloatingMiniPlayer` (swipe-to-skip, press response, dock layout) | GPL-3.0 |
