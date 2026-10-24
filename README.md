# Batvehicles MTS Patch

A small Forge add-on for Immersive Vehicles (MTS) that gives the Bat Vehicles Batmobile a grappling-hook winch.
It doesn't modify the MTS jar. It hooks into MTS vehicle movement with one Mixin, and ships its own MTS content pack containing the launcher part.

## Using it
1. Put this jar, MTS, and the Bat Vehicles pack in `mods/`.
2. Spawn a Batmobile. A fixed grapple launcher is added to its nose by default. Swap in another variant with the wrench, like any part.
3. Custom keys (set under MTS controls → custom keybinds):
   - **Grapple (Fire/Release)** (`keyIndex` 3). Launches the hook towards whatever is under your crosshair. It flies at 80 blocks/second for up to 100 blocks and sticks into the first block, mob or vehicle it hits. If it hits nothing, it comes back. Press again to release.
   - **Winch In (Hold)** (`keyIndex` 4). Reels the line in at 5 blocks/second, down to 2 blocks.

The line only pulls, and what it pulls depends on what the hook hit:
- **Block:** the car is pulled. Once the line is taut you can't drive further away. Winch in to drag yourself towards the block, up walls included. Or keep driving and the car swings round the anchor, with its heading steered along the swing.
- **Mob or player:** they get reeled in towards the car. The car doesn't feel them.
- **Another vehicle:** the pull is shared by mass, so the lighter vehicle moves more. Hooks in placed parts behave like blocks.

The line drops if the anchor block is broken, the target dies or despawns, or the two ends get more than 150 blocks apart. Lines into mobs or vehicles are also dropped when the world reloads.

## Variants

| Item | Aims | Sticks into |
|---|---|---|
| Grapple Winch (Turret), `batmobile_grapple` | Turns to the crosshair | Blocks, mobs, vehicles |
| Grapple Winch (Fixed), `batmobile_grapple_fixed` (Batmobile default) | Straight ahead | Blocks, mobs, vehicles |
| Harpoon (Fixed), `batmobile_grapple_fixed_harpoon` | Straight ahead | Mobs and vehicles only |
| Harpoon (Turret), `batmobile_grapple_harpoon` | Turns to the crosshair | Mobs and vehicles only |

Harpoons bounce off blocks and placed parts, and the hook comes back. Fixed launchers fire along the part slot's +Z, so the slot `rot` sets where they point.
The variants are `definitions` in one part JSON. Each sets the constants `grapple_fixed` and/or `grapple_entities_only`, and they share one texture through `textureNames`. Icon badges: orange means fixed, red means harpoon.

## How it works
- `GrappleSystem` holds all the logic. It uses only MTS core classes, which are the same in every MTS Minecraft version.
- `VehicleMovingMixin` calls it every tick on the client and server, just before MTS moves the vehicle (`AEntityVehicleD_Moving.update` → `moveVehicle()`). Line forces therefore go through MTS's normal collision checks and movement sync.
- The state lives in MTS variables on the launcher part, so pack JSON can use it:
  - Inputs: `grapple_fire`, `grapple_reel`, `grapple_slack`.
  - State: `grapple_state` (0 idle, 1 flying, 2 block, 3 mob, 4 vehicle), `grapple_line_out`, `grapple_attached`, `grapple_rope`, `grapple_self_share`, and `grapple_anchor_x/y/z` (the hook position, including in flight).
  - Rendering: `grapple_body_yaw` and `grapple_body_pitch` turn the whole launcher about its bottom back edge. They follow the driver's view, or the hook while the line is out, and are always 0 on fixed launchers. `grapple_line_yaw` and `grapple_line_pitch` then turn the cable and hook about the barrel tip, relative to the body. `grapple_length` is the cable length from the tip.
- Any part with `"type": "generic_grapple"` works as a launcher, so other vehicles can use it too.
- `grapple_slack` (let the line out) works but has no key, because MTS allows only four custom keybinds per vehicle. Bind it from a panel switch if you need it.

## Building
Targets Forge 1.20.1 (47.1.47), the same toolchain as `mcinterfaceforge1201`.

```bash
./gradlew build
```

The jar is written to `build/libs/`. `libs/mts-core.jar` is a copy of `mccore/build/libs/Immersive Vehicles-Core-relocated.jar` and is used at compile time only.
To port to another Forge version, change the versions in `gradle.properties` and `mods.toml`, and update `PlatformHooks`, which is the only class that uses Minecraft code directly.
