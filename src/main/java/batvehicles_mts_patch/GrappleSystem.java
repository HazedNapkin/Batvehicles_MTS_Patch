package batvehicles_mts_patch;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import minecrafttransportsimulator.baseclasses.BlockHitResult;
import minecrafttransportsimulator.baseclasses.ComputedVariable;
import minecrafttransportsimulator.baseclasses.ComputedVariable.ComputedVariableOperator;
import minecrafttransportsimulator.baseclasses.Point3D;
import minecrafttransportsimulator.entities.instances.APart;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import minecrafttransportsimulator.mcinterface.IWrapperEntity;

/**
 * Grappling-hook winch for MTS vehicles.
 * <p>
 * Any part whose generic type is {@link #PART_TYPE} acts as a grapple launcher.  It fires
 * where the driver is looking, or along its own +Z axis if there's no driver.  All state lives
 * in MTS variables on that part, which means pack JSON drives it with normal keybinds and
 * animations, and MTS handles saving and client sync for us:
 * <ul>
 * <li>{@link #VAR_FIRE}: toggle.  1 fires the line, 0 releases it.  Set back to 0 by the server on a miss.</li>
 * <li>{@link #VAR_REEL}: button.  While held, the winch pulls the line in.</li>
 * <li>{@link #VAR_SLACK}: button.  While held, the winch lets the line out.</li>
 * <li>{@link #VAR_ATTACHED}: read-only.  1 while the hook is set in a block.</li>
 * <li>{@link #VAR_LENGTH}, {@link #VAR_YAW}, {@link #VAR_PITCH}: read-only, for rendering the cable.
 * Distance to the anchor, and the angles that point the part's +Z axis at it.</li>
 * </ul>
 * The line only ever pulls.  When it's taut, the vehicle can't move further from the anchor,
 * gets pulled back to the line length, and its heading is steered along the swing so the
 * tyres don't fight the turn.
 */
public final class GrappleSystem {
    public static final String PART_TYPE = "generic_grapple";

    public static final String VAR_FIRE = "grapple_fire";
    public static final String VAR_REEL = "grapple_reel";
    public static final String VAR_SLACK = "grapple_slack";
    public static final String VAR_ATTACHED = "grapple_attached";
    public static final String VAR_ROPE = "grapple_rope";
    public static final String VAR_ANCHOR_X = "grapple_anchor_x";
    public static final String VAR_ANCHOR_Y = "grapple_anchor_y";
    public static final String VAR_ANCHOR_Z = "grapple_anchor_z";
    public static final String VAR_LENGTH = "grapple_length";
    public static final String VAR_YAW = "grapple_yaw";
    public static final String VAR_PITCH = "grapple_pitch";

    /**How far the hook can fly, in blocks.**/
    private static final double RANGE = 48;
    /**Shortest the winch can reel the line, in blocks.**/
    private static final double MIN_ROPE = 2;
    /**Winch speed, in blocks per tick (5 blocks/second).**/
    private static final double REEL_SPEED = 0.25;
    /**Fraction of any over-stretch pulled back each tick.  Higher is a stiffer line.**/
    private static final double STIFFNESS = 0.3;
    /**Cap on the pull-back speed from stretch, in blocks per tick, so the line can't fling the car.**/
    private static final double MAX_PULL = 0.6;
    /**Fraction of the heading error to the swing direction corrected each tick.**/
    private static final double YAW_ASSIST = 0.35;
    /**Cap on the yaw added by the line, in degrees per tick.**/
    private static final double MAX_YAW_ASSIST = 6;
    /**How often the server checks the anchor block still exists, in ticks.**/
    private static final int ANCHOR_CHECK_INTERVAL = 10;

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

        ComputedVariable attachedVar = part.getOrCreateVariable(VAR_ATTACHED);
        ComputedVariable ropeVar = part.getOrCreateVariable(VAR_ROPE);

        //The server owns the hook.  It decides hits and line length, and syncs them to clients.
        if (!vehicle.world.isClient()) {
            ComputedVariable fireVar = part.getOrCreateVariable(VAR_FIRE);
            if (fireVar.isActive && !attachedVar.isActive) {
                fire(part, fireVar, attachedVar, ropeVar);
            } else if (!fireVar.isActive && attachedVar.isActive) {
                attachedVar.setTo(0, true);
            } else if (attachedVar.isActive) {
                Point3D anchor = getAnchor(part);
                if (vehicle.ticksExisted % ANCHOR_CHECK_INTERVAL == 0 && (vehicle.world.isAir(anchor) || !anchor.isDistanceToCloserThan(part.position, RANGE * 1.5))) {
                    //Anchor block broken, or we got teleported away.  Drop the line.
                    fireVar.setTo(0, true);
                    attachedVar.setTo(0, true);
                } else {
                    double rope = ropeVar.currentValue;
                    if (part.getOrCreateVariable(VAR_REEL).isActive) {
                        rope -= REEL_SPEED;
                    } else if (part.getOrCreateVariable(VAR_SLACK).isActive) {
                        rope += REEL_SPEED;
                    }
                    ropeVar.setTo(Math.max(MIN_ROPE, Math.min(RANGE, rope)), true);
                }
            }
        }

        //Both sides run the same line physics so clients don't have to wait on server corrections.
        if (attachedVar.isActive && vehicle.towedByConnection == null) {
            applyLineForces(vehicle, part, getAnchor(part), ropeVar.currentValue);
        }
    }

    private static void fire(APart part, ComputedVariable fireVar, ComputedVariable attachedVar, ComputedVariable ropeVar) {
        //Aim where the driver is looking, so they can pick a corner with the crosshair.
        //With no driver, fire straight along the launcher.
        Point3D start;
        Point3D delta;
        IWrapperEntity controller = part.getController();
        if (controller != null) {
            start = controller.getEyePosition().copy();
            delta = controller.getLineOfSight(RANGE).copy();
        } else {
            start = part.position.copy();
            delta = new Point3D(0, 0, RANGE).rotate(part.orientation);
        }
        BlockHitResult hit = part.world.getBlockHit(start, delta);
        if (hit != null) {
            //Store the anchor a hair inside the block, so the air check finds the block we hit and not the space in front of it.
            Point3D anchor = hit.hitPosition.copy().addScaled(delta.copy().normalize(), 0.01);
            part.getOrCreateVariable(VAR_ANCHOR_X).setTo(anchor.x, true);
            part.getOrCreateVariable(VAR_ANCHOR_Y).setTo(anchor.y, true);
            part.getOrCreateVariable(VAR_ANCHOR_Z).setTo(anchor.z, true);
            ropeVar.setTo(Math.max(MIN_ROPE, part.position.distanceTo(anchor)), true);
            attachedVar.setTo(1, true);
        } else {
            //Missed.  Reset the toggle so the next press fires again.
            fireVar.setTo(0, true);
        }
    }

    private static Point3D getAnchor(APart part) {
        return new Point3D(part.getOrCreateVariable(VAR_ANCHOR_X).currentValue, part.getOrCreateVariable(VAR_ANCHOR_Y).currentValue, part.getOrCreateVariable(VAR_ANCHOR_Z).currentValue);
    }

    private static void applyLineForces(EntityVehicleF_Physics vehicle, APart part, Point3D anchor, double rope) {
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
            vehicle.motion.addScaled(lineNormal, -radialSpeed);
        }

        //Pull back towards the line length.  Motion gets scaled by speedFactor when applied, so undo that here.
        double pull = Math.min((distance - rope) * STIFFNESS, MAX_PULL);
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
                double yawAdded = Math.max(-MAX_YAW_ASSIST, Math.min(MAX_YAW_ASSIST, headingError * YAW_ASSIST));
                vehicle.rotation.angles.y += yawAdded;
            }
        }
    }

    /**
     * Gives the cable variables functions that are evaluated at render time, so the cable
     * tracks the interpolated part position and doesn't lag a tick behind the car.
     * We reuse the existing variable objects, as animations hold references to them.
     */
    private static void setupRenderVariables(APart part) {
        setRenderFunction(part, VAR_LENGTH, partialTicks -> getLocalLineVector(part, partialTicks).length());
        setRenderFunction(part, VAR_YAW, partialTicks -> {
            Point3D local = getLocalLineVector(part, partialTicks);
            return Math.toDegrees(Math.atan2(local.x, local.z));
        });
        setRenderFunction(part, VAR_PITCH, partialTicks -> {
            Point3D local = getLocalLineVector(part, partialTicks);
            double length = local.length();
            return length > 1.0E-3 ? -Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, local.y / length)))) : 0;
        });
    }

    private static void setRenderFunction(APart part, String name, ComputedVariableOperator function) {
        ComputedVariable variable = part.getOrCreateVariable(name);
        if (renderVariables.add(variable)) {
            variable.setFunctionTo(new ComputedVariable(part, name, function, true));
        }
    }

    /**
     * Returns the vector from the part to the anchor in the part's local coordinates.
     * Zero if not attached.
     */
    private static Point3D getLocalLineVector(APart part, float partialTicks) {
        if (!part.getOrCreateVariable(VAR_ATTACHED).isActive) {
            return new Point3D();
        }
        Point3D hookPosition = part.prevPosition.copy().interpolate(part.position, partialTicks);
        return getAnchor(part).subtract(hookPosition).reOrigin(part.orientation);
    }
}
