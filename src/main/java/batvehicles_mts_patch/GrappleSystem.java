package batvehicles_mts_patch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import minecrafttransportsimulator.baseclasses.BlockHitResult;
import minecrafttransportsimulator.baseclasses.BoundingBox;
import minecrafttransportsimulator.baseclasses.BoundingBoxHitResult;
import minecrafttransportsimulator.baseclasses.ComputedVariable;
import minecrafttransportsimulator.baseclasses.ComputedVariable.ComputedVariableOperator;
import minecrafttransportsimulator.baseclasses.EntityInteractResult;
import minecrafttransportsimulator.baseclasses.Point3D;
import minecrafttransportsimulator.baseclasses.RotationMatrix;
import minecrafttransportsimulator.entities.components.AEntityB_Existing;
import minecrafttransportsimulator.entities.components.AEntityF_Multipart;
import minecrafttransportsimulator.entities.instances.APart;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import minecrafttransportsimulator.entities.instances.PartGroundDevice;
import minecrafttransportsimulator.jsondefs.JSONCollisionGroup.CollisionType;
import minecrafttransportsimulator.mcinterface.IWrapperEntity;

/**
 * Grappling-hook winch for MTS vehicles.
 * Any part whose generic type is {@link #PART_TYPE} acts as a grapple launcher.  Firing launches
 * the hook as a projectile towards whatever is under the driver's crosshair (or along the
 * launcher's +Z axis with no driver).  It flies up to {@link #RANGE} blocks and sticks into the
 * first block, mob or vehicle it hits.  Blocks hold the car, mobs get reeled in, and vehicles
 * share the pull by mass.
 * State the pack and clients need lives in MTS variables on the part, so MTS handles client sync
 * for us and pack JSON drives it with normal keybinds and animations:
 * -{@link #VAR_FIRE}: toggle.  1 fires the hook, 0 releases it.  Set back to 0 by the server on a miss.
 * -{@link #VAR_REEL}: button.  While held, the winch pulls the line in.
 * -{@link #VAR_SLACK}: button.  While held, the winch lets the line out.
 * -{@link #VAR_STATE}: read-only.  One of the STATE_ constants.
 * -{@link #VAR_LINE_OUT}: read-only.  1 while the hook is flying or stuck in something.
 * -{@link #VAR_ATTACHED}: read-only.  1 while the hook is stuck in something.
 * -{@link #VAR_BODY_YAW}, {@link #VAR_BODY_PITCH}: read-only, for rendering.  Turn the whole
 * launcher about {@link #PIVOT}: at the hook while the line is out, otherwise where the driver looks.
 * Always zero for fixed launchers.
 * -{@link #VAR_LINE_YAW}, {@link #VAR_LINE_PITCH}, {@link #VAR_LENGTH}: read-only, for rendering.
 * Turn the cable and hook about the barrel tip, relative to the body, and the cable length from the tip to the hook.
 * 
 * Definition constants pick the variant: {@link #CONSTANT_FIXED} and {@link #CONSTANT_ENTITIES_ONLY}.
 * The line only ever pulls.  When it's taut, whatever is on each end can't move further apart,
 * gets pulled back to the line length, and the car's heading is steered along any swing so the
 * tyres don't fight the turn.  When the line lifts the car, it pitches up on its far wheels, or
 * hangs nose-first below the hook once clear of the ground.  At the top of a wall, the mantle
 * takes over and hauls the car up and over onto the top.
 */
public final class GrappleSystem {
    public static final String PART_TYPE = "generic_grapple";

    public static final String VAR_FIRE = "grapple_fire";
    public static final String VAR_REEL = "grapple_reel";
    public static final String VAR_SLACK = "grapple_slack";
    public static final String VAR_STATE = "grapple_state";
    public static final String VAR_LINE_OUT = "grapple_line_out";
    public static final String VAR_ATTACHED = "grapple_attached";
    public static final String VAR_ROPE = "grapple_rope";
    public static final String VAR_SELF_SHARE = "grapple_self_share";
    public static final String VAR_ANCHOR_X = "grapple_anchor_x";
    public static final String VAR_ANCHOR_Y = "grapple_anchor_y";
    public static final String VAR_ANCHOR_Z = "grapple_anchor_z";
    public static final String VAR_LENGTH = "grapple_length";
    public static final String VAR_BODY_YAW = "grapple_body_yaw";
    public static final String VAR_BODY_PITCH = "grapple_body_pitch";
    public static final String VAR_LINE_YAW = "grapple_line_yaw";
    public static final String VAR_LINE_PITCH = "grapple_line_pitch";
    /**Read-only.  One of the MANTLE_ constants, for the car being hauled over the top of a wall.**/
    public static final String VAR_MANTLE = "grapple_mantle";
    private static final String VAR_MANTLE_LIP = "grapple_mantle_lip";
    private static final String VAR_MANTLE_EDGE_X = "grapple_mantle_edge_x";
    private static final String VAR_MANTLE_EDGE_Z = "grapple_mantle_edge_z";
    private static final String VAR_MANTLE_YAW = "grapple_mantle_yaw";

    /**Definition constant: the launcher body doesn't turn to follow the crosshair.  It still fires where the driver aims.**/
    public static final String CONSTANT_FIXED = "grapple_fixed";
    /**Definition constant: the hook only sticks into mobs and vehicles.  It bounces off blocks.**/
    public static final String CONSTANT_ENTITIES_ONLY = "grapple_entities_only";

    public static final int STATE_IDLE = 0;
    public static final int STATE_FLYING = 1;
    public static final int STATE_BLOCK = 2;
    public static final int STATE_MOB = 3;
    public static final int STATE_VEHICLE = 4;

    public static final int MANTLE_NONE = 0;
    /**Hanging at the lip for {@link #MANTLE_PAUSE_TICKS}.**/
    public static final int MANTLE_PAUSE = 1;
    /**Lifting straight up while tilting towards the hook, until the launcher-end wheels clear the top.**/
    public static final int MANTLE_LIFT = 2;
    /**Moving towards the hook while tilting down to level, until the far wheels are over the top.**/
    public static final int MANTLE_ROLL = 3;

    /**Point the launcher turns about: the bottom back edge of the model, in part coordinates.
     * Must match the animation centerPoint in the part JSON.**/
    private static final Point3D PIVOT = new Point3D(0, -2D / 16D, -8D / 16D);
    /**How far the hook can fly, in blocks.**/
    private static final double RANGE = 100;
    /**Hook flight speed, in blocks per tick (100 blocks in 1.25 seconds).**/
    private static final double HOOK_SPEED = 4;
    /**Shortest the winch can reel the line, in blocks.  Short enough to keep pulling the car over a
     * lip when the hook is just past the edge.  Any longer and the line goes slack at the top.**/
    private static final double MIN_ROPE = 0.5;
    /**Winch speed, in blocks per tick (5 blocks/second).**/
    private static final double REEL_SPEED = 0.25;
    /**Fraction of any over-stretch pulled back each tick.  Higher is a stiffer line.**/
    private static final double STIFFNESS = 0.3;
    /**Cap on the pull-back speed from stretch, in blocks per tick, so the line can't fling anything.**/
    private static final double MAX_PULL = 0.6;
    /**Fraction of the heading error to the swing direction corrected each tick.**/
    private static final double YAW_ASSIST = 0.35;
    /**Cap on the yaw added by the line, in degrees per tick.**/
    private static final double MAX_YAW_ASSIST = 6;
    /**Yaw assist is off when the car's heading or the line, flattened onto the ground, is shorter
     * than this (about 60 degrees from level), and fades in up to level.**/
    private static final double MIN_YAW_FLATNESS = 0.5;
    /**Cap on the pitch added by the line, in degrees per tick.**/
    private static final double MAX_TILT_RATE = 4;
    /**Fraction of the hanging angle error corrected each tick while the car hangs free of the ground.**/
    private static final double HANG_ASSIST = 0.15;
    /**How often the server checks the anchor block still exists, in ticks.**/
    private static final int ANCHOR_CHECK_INTERVAL = 10;

    /**How long the car hangs at the lip before the mantle can start, in ticks.**/
    private static final int MANTLE_PAUSE_TICKS = 10;
    /**How fast the mantle lifts and rolls the car, in blocks per tick.**/
    private static final double MANTLE_SPEED = 1D / 16D;
    /**Pitch the car is tilted to, in degrees from level, by the time its launcher-end wheels reach the top.**/
    private static final double MANTLE_ROLL_PITCH = 45;
    /**How far above the top the launcher-end wheels are carried, in blocks, so they don't catch the lip.**/
    private static final double MANTLE_CLEARANCE = 1D / 16D;
    /**The car counts as climbing a wall when its launcher end points further up than this (sine of about 37 degrees).**/
    private static final double MANTLE_CLIMB_STEEPNESS = 0.6;
    /**The line has stopped lifting the car once it points up less than this (sine of 30 degrees).**/
    private static final double MANTLE_FLAT_LINE = 0.5;
    /**How many 1/16 block steps ahead of the wheels we look for the wall face.  48 is 3 blocks.**/
    private static final int MANTLE_FACE_SEARCH = 48;
    /**How many blocks above the wheels we look for the top of the wall.**/
    private static final int MANTLE_LIP_SEARCH = 4;

    /**Server-only hook state that can't live in a variable: flight and what we're stuck in.**/
    private static final Map<APart, Hook> hooks = new WeakHashMap<>();
    /**Client-only hook positions for the last two ticks, so the render can interpolate.**/
    private static final Map<APart, Point3D[]> renderAnchors = new WeakHashMap<>();
    /**Cable variables we've already given a render function to.  Weak so removed parts don't leak.**/
    private static final Set<ComputedVariable> renderVariables = Collections.newSetFromMap(new WeakHashMap<>());
    /**Vehicles whose pitch the line is holding this tick.  MTS's own pitch correction is skipped for
     * these, as it would drop the lifted end straight back onto its wheels.**/
    private static final Set<EntityVehicleF_Physics> pitchHeldVehicles = Collections.newSetFromMap(new WeakHashMap<>());
    /**Push out of a wall to apply to each stuck vehicle this tick, in blocks.**/
    private static final Map<EntityVehicleF_Physics, Point3D> pushOuts = new WeakHashMap<>();
    /**Server-only count of ticks each launcher has paused at a lip.**/
    private static final Map<APart, Integer> mantlePauseTicks = new WeakHashMap<>();

    private GrappleSystem() {
    }

    public static boolean isGrapple(APart part) {
        return part.definition.generic != null && PART_TYPE.equals(part.definition.generic.type);
    }

    /**
     * Called on both client and server, once per vehicle tick, just before MTS moves the vehicle.
     */
    public static void updateVehicle(EntityVehicleF_Physics vehicle) {
        pitchHeldVehicles.remove(vehicle);
        pushOuts.remove(vehicle);
        boolean attached = false;
        for (APart part : vehicle.allParts) {
            if (part.isValid && isGrapple(part)) {
                updateGrapple(vehicle, part);
                attached |= getState(part) >= STATE_BLOCK;
            }
        }

        //If the line has dragged the car into a wall, step it back out.  Only while attached, so we
        //don't change how MTS handles collisions the rest of the time.
        if (attached && vehicle.towedByConnection == null) {
            Point3D pushOut = LineCollision.findPushOut(vehicle, LineCollision.getAllBoxes(vehicle));
            if (pushOut != null) {
                pushOuts.put(vehicle, pushOut);
            }
        }
    }

    /**
     * Returns the push out of a wall to apply to this vehicle this tick, if any, and clears it.
     * MTS cancels any move made by a box already inside a block, so this is added to the position
     * after MTS's collision checks rather than to the motion.
     */
    public static Point3D takePushOut(EntityVehicleF_Physics vehicle) {
        return pushOuts.remove(vehicle);
    }

    private static void updateGrapple(EntityVehicleF_Physics vehicle, APart part) {
        setupRenderVariables(part);

        //The server owns the hook.  It flies it, decides hits and line length, and syncs them to clients.
        if (!vehicle.world.isClient()) {
            updateServerHook(vehicle, part);
        } else {
            updateRenderAnchor(part);
        }

        //Both sides run the car's side of the line so clients don't have to wait on server corrections.
        //How much of the pull the car takes is decided by the server at attach time.
        if (getState(part) >= STATE_BLOCK && vehicle.towedByConnection == null) {
            double selfShare = part.getOrCreateVariable(VAR_SELF_SHARE).currentValue;
            if (selfShare > 0) {
                if (getMantle(part) != MANTLE_NONE) {
                    updateMantle(vehicle, part);
                } else {
                    applyLineForces(vehicle, part, getAnchor(part), part.getOrCreateVariable(VAR_ROPE).currentValue, selfShare);
                }
            }
        }
    }

    private static void updateServerHook(EntityVehicleF_Physics vehicle, APart part) {
        ComputedVariable fireVar = part.getOrCreateVariable(VAR_FIRE);
        int state = getState(part);
        Hook hook = hooks.get(part);

        if (!fireVar.isActive) {
            if (state != STATE_IDLE) {
                release(part, fireVar);
            }
            return;
        }
        if (state == STATE_IDLE) {
            launch(vehicle, part);
            //Take the first step straight away.  Otherwise clients get a tick with the hook sitting on the
            //launcher, and a zero-length cable has no direction, so it can point anywhere.
            Hook launched = hooks.get(part);
            if (launched != null) {
                fly(vehicle, part, launched, fireVar);
            }
            return;
        }
        if (state != STATE_BLOCK && hook == null) {
            //Flying or stuck in an entity across a reload: we've lost the reference, so drop the line.
            release(part, fireVar);
            return;
        }

        switch (state) {
            case STATE_FLYING: {
                fly(vehicle, part, hook, fireVar);
                return;
            }
            case STATE_BLOCK: {
                if (vehicle.ticksExisted % ANCHOR_CHECK_INTERVAL == 0 && vehicle.world.isAir(getAnchor(part))) {
                    release(part, fireVar);
                    return;
                }
                break;
            }
            case STATE_MOB: {
                if (!hook.mob.isValid()) {
                    release(part, fireVar);
                    return;
                }
                setAnchor(part, hook.mob.getPosition().copy().add(hook.offset));
                break;
            }
            case STATE_VEHICLE: {
                if (!hook.multipart.isValid) {
                    release(part, fireVar);
                    return;
                }
                setAnchor(part, hook.offset.copy().rotate(hook.multipart.orientation).add(hook.multipart.position));
                break;
            }
        }

        Point3D anchor = getAnchor(part);
        if (!anchor.isDistanceToCloserThan(part.position, RANGE * 1.5)) {
            //Pulled or teleported too far apart.  The line snaps.
            release(part, fireVar);
            return;
        }

        ComputedVariable ropeVar = part.getOrCreateVariable(VAR_ROPE);
        double rope = ropeVar.currentValue;
        if (part.getOrCreateVariable(VAR_REEL).isActive) {
            rope -= REEL_SPEED;
        } else if (part.getOrCreateVariable(VAR_SLACK).isActive) {
            rope += REEL_SPEED;
        }
        if (getMantle(part) != MANTLE_NONE) {
            //The mantle carries the car, not the line.  Keep the line just taut, so it doesn't yank
            //the car when the mantle hands back to it.
            rope = part.position.distanceTo(anchor);
        }
        rope = Math.max(MIN_ROPE, Math.min(RANGE, rope));
        ropeVar.setTo(rope, true);

        //Pull whatever we're stuck in.  The car's share is applied on both sides in updateGrapple.
        double targetShare = 1 - part.getOrCreateVariable(VAR_SELF_SHARE).currentValue;
        if (state == STATE_MOB) {
            pullMob(part, hook.mob, anchor, rope);
        } else if (state == STATE_VEHICLE && hook.multipart instanceof EntityVehicleF_Physics && targetShare > 0) {
            pullVehicle(part, (EntityVehicleF_Physics) hook.multipart, anchor, rope, targetShare);
        }
    }

    /**
     * Fires the hook from the launcher at whatever is under the driver's crosshair.
     */
    private static void launch(EntityVehicleF_Physics vehicle, APart part) {
        Point3D start = part.position.copy();
        Point3D target;
        IWrapperEntity controller = part.getController();
        if (controller != null) {
            //Find what the crosshair is on and aim the hook from the launcher at that point.
            //This way the hook goes where the driver is looking, even though the launcher is offset from their eyes.
            Point3D eye = controller.getEyePosition().copy();
            Point3D eyeEnd = eye.copy().add(controller.getLineOfSight(RANGE));
            Hit aimHit = findHit(vehicle, eye, eyeEnd);
            target = aimHit != null ? aimHit.position : eyeEnd;
        } else {
            target = new Point3D(0, 0, RANGE).rotate(part.orientation).add(start);
        }

        Hook hook = new Hook();
        hook.position.set(start);
        hook.velocity.set(target).subtract(start).normalize().scale(HOOK_SPEED);
        hooks.put(part, hook);
        setAnchor(part, start);
        setState(part, STATE_FLYING);
    }

    /**
     * Moves the hook one tick along its flight, sticking it into the first thing it hits.
     */
    private static void fly(EntityVehicleF_Physics vehicle, APart part, Hook hook, ComputedVariable fireVar) {
        Point3D start = hook.position.copy();
        Point3D end = start.copy().add(hook.velocity);
        Hit hit = findHit(vehicle, start, end);
        if (hit != null) {
            if (isEntitiesOnly(part) && !hit.isMovable()) {
                //Entity-only hooks can't bite into blocks or placed parts.  They bounce off and come back.
                release(part, fireVar);
            } else {
                attach(vehicle, part, hook, hit);
            }
            return;
        }

        hook.position.set(end);
        if (!hook.position.isDistanceToCloserThan(part.position, RANGE) || !vehicle.world.chunkLoaded(hook.position)) {
            //Out of line, or flying into unloaded chunks.  Reel it back.
            release(part, fireVar);
            return;
        }
        setAnchor(part, hook.position);
    }

    private static void attach(EntityVehicleF_Physics vehicle, APart part, Hook hook, Hit hit) {
        hook.position.set(hit.position);
        double selfShare;
        int state;
        if (hit.mob != null) {
            //Mobs are light next to a car, so the car doesn't feel them.  We just reel them in.
            hook.mob = hit.mob;
            hook.offset = hit.position.copy().subtract(hit.mob.getPosition());
            state = STATE_MOB;
            selfShare = 0;
        } else if (hit.multipart != null) {
            //Store the hit in the other entity's local coordinates, so the hook stays put as it turns.
            hook.multipart = hit.multipart;
            hook.offset = hit.position.copy().subtract(hit.multipart.position).reOrigin(hit.multipart.orientation);
            state = STATE_VEHICLE;
            if (hit.multipart instanceof EntityVehicleF_Physics) {
                //Share the pull by mass: the lighter vehicle moves more.
                double otherMass = ((EntityVehicleF_Physics) hit.multipart).currentMass;
                selfShare = otherMass / (otherMass + vehicle.currentMass);
            } else {
                //Placed parts don't move, so treat them like a block.
                selfShare = 1;
            }
        } else {
            state = STATE_BLOCK;
            selfShare = 1;
        }

        setAnchor(part, hit.position);
        part.getOrCreateVariable(VAR_SELF_SHARE).setTo(selfShare, true);
        part.getOrCreateVariable(VAR_ROPE).setTo(Math.max(MIN_ROPE, Math.min(RANGE, part.position.distanceTo(hit.position))), true);
        setState(part, state);
    }

    private static void release(APart part, ComputedVariable fireVar) {
        hooks.remove(part);
        fireVar.setTo(0, true);
        setMantle(part, MANTLE_NONE);
        setState(part, STATE_IDLE);
    }

    /**
     * Returns the closest block, vehicle or mob on the segment, ignoring our own vehicle and anyone riding it.
     */
    private static Hit findHit(EntityVehicleF_Physics vehicle, Point3D start, Point3D end) {
        Hit closest = null;
        Point3D delta = end.copy().subtract(start);

        BlockHitResult blockHit = vehicle.world.getBlockHit(start, delta);
        if (blockHit != null) {
            //Store the hit a hair inside the block, so the air check finds the block we hit and not the space in front of it.
            closest = new Hit(blockHit.hitPosition.copy().addScaled(delta.copy().normalize(), 0.01));
        }

        EntityInteractResult multipartHit = vehicle.world.getMultipartEntityIntersect(start, end, vehicle, CollisionType.ENTITY, CollisionType.ATTACK, CollisionType.BULLET);
        if (multipartHit != null && (closest == null || start.isFirstCloserThanSecond(multipartHit.position, closest.position))) {
            AEntityF_Multipart<?> multipart = multipartHit.entity instanceof APart ? ((APart) multipartHit.entity).masterEntity : (AEntityF_Multipart<?>) multipartHit.entity;
            closest = new Hit(multipartHit.position.copy());
            closest.multipart = multipart;
        }

        for (IWrapperEntity mob : vehicle.world.getEntitiesWithin(new BoundingBox(start, end))) {
            if (isRidingVehicle(mob, vehicle)) {
                continue;
            }
            BoundingBoxHitResult mobHit = mob.getBounds().getIntersection(start, end);
            if (mobHit != null && (closest == null || start.isFirstCloserThanSecond(mobHit.position, closest.position))) {
                closest = new Hit(mobHit.position.copy());
                closest.mob = mob;
            }
        }
        return closest;
    }

    private static boolean isRidingVehicle(IWrapperEntity entity, EntityVehicleF_Physics vehicle) {
        AEntityB_Existing riding = entity.getEntityRiding();
        return riding == vehicle || (riding instanceof APart && ((APart) riding).masterEntity == vehicle);
    }

    /**
     * Reels a mob towards the launcher.  Server only; mob movement syncs through vanilla.
     */
    private static void pullMob(APart part, IWrapperEntity mob, Point3D anchor, double rope) {
        Point3D toLauncher = part.position.copy().subtract(anchor);
        double distance = toLauncher.length();
        if (distance <= rope || distance < 1.0E-3 || mob.getEntityRiding() != null) {
            return;
        }
        Point3D lineNormal = toLauncher.scale(1D / distance);
        Point3D velocity = mob.getVelocity().copy();
        double radialSpeed = velocity.dotProduct(lineNormal, false);
        if (radialSpeed < 0) {
            velocity.addScaled(lineNormal, -radialSpeed);
        }
        velocity.addScaled(lineNormal, Math.min((distance - rope) * STIFFNESS, MAX_PULL));
        mob.setVelocity(velocity);
        PlatformHooks.markVelocityChanged(mob);
    }

    /**
     * Pulls another vehicle towards the launcher with its share of the line force.  Server only;
     * MTS syncs that vehicle's movement to clients.
     */
    private static void pullVehicle(APart part, EntityVehicleF_Physics other, Point3D anchor, double rope, double share) {
        Point3D toLauncher = part.position.copy().subtract(anchor);
        double distance = toLauncher.length();
        if (distance <= rope || distance < 1.0E-3) {
            return;
        }
        Point3D lineNormal = toLauncher.scale(1D / distance);
        double radialSpeed = other.motion.dotProduct(lineNormal, false);
        if (radialSpeed < 0) {
            other.motion.addScaled(lineNormal, -radialSpeed * share);
        }
        other.motion.addScaled(lineNormal, Math.min((distance - rope) * STIFFNESS, MAX_PULL) * share / other.speedFactor);
    }

    /**
     * Applies the car's share of the line tension, pulling it towards the anchor.
     */
    private static void applyLineForces(EntityVehicleF_Physics vehicle, APart part, Point3D anchor, double rope, double share) {
        Point3D toAnchor = anchor.copy().subtract(part.position);
        double distance = toAnchor.length();
        if (distance <= rope || distance < 1.0E-3) {
            //Line is slack, nothing to do.
            return;
        }
        Point3D lineNormal = toAnchor.scale(1D / distance);
        double motionYBefore = vehicle.motion.y;

        //Rigid line: remove any velocity taking us further from the anchor.
        double radialSpeed = vehicle.motion.dotProduct(lineNormal, false);
        if (radialSpeed < 0) {
            vehicle.motion.addScaled(lineNormal, -radialSpeed * share);
        }

        //Pull back towards the line length.  Motion gets scaled by speedFactor when applied, so undo that here.
        double pull = Math.min((distance - rope) * STIFFNESS, MAX_PULL) * share;
        vehicle.motion.addScaled(lineNormal, pull / vehicle.speedFactor);

        //Steer the heading along our direction of travel while the line swings us.  Without this, tyre
        //grip snaps our motion back to the heading next tick and the line can't swing us round a corner.
        //Scale it by how much of the travel is sideways to the line, so reeling straight in doesn't turn
        //us.  Steering to the sideways part alone turned any drift into a full turn side-on to the line.
        //Fade it out as the car or the line nears vertical, such as climbing a wall.  The flattened
        //heading and line are then too short to have a reliable direction, and the car's yaw axis lies
        //nearly flat, so a "yaw" would flip the car over rather than turn it.
        Point3D groundMotion = new Point3D(vehicle.motion.x, 0, vehicle.motion.z);
        Point3D groundNormal = new Point3D(lineNormal.x, 0, lineNormal.z);
        Point3D heading = new Point3D(vehicle.headingVector.x, 0, vehicle.headingVector.z);
        double groundSpeed = groundMotion.length();
        double headingFlatness = heading.length();
        if (groundSpeed > 0.05 && groundNormal.length() > MIN_YAW_FLATNESS && headingFlatness > MIN_YAW_FLATNESS) {
            groundNormal.normalize();
            heading.normalize();
            double sidewaysFraction = Math.sqrt(Math.max(0, 1 - Math.pow(groundMotion.dotProduct(groundNormal, false) / groundSpeed, 2)));
            sidewaysFraction *= (headingFlatness - MIN_YAW_FLATNESS) / (1 - MIN_YAW_FLATNESS);
            Point3D travelDirection = groundMotion.scale(1D / groundSpeed);
            if (heading.dotProduct(travelDirection, true) < 0) {
                //Travelling backwards, so line the tail up with the travel instead of the nose.
                travelDirection.invert();
            }
            double headingError = Math.toDegrees(Math.atan2(heading.crossProduct(travelDirection).y, heading.dotProduct(travelDirection, true)));
            double yawAdded = Math.max(-MAX_YAW_ASSIST, Math.min(MAX_YAW_ASSIST, headingError * YAW_ASSIST * share * sidewaysFraction));
            vehicle.rotation.angles.y += yawAdded;
        }

        List<BoundingBox> freeBoxes = LineCollision.getFreeBoxes(vehicle, LineCollision.getAllBoxes(vehicle));
        applyLineTilt(vehicle, part, lineNormal, (vehicle.motion.y - motionYBefore) * vehicle.speedFactor, freeBoxes);

        //MTS doesn't stop wheels going into walls, and the line usually pulls the car towards one.
        Point3D blocked = LineCollision.clampToWalls(vehicle, freeBoxes);

        //A straight line to a hook on top of a wall stops lifting once the launcher is level with the
        //hook, which leaves the wheels below the lip.  When that happens, the server hands over to the
        //mantle to haul the car over the top.  These checks are cheap, so the block search in
        //tryStartMantle only runs while the car is actually stuck against a wall.
        Point3D towardsHook = new Point3D(lineNormal.x, 0, lineNormal.z);
        if (!vehicle.world.isClient() && lineNormal.y < MANTLE_FLAT_LINE && towardsHook.length() > 1.0E-3 && blocked.dotProduct(towardsHook.normalize(), false) > 0) {
            tryStartMantle(vehicle, part, towardsHook);
        }
    }

    /**
     * Pitches the car when the line lifts one end of it.  The line pulls on the launcher while
     * gravity pulls on the middle of the car, so the launcher end rises first.
     * -With wheels grounded on the far side of the car, the car tips up about them.  The lift the
     * line just added is spent on the tilt instead of raising the whole car level.
     * -Hanging free, the car swings until its middle hangs straight below the launcher.
     * -With only the launcher end grounded, such as the front wheels just over the top of a wall,
     * the car pitches about those wheels until it points along the line, which lays it flat on the top.
     * 
     * Either way the car never tilts past where it would hang from the line, so it can't flip over.
     *
     * @param lift how far the line raised the car this tick, in blocks.
     * @param freeBoxes the car's boxes that aren't already inside a block, to check the tilt against.
     */
    private static void applyLineTilt(EntityVehicleF_Physics vehicle, APart part, Point3D lineNormal, double lift, List<BoundingBox> freeBoxes) {
        //Work in the car's own frame, where pitch is a turn about X, so only Y and Z matter.
        //MTS has no centre of mass, so take the middle of the wheels.  Without wheels, use the origin.
        Point3D launcher = toLocal(vehicle, part.position);
        Point3D center = new Point3D();
        int wheels = 0;
        for (APart other : vehicle.allParts) {
            if (other instanceof PartGroundDevice && !other.placementDefinition.isSpare) {
                center.add(toLocal(vehicle, other.position));
                ++wheels;
            }
        }
        if (wheels > 0) {
            center.scale(1D / wheels);
        }
        Point3D arm = launcher.copy().subtract(center);
        if (Math.hypot(arm.y, arm.z) < 0.1) {
            //Launcher sits on the middle of the car.  The line can't turn it.
            return;
        }

        //Tilt that would hang the car's middle straight below the launcher.  Positive is nose down.
        Point3D lineLocal = lineNormal.copy().reOrigin(vehicle.orientation);
        double hangError = getTiltAngle(arm) - getTiltAngle(lineLocal);
        hangError = ((hangError % 360) + 540) % 360 - 180;

        //Sort the grounded wheels into the launcher end and the far end of the car.
        Point3D pivot = new Point3D();
        int pivotWheels = 0;
        Point3D launcherEnd = new Point3D();
        int launcherEndWheels = 0;
        for (PartGroundDevice groundDevice : vehicle.groundDeviceCollective.groundedGroundDevices) {
            Point3D wheel = toLocal(vehicle, groundDevice.position);
            if ((wheel.z - center.z) * arm.z < 0) {
                pivot.add(wheel);
                ++pivotWheels;
            } else {
                launcherEnd.add(wheel);
                ++launcherEndWheels;
            }
        }

        double tilt;
        //How far the launcher rises in the world for each degree of tilt.  Only used when pivoting on the far wheels.
        double risePerDegree = 0;
        if (pivotWheels == 0 && launcherEndWheels > 0) {
            //The launcher end is up on something, like the top of a wall, and the far end hangs free.
            //Pitch about the launcher-end wheels until the car points along the line, so the line drags
            //it over the edge and down flat onto the top, instead of leaving it hanging off the edge.
            //This runs whichever way the line points, as at the top it usually runs level or downhill.
            pivot.set(launcherEnd.scale(1D / launcherEndWheels));
            double overError = getTiltAngle(new Point3D(0, 0, Math.signum(arm.z))) - getTiltAngle(lineLocal);
            overError = ((overError % 360) + 540) % 360 - 180;
            tilt = Math.max(-MAX_TILT_RATE, Math.min(MAX_TILT_RATE, overError * HANG_ASSIST));
        } else if (lineNormal.y <= 0) {
            //Line runs level or downhill, so it can't lift either end.  Leave pitch to MTS.
            return;
        } else if (pivotWheels > 0) {
            pivot.scale(1D / pivotWheels);
            Point3D pivotArm = launcher.copy().subtract(pivot);
            risePerDegree = new Point3D(0, -pivotArm.z, pivotArm.y).rotate(vehicle.orientation).y * Math.PI / 180D;
            tilt = Math.abs(risePerDegree) > 1.0E-3 ? lift / risePerDegree : 0;
            if (tilt * hangError <= 0) {
                //The line isn't lifting, or we're already hanging from it.  Hold the angle we're at.
                tilt = 0;
            } else {
                tilt = Math.signum(tilt) * Math.min(Math.abs(tilt), Math.min(Math.abs(hangError), MAX_TILT_RATE));
            }
        } else {
            //Hanging free.  Swing about the launcher so the line holds it still.
            pivot.set(launcher);
            tilt = Math.max(-MAX_TILT_RATE, Math.min(MAX_TILT_RATE, hangError * HANG_ASSIST));
        }

        //MTS only checks a turn against the collision boxes, so a tilt could swing the wheels into a wall.
        tilt = LineCollision.limitTilt(vehicle, freeBoxes, pivot, tilt);
        if (tilt != 0) {
            //The tilt raises the launcher, so don't also lift the whole car by that much.
            vehicle.motion.y -= tilt * risePerDegree / vehicle.speedFactor;
            vehicle.rotation.angles.x += tilt;
            //MTS turns the car about its origin, so shift the origin to keep the pivot where it is.
            Point3D pivotShift = pivot.copy().subtract(pivot.copy().rotate(new RotationMatrix().setToAxisAngle(1, 0, 0, tilt)));
            vehicle.motion.addScaled(pivotShift.rotate(vehicle.orientation), 1D / vehicle.speedFactor);
        }
        pitchHeldVehicles.add(vehicle);
    }

    /**
     * Starts the mantle if the car is climbing a wall and the top of the wall is in reach.  Server only.
     * Looks for the wall face ahead of the lowest launcher-end wheel, then for the first air block
     * above the face, which is the top of the wall.
     */
    private static void tryStartMantle(EntityVehicleF_Physics vehicle, APart part, Point3D towardsHook) {
        WheelSplit wheels = new WheelSplit(vehicle, part);
        if (wheels.launcherEnd.isEmpty() || wheels.farEnd.isEmpty() || vehicle.headingVector.y * wheels.side < MANTLE_CLIMB_STEEPNESS) {
            return;
        }

        Point3D face = wheels.getLowestLauncherEnd().position.copy();
        Point3D step = towardsHook.copy().scale(1D / 16D);
        boolean foundFace = false;
        for (int i = 0; i < MANTLE_FACE_SEARCH && !foundFace; ++i) {
            face.add(step);
            foundFace = !vehicle.world.isAir(face);
        }
        if (!foundFace) {
            return;
        }

        Point3D column = new Point3D(Math.floor(face.x) + 0.5, Math.floor(face.y) + 0.5, Math.floor(face.z) + 0.5);
        for (int i = 0; i < MANTLE_LIP_SEARCH; ++i) {
            column.y += 1;
            if (vehicle.world.isAir(column)) {
                //Set the path before the phase, so clients have it by the time they start following it.
                part.getOrCreateVariable(VAR_MANTLE_LIP).setTo(Math.floor(column.y), true);
                part.getOrCreateVariable(VAR_MANTLE_EDGE_X).setTo(face.x, true);
                part.getOrCreateVariable(VAR_MANTLE_EDGE_Z).setTo(face.z, true);
                part.getOrCreateVariable(VAR_MANTLE_YAW).setTo(Math.toDegrees(Math.atan2(towardsHook.x, towardsHook.z)), true);
                mantlePauseTicks.remove(part);
                setMantle(part, MANTLE_PAUSE);
                return;
            }
        }
    }

    /**
     * Hauls the car over the top of a wall.  Runs on both client and server.  The mantle moves the
     * car itself along a set path, so it looks the same every time and costs next to nothing a tick:
     * -Pause: hang at the lip for {@link #MANTLE_PAUSE_TICKS}.
     * -Lift: rise straight up while tilting towards the hook, pivoting on the launcher-end wheels,
     * until they clear the top.  The tilt is spread over the height left, so the car reaches
     * {@link #MANTLE_ROLL_PITCH} just as the wheels clear the lip.
     * -Roll: move towards the hook with the launcher-end wheels skimming the top, tilting down to
     * level over the distance left, so the far wheels come up over the lip as the car levels out.
     * 
     * Lift and roll only move while reel is held.  Letting go holds the car where it is, and slack
     * drops out of the mantle and back to the line. Only the server changes phase; clients follow.
     */
    private static void updateMantle(EntityVehicleF_Physics vehicle, APart part) {
        boolean server = !vehicle.world.isClient();
        //Nothing else moves the car during the mantle, so cancel gravity, tyres and steering.
        vehicle.motion.set(0, 0, 0);
        vehicle.rotation.angles.set(0, 0, 0);
        pitchHeldVehicles.add(vehicle);

        if (part.getOrCreateVariable(VAR_SLACK).isActive) {
            if (server) {
                setMantle(part, MANTLE_NONE);
            }
            return;
        }
        int phase = getMantle(part);
        if (phase == MANTLE_PAUSE) {
            if (server && mantlePauseTicks.merge(part, 1, Integer::sum) >= MANTLE_PAUSE_TICKS) {
                mantlePauseTicks.remove(part);
                setMantle(part, MANTLE_LIFT);
            }
            return;
        }
        if (!part.getOrCreateVariable(VAR_REEL).isActive) {
            return;
        }

        WheelSplit wheels = new WheelSplit(vehicle, part);
        if (wheels.launcherEnd.isEmpty() || wheels.farEnd.isEmpty()) {
            if (server) {
                setMantle(part, MANTLE_NONE);
            }
            return;
        }
        double lip = part.getOrCreateVariable(VAR_MANTLE_LIP).currentValue;
        double wheelBottom = wheels.getLauncherEndBottom();
        //How far the launcher end points up from level, in degrees.
        double pitchUp = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, vehicle.headingVector.y * wheels.side))));

        Point3D move = new Point3D();
        double tilt;
        if (phase == MANTLE_LIFT) {
            double remaining = lip + MANTLE_CLEARANCE - wheelBottom;
            if (remaining <= 0) {
                if (server) {
                    setMantle(part, MANTLE_ROLL);
                }
                return;
            }
            double rise = Math.min(MANTLE_SPEED, remaining);
            move.y = rise;
            tilt = Math.max(0, pitchUp - MANTLE_ROLL_PITCH) * rise / remaining;
        } else {
            double yaw = Math.toRadians(part.getOrCreateVariable(VAR_MANTLE_YAW).currentValue);
            Point3D direction = new Point3D(Math.sin(yaw), 0, Math.cos(yaw));
            Point3D edge = new Point3D(part.getOrCreateVariable(VAR_MANTLE_EDGE_X).currentValue, 0, part.getOrCreateVariable(VAR_MANTLE_EDGE_Z).currentValue);
            //Distance left until every far wheel is a radius past the wall face, so over the top.
            double remaining = -Double.MAX_VALUE;
            for (APart wheel : wheels.farEnd) {
                Point3D flatWheel = new Point3D(wheel.position.x, 0, wheel.position.z);
                remaining = Math.max(remaining, edge.copy().subtract(flatWheel).dotProduct(direction, false) + wheel.getHeight() / 2D);
            }
            if (remaining <= 0) {
                //Over the top.  MTS sets the car down and the line takes over again.
                if (server) {
                    setMantle(part, MANTLE_NONE);
                }
                return;
            }
            double travel = Math.min(MANTLE_SPEED, remaining);
            move.set(direction).scale(travel);
            move.y = Math.max(-MANTLE_SPEED, Math.min(MANTLE_SPEED, lip + MANTLE_CLEARANCE - wheelBottom));
            tilt = Math.max(0, pitchUp) * travel / remaining;
        }
        //Cap the rate so a short distance left can't snap the car round.  Positive tilt is nose down,
        //so flip it when the launcher is at the back.
        tilt = Math.min(tilt, MAX_TILT_RATE) * wheels.side;

        //Pivot on the launcher-end wheels, so they follow the path above and the far end swings round them.
        Point3D pivot = wheels.getLauncherEndCenter();
        vehicle.rotation.angles.x = tilt;
        Point3D pivotShift = pivot.copy().subtract(pivot.copy().rotate(new RotationMatrix().setToAxisAngle(1, 0, 0, tilt)));
        move.add(pivotShift.rotate(vehicle.orientation));
        vehicle.motion.set(move).scale(1D / vehicle.speedFactor);
    }

    /**
     * Returns true if the line is holding this vehicle's pitch this tick, so MTS shouldn't level it.
     */
    public static boolean isPitchHeld(EntityVehicleF_Physics vehicle) {
        return pitchHeldVehicles.contains(vehicle);
    }

    private static Point3D toLocal(EntityVehicleF_Physics vehicle, Point3D worldPosition) {
        return worldPosition.copy().subtract(vehicle.position).reOrigin(vehicle.orientation);
    }

    /**Pitch of a local vector above the +Z axis, in degrees.**/
    private static double getTiltAngle(Point3D local) {
        return Math.toDegrees(Math.atan2(local.y, local.z));
    }

    private static boolean isFixed(APart part) {
        return part.getOrCreateVariable(CONSTANT_FIXED).isActive;
    }

    private static boolean isEntitiesOnly(APart part) {
        return part.getOrCreateVariable(CONSTANT_ENTITIES_ONLY).isActive;
    }

    private static int getMantle(APart part) {
        return (int) part.getOrCreateVariable(VAR_MANTLE).currentValue;
    }

    private static void setMantle(APart part, int phase) {
        part.getOrCreateVariable(VAR_MANTLE).setTo(phase, true);
    }

    private static int getState(APart part) {
        return (int) part.getOrCreateVariable(VAR_STATE).currentValue;
    }

    private static void setState(APart part, int state) {
        part.getOrCreateVariable(VAR_STATE).setTo(state, true);
        part.getOrCreateVariable(VAR_LINE_OUT).setTo(state != STATE_IDLE ? 1 : 0, true);
        part.getOrCreateVariable(VAR_ATTACHED).setTo(state >= STATE_BLOCK ? 1 : 0, true);
    }

    private static Point3D getAnchor(APart part) {
        return new Point3D(part.getOrCreateVariable(VAR_ANCHOR_X).currentValue, part.getOrCreateVariable(VAR_ANCHOR_Y).currentValue, part.getOrCreateVariable(VAR_ANCHOR_Z).currentValue);
    }

    /**Sets the hook position.  Only sends packets for the axes that changed.**/
    private static void setAnchor(APart part, Point3D anchor) {
        part.getOrCreateVariable(VAR_ANCHOR_X).setTo(anchor.x, true);
        part.getOrCreateVariable(VAR_ANCHOR_Y).setTo(anchor.y, true);
        part.getOrCreateVariable(VAR_ANCHOR_Z).setTo(anchor.z, true);
    }

    /**
     * Client side: remembers the hook position for this tick and the last one, so the render can
     * interpolate between them instead of the hook jumping 4 blocks a tick in flight.
     */
    private static void updateRenderAnchor(APart part) {
        Point3D[] anchors = renderAnchors.get(part);
        Point3D anchor = getAnchor(part);
        if (!part.getOrCreateVariable(VAR_LINE_OUT).isActive) {
            //No line out.  Forget the last hook position, so the next launch doesn't sweep in from it.
            renderAnchors.remove(part);
        } else if (anchors == null) {
            //Line just went out.  Start the interpolation at the hook's first position.
            renderAnchors.put(part, new Point3D[] {anchor, anchor.copy()});
        } else {
            anchors[0].set(anchors[1]);
            anchors[1].set(anchor);
        }
    }

    private static Point3D getRenderAnchor(APart part, float partialTicks) {
        Point3D[] anchors = renderAnchors.get(part);
        return anchors != null ? anchors[0].copy().interpolate(anchors[1], partialTicks) : getAnchor(part);
    }

    /**
     * Gives the cable variables functions that are evaluated at render time, so the cable
     * tracks the interpolated part position and doesn't lag a tick behind the car.
     * We reuse the existing variable objects, as animations hold references to them.
     * The whole launcher (housing, barrel, cable and hook) turns by the body angles about
     * {@link #PIVOT}.  The cable and hook then turn by the line angles about the barrel tip, so the
     * cable always leaves from the tip, whether or not the launcher itself can turn.
     */
    private static void setupRenderVariables(APart part) {
        setRenderFunction(part, VAR_BODY_YAW, partialTicks -> getAim(part, partialTicks)[0]);
        setRenderFunction(part, VAR_BODY_PITCH, partialTicks -> getAim(part, partialTicks)[1]);
        setRenderFunction(part, VAR_LINE_YAW, partialTicks -> getAim(part, partialTicks)[2]);
        setRenderFunction(part, VAR_LINE_PITCH, partialTicks -> getAim(part, partialTicks)[3]);
        setRenderFunction(part, VAR_LENGTH, partialTicks -> getAim(part, partialTicks)[4]);
    }

    /**
     * Returns {body yaw, body pitch, line yaw, line pitch, cable length} for the launcher.
     * Line angles are relative to the turned body.
     * While the line is out, a turning launcher points from its (moved) tip straight at the hook,
     * leaving the line angles near zero.  A fixed launcher stays put and the line angles do the work.
     * With the line in, a turning launcher points where the driver is looking.
     */
    private static double[] getAim(APart part, float partialTicks) {
        boolean fixed = isFixed(part);
        if (part.getOrCreateVariable(VAR_LINE_OUT).isActive) {
            Point3D launcherPosition = part.prevPosition.copy().interpolate(part.position, partialTicks);
            Point3D anchorLocal = getRenderAnchor(part, partialTicks).subtract(launcherPosition).reOrigin(part.orientation);

            double bodyYaw = 0;
            double bodyPitch = 0;
            if (!fixed) {
                //The tip moves as the launcher turns about the pivot, so aim from the pivot first,
                //then re-aim from where that puts the tip.  A few passes converge well inside a pixel.
                Point3D direction = anchorLocal.copy().subtract(PIVOT);
                for (int i = 0; i < 3; ++i) {
                    bodyYaw = getYaw(direction);
                    bodyPitch = getPitch(direction);
                    direction = anchorLocal.copy().subtract(getTip(bodyYaw, bodyPitch));
                }
                bodyYaw = getYaw(direction);
                bodyPitch = getPitch(direction);
            }

            //Line from the tip to the hook, in the turned body's coordinates.
            Point3D line = anchorLocal.subtract(getTip(bodyYaw, bodyPitch));
            double length = line.length();
            line.rotate(new RotationMatrix().setToAxisAngle(0, 1, 0, -bodyYaw)).rotate(new RotationMatrix().setToAxisAngle(1, 0, 0, -bodyPitch));
            return new double[] {bodyYaw, bodyPitch, getYaw(line), getPitch(line), length};
        }
        IWrapperEntity controller = part.getController();
        if (fixed || controller == null) {
            return new double[] {0, 0, 0, 0, 0};
        }
        Point3D look = controller.getLineOfSight(1).copy().reOrigin(part.orientation);
        return new double[] {getYaw(look), getPitch(look), 0, 0, 0};
    }

    /**Returns where the barrel tip (the model origin) ends up after aiming, in local coordinates.**/
    private static Point3D getTip(double yaw, double pitch) {
        return PIVOT.copy().invert().rotate(new RotationMatrix().setToAxisAngle(1, 0, 0, pitch)).rotate(new RotationMatrix().setToAxisAngle(0, 1, 0, yaw)).add(PIVOT);
    }

    /**Yaw that turns +Z towards the local vector, in degrees.  Zero for a zero vector.**/
    private static double getYaw(Point3D local) {
        return local.x == 0 && local.z == 0 ? 0 : Math.toDegrees(Math.atan2(local.x, local.z));
    }

    /**Pitch about +X that tilts +Z towards the local vector, in degrees.  Zero for a zero vector.**/
    private static double getPitch(Point3D local) {
        double length = local.length();
        return length > 1.0E-3 ? -Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, local.y / length)))) : 0;
    }

    private static void setRenderFunction(APart part, String name, ComputedVariableOperator function) {
        ComputedVariable variable = part.getOrCreateVariable(name);
        if (renderVariables.add(variable)) {
            variable.setFunctionTo(new ComputedVariable(part, name, function, true));
        }
    }

    /**The car's wheels, split into the launcher end and the far end of the car.  Spares don't count.**/
    private static class WheelSplit {
        final EntityVehicleF_Physics vehicle;
        final List<APart> launcherEnd = new ArrayList<>();
        final List<APart> farEnd = new ArrayList<>();
        /**1 if the launcher is ahead of the middle of the wheels, -1 if behind.**/
        final double side;

        WheelSplit(EntityVehicleF_Physics vehicle, APart launcher) {
            this.vehicle = vehicle;
            List<APart> wheels = new ArrayList<>();
            double centerZ = 0;
            for (APart other : vehicle.allParts) {
                if (other instanceof PartGroundDevice && !other.placementDefinition.isSpare) {
                    wheels.add(other);
                    centerZ += toLocal(vehicle, other.position).z;
                }
            }
            if (!wheels.isEmpty()) {
                centerZ /= wheels.size();
            }
            side = toLocal(vehicle, launcher.position).z >= centerZ ? 1 : -1;
            for (APart wheel : wheels) {
                ((toLocal(vehicle, wheel.position).z - centerZ) * side > 0 ? launcherEnd : farEnd).add(wheel);
            }
        }

        APart getLowestLauncherEnd() {
            APart lowest = launcherEnd.get(0);
            for (APart wheel : launcherEnd) {
                if (wheel.position.y - wheel.getHeight() / 2D < lowest.position.y - lowest.getHeight() / 2D) {
                    lowest = wheel;
                }
            }
            return lowest;
        }

        /**World height of the bottom of the lowest launcher-end wheel.**/
        double getLauncherEndBottom() {
            APart lowest = getLowestLauncherEnd();
            return lowest.position.y - lowest.getHeight() / 2D;
        }

        /**Middle of the launcher-end wheels, in the car's frame.**/
        Point3D getLauncherEndCenter() {
            Point3D center = new Point3D();
            for (APart wheel : launcherEnd) {
                center.add(toLocal(vehicle, wheel.position));
            }
            return center.scale(1D / launcherEnd.size());
        }
    }

    /**Server-side hook data.**/
    private static class Hook {
        final Point3D position = new Point3D();
        final Point3D velocity = new Point3D();
        /**The mob we're stuck in, if any.**/
        IWrapperEntity mob;
        /**The vehicle or placed part we're stuck in, if any.**/
        AEntityF_Multipart<?> multipart;
        /**Where the hook sits on what it's stuck in.  World-aligned for mobs, local for multiparts.**/
        Point3D offset;
    }

    /**Something the hook ran into.  Neither mob nor multipart set means a block.**/
    private static class Hit {
        final Point3D position;
        IWrapperEntity mob;
        AEntityF_Multipart<?> multipart;

        Hit(Point3D position) {
            this.position = position;
        }

        /**True for things the line can drag: mobs and vehicles, but not blocks or placed parts.**/
        boolean isMovable() {
            return mob != null || multipart instanceof EntityVehicleF_Physics;
        }
    }
}
