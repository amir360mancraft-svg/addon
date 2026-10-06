# Block Outlines (Meteor addon)

Module `block-outlines` (Render): smooth coloured outline on the targeted block; when it breaks, a black/white SVG icon pops and spins.

Settings (group Render): color, alpha (0.1-1), smooth, speed (2-30).

Build: JDK 21, `./gradlew build` -> `build/libs/`. Replace `src/main/resources/assets/blockoutlines/icon.svg` to change the icon (supports `<polygon points fill>` and `<rect>`; fill brighter than grey = white, otherwise black).
