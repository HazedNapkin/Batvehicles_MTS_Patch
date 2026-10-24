# Batvehicles MTS Patch

A small Forge add-on for Immersive Vehicles (MTS). It gives the Bat Vehicles Batmobile a grappling-hook winch and adds a Vehicle Phone that calls your vehicles to you.
It doesn't modify the MTS jar. It hooks into MTS with Mixins (vehicle movement, item use and packet registration), and ships its own MTS content pack containing the launcher part and the phone.

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
| Grapple Winch (Fixed), `batmobile_grapple_fixed` (Batmobile default) | Fires at the crosshair, body doesn't turn | Blocks, mobs, vehicles |
| Harpoon (Fixed), `batmobile_grapple_fixed_harpoon` | Fires at the crosshair, body doesn't turn | Mobs and vehicles only |
| Harpoon (Turret), `batmobile_grapple_harpoon` | Turns to the crosshair | Mobs and vehicles only |

Harpoons bounce off blocks and placed parts, and the hook comes back. Fixed launchers still fire at the crosshair; only the launcher body stays still while the cable swings out from the barrel tip. With no driver, every launcher fires along the part slot's +Z.
The variants are `definitions` in one part JSON. Each sets the constants `grapple_fixed` and/or `grapple_entities_only`, and they share one texture through `textureNames`. Icon badges: orange means fixed, red means harpoon.

## Vehicle Phone

Craft the **Vehicle Phone** at an MTS bench (2 iron, 4 redstone, 1 glass pane, 1 ender pearl).

- **Register a vehicle:** shift-right-click it with the phone. You need its MTS key, unless it has no key or you're an operator. A phone holds up to 8 vehicles. The list is stored on the phone, so whoever holds the phone can call them.
- **Call one:** right-click with the phone to open the menu. Each vehicle has **Call**, **Stop** and **X** (remove) buttons. Its distance shows when it's close enough to be loaded on your client.
- A call is refused if someone is riding the vehicle, or it's wrecked or out of fuel. It only works in the same dimension.

### What a ground vehicle does
1. If its chunk isn't loaded, the mod force-loads it briefly. The mod remembers where every vehicle was last seen, saved in the dimension's `mtsdata.dat`.
2. If it's already loaded and within `phone_summon_distance` blocks (default 30, plus an 8-block margin), it drives over from where it is and is never teleported. Otherwise it looks for an arrival spot that far away, starting straight in front of you and working round both sides. From there it plans a route to the nearest spot within 3 to 8 blocks of you where the whole vehicle fits.
3. It teleports to that start spot, starts its engines (not jet thrusters), releases the parking brake and drives in with the real throttle, steering and brakes. It cruises at about 30 km/h and slows for turns and the final approach.
4. On arrival it stops, sets the parking brake and leaves the engine running.

**Fallbacks** (a vehicle that started within range never teleports; it drives as close as it can or stops instead)
- **No route from anywhere:** it teleports straight to the spot next to you.
- **No room next to you:** it drives as close as it can get.
- **Stuck for 5 seconds, or still driving after 2 minutes:** it plans a new route once. If that fails, it teleports next to you, or stops where it is if there's no room.

The route planner works on a block grid. It's sized to the vehicle's width and height, climbs at most 1 block, drops at most 2, avoids water and lava, and won't cut corners. It runs in slices over several ticks, so a summon can't lag the server.

### What an aircraft does
Only aircraft with the `phone_summon_aircraft` definition constant can be registered. The Batwing has it.
1. You can't call it while you're indoors (any block over your head).
2. It teleports in about `phone_summon_distance` blocks in front of you, 10 blocks above everything around you. If it's already loaded and within that range, it climbs and flies over from where it is instead.
3. It starts its engines, switches on `stay_on` and flies to just above you, following you if you move. Then it switches to `hover`.
4. It searches within 20 blocks of you for a spot where it can come straight down. The ground must be solid and fairly flat, and the whole column above it must be clear. If it finds one, it flies over, lowers the gear (`gear_setpoint` 0) and descends. Once the wheels touch, it presses down for half a second so they all settle, then sets the parking brake and switches `hover` and `stay_on` off so it rests on its wheels.
5. If there's nowhere to land, it stays in `hover` above you with `stay_on` left on.

During the flight the mod moves the aircraft directly instead of using its flight physics. The movement still goes through MTS collisions, and the server and clients run the same movement.

### Vehicle JSON options
- `"constants": ["phone_summon_aircraft"]` in a definition lets the phone call that aircraft.
- A `phone_summon_distance` variable modifier with `setValue` changes how far away the vehicle appears before driving or flying in.

## How it works
- `GrappleSystem` holds all the logic. It uses only MTS core classes, which are the same in every MTS Minecraft version.
- `VehicleMovingMixin` calls the grapple and phone systems every tick on the client and server, just before MTS moves the vehicle (`AEntityVehicleD_Moving.update` → `moveVehicle()`). Line forces therefore go through MTS's normal collision checks and movement sync.
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
