# Batvehicles MTS Patch

A small Forge add-on for Immersive Vehicles (MTS) that gives the Bat Vehicles Batmobile a grappling-hook winch.
It doesn't modify the MTS jar. It hooks into MTS vehicle movement with one Mixin, and ships its own MTS content pack containing the launcher part.

## Using it
1. Put this jar, MTS, and the Bat Vehicles pack in `mods/`.
2. Spawn a Batmobile. The grapple launcher is added to its nose by default.
3. Custom keys (set under MTS controls → custom keybinds):
   - **Custom 3: Grapple (Fire/Release).** Fires the line at whatever you're looking at, up to 48 blocks. Press again to release.
   - **Custom 4: Winch In (Hold).** Reels the line in at 5 blocks/second, down to 2 blocks.

The line only pulls. Once it's taut you can't drive further away from the anchor. Winch in to drag yourself towards it, up walls included. Or keep driving on a taut line and the car swings round the anchor, with its heading steered along the swing.
The line drops automatically if the anchor block is broken.

## How it works
- `GrappleSystem` holds all the logic. It uses only MTS core classes, which are the same in every MTS Minecraft version.
- `VehicleMovingMixin` calls it every tick on the client and server, just before MTS moves the vehicle (`AEntityVehicleD_Moving.update` → `moveVehicle()`). Line forces therefore go through MTS's normal collision checks and movement sync.
- The state lives in MTS variables on the launcher part, so pack JSON can use it:
  - Inputs: `grapple_fire`, `grapple_reel`, `grapple_slack`.
  - State: `grapple_attached`, `grapple_rope`, `grapple_anchor_x/y/z`.
  - Rendering: `grapple_length`, `grapple_yaw`, `grapple_pitch`.
- Any part with `"type": "generic_grapple"` works as a launcher, so other vehicles can use it too.
- `grapple_slack` (let the line out) works but has no key, because MTS allows only four custom keybinds per vehicle. Bind it from a panel switch if you need it.

## Building
Targets Forge 1.20.1 (47.1.47), the same toolchain as `mcinterfaceforge1201`.

```bash
./gradlew build
```

The jar is written to `build/libs/`. `libs/mts-core.jar` is a copy of `mccore/build/libs/Immersive Vehicles-Core-relocated.jar` and is used at compile time only.
To port to another Forge version, change the versions in `gradle.properties` and `mods.toml`. The Java code doesn't change.
