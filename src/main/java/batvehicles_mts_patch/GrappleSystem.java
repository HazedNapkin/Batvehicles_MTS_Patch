package batvehicles_mts_patch;

import java.util.Collections;
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
import minecrafttransportsimulator.jsondefs.JSONCollisionGroup.CollisionType;
import minecrafttransportsimulator.mcinterface.IWrapperEntity;

/**
 * Grappling-hook winch for MTS vehicles.
 * <p>
 * Any part whose generic type is {@link #PART_TYPE} acts as a grapple launcher.  Firing launches
 * the hook as a projectile towards whatever is under the driver's crosshair (or along the
 * launcher's +Z axis with no driver).  It flies up to {@link #RANGE} blocks and sticks into the
 * first block, mob or vehicle it hits.  Blocks hold the car, mobs get reeled in, and vehicles
 * share the pull by mass.
 * <p>
 * State the pack and clients need lives in MTS variables on the part, so MTS handles client sync
 * for us and pack JSON drives it with normal keybinds and animations:
 * <ul>
 * <li>{@link #VAR_FIRE}: toggle.  1 fires the hook, 0 releases it.  Set back to 0 by the server on a miss.</li>
 * <li>{@link #VAR_REEL}: button.  While held, the winch pulls the line in.</li>
 * <li>{@link #VAR_SLACK}: button.  While held, the winch lets the line out.</li>
 * <li>{@link #VAR_STATE}: read-only.  One of the STATE_ constants.</li>
 * <li>{@link #VAR_LINE_OUT}: read-only.  1 while the hook is flying or stuck in something.</li>
 * <li>{@link #VAR_ATTACHED}: read-only.  1 while the hook is stuck in something.</li>
 * <li>{@link #VAR_BODY_YAW}, {@link #VAR_BODY_PITCH}: read-only, for rendering.  Turn the whole
 * launcher about {@link #PIVOT}: at the hook while the line is out, otherwise where the driver looks.
 * Always zero for fixed launchers.</li>
 * <li>{@link #VAR_LINE_YAW}, {@link #VAR_LINE_PITCH}, {@link #VAR_LENGTH}: read-only, for rendering.
 * Turn the cable and hook about the barrel tip, relative to the body, and the cable length from the tip to the hook.</li>
 * </ul>
 * Definition constants pick the variant: {@link #CONSTANT_FIXED} and {@link #CONSTANT_ENTITIES_ONLY}.
 * The line only ever pulls.  When it's taut, whatever is on each end can't move further apart,
 * gets pulled back to the line length, and the car's heading is steered along any swing so the
 * tyres don't fight the turn.
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

    /**Definition constant: the launcher doesn't turn, and fires straight along its +Z axis.**/
    public static final String CONSTANT_FIXED = "grapple_fixed";
    /**Definition constant: the hook only sticks into mobs and vehicles.  It bounces off blocks.**/
    public static final String CONSTANT_ENTITIES_ONLY = "grapple_entities_only";

    public static final int STATE_IDLE = 0;
    public static final int STATE_FLYING = 1;
    public static final int STATE_BLOCK = 2;
    public static final int STATE_MOB = 3;
    public static final int STATE_VEHICLE = 4;

    /**Point the launcher turns about: the bottom back edge of the model, in part coordinates.
     * Must match the animation centerPoint in the part JSON.**/
    private static final Point3D PIVOT = new Point3D(0, -2D / 16D, -8D / 16D);
    /**How far the hook can fly, in blocks.**/
    private static final double RANGE = 100;
    /**Hook flight speed, in blocks per tick (100 blocks in 1.25 seconds).**/
    private static final double HOOK_SPEED = 4;
    /**Shortest the winch can reel the line, in blocks.**/
    private static final double MIN_ROPE = 2;
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
    /**How often the server checks the anchor block still exists, in ticks.**/
    private static final int ANCHOR_CHECK_INTERVAL = 10;

    /**Server-only hook state that can't live in a variable: flight and what we're stuck in.**/
    private static final Map<APart, Hook> hooks = new WeakHashMap<>();
    /**Client-only hook positions for the last two ticks, so the render can interpolate.**/
    private static final Map<APart, Point3D[]> renderAnchors = new WeakHashMap<>();
    /**Cable variables we've already given a render function to.  Weak so removed parts don't leak.**/
    private static final Set<ComputedVariable> renderVariables = Collections.newSetFromMap(new WeakHashMap<>());

    private GrappleSystem() {
    }

    public static boolean isGrapple(APart part) {
        return part.definition.generic != null && PART_TYPE.equals(part.definition.generic.type);
    }

    /**
     * Called on both client and server, once per vehicle tick, just before MTS moves the vehicle.
     */
    public static void updateVehicle(EntityVehicleF_Physics vehicle) {
        for (APart part : vehicle.allParts) {
            if (part.isValid && isGrapple(part)) {
                updateGrapple(vehicle, part);
            }
        }
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
                applyLineForces(vehicle, part, getAnchor(part), part.getOrCreateVariable(VAR_ROPE).currentValue, selfShare);
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
     * Fixed launchers ignore the crosshair and fire straight ahead.
     */
    private static void launch(EntityVehicleF_Physics vehicle, APart part) {
        Point3D start = part.position.copy();
        Point3D target;
        IWrapperEntity controller = part.getController();
        if (controller != null && !isFixed(part)) {
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

        //Rigid line: remove any velocity taking us further from the anchor.
        double radialSpeed = vehicle.motion.dotProduct(lineNormal, false);
        if (radialSpeed < 0) {
            vehicle.motion.addScaled(lineNormal, -radialSpeed * share);
        }

        //Pull back towards the line length.  Motion gets scaled by speedFactor when applied, so undo that here.
        double pull = Math.min((distance - rope) * STIFFNESS, MAX_PULL) * share;
        vehicle.motion.addScaled(lineNormal, pull / vehicle.speedFactor);

        //Steer the heading along the swing.  Without this, tyre grip snaps our motion back to
        //the heading next tick and the line can't swing us round a corner.
        Point3D groundMotion = new Point3D(vehicle.motion.x, 0, vehicle.motion.z);
        Point3D groundNormal = new Point3D(lineNormal.x, 0, lineNormal.z);
        if (groundMotion.length() > 0.05 && groundNormal.length() > 1.0E-3) {
            groundNormal.normalize();
            Point3D swingDirection = groundMotion.addScaled(groundNormal, -groundMotion.dotProduct(groundNormal, false));
            if (swingDirection.length() > 0.01) {
                swingDirection.normalize();
                if (vehicle.goingInReverse) {
                    swingDirection.invert();
                }
                Point3D heading = new Point3D(vehicle.headingVector.x, 0, vehicle.headingVector.z).normalize();
                double headingError = Math.toDegrees(Math.atan2(heading.crossProduct(swingDirection).y, heading.dotProduct(swingDirection, true)));
                double yawAdded = Math.max(-MAX_YAW_ASSIST, Math.min(MAX_YAW_ASSIST, headingError * YAW_ASSIST * share));
                vehicle.rotation.angles.y += yawAdded;
            }
        }
    }

    private static boolean isFixed(APart part) {
        return part.getOrCreateVariable(CONSTANT_FIXED).isActive;
    }

    private static boolean isEntitiesOnly(APart part) {
        return part.getOrCreateVariable(CONSTANT_ENTITIES_ONLY).isActive;
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
        if (anchors == null || !part.getOrCreateVariable(VAR_LINE_OUT).isActive) {
            //No line out, so there's nothing to interpolate from.  Start fresh at the next launch.
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
