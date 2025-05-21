package movement;

import core.Coord;
import core.Settings;

/**
 * LevyWalkMovement: Simulates Lévy Walk for nodes within world boundaries.
 * Step lengths are drawn from a Pareto-like distribution controlled by 'alpha'.
 * If a step would take the node out of bounds, the node remains stationary for
 * that step.
 */
public class LevyWalkMovement extends MovementModel {

  /** LevyWalkMovement settings namespace ({@value}) */
  public static final String LEVY_WALK_NS = "LevyWalkMovement";
  /**
   * Power-law exponent (alpha) for step length distribution -setting id
   * ({@value}).
   * Smaller alpha means more frequent long jumps. Typical range 0 < alpha <= 2.
   * Default value is {@link #DEFAULT_ALPHA}.
   */
  public static final String ALPHA_S = "alpha";
  /**
   * Minimum step length - setting id ({@value}).
   * To prevent zero or extremely small steps from Pareto generation if shifted.
   * Default value is {@link #DEFAULT_MIN_STEP}.
   */
  public static final String MIN_STEP_S = "minStep";
  /**
   * Scale factor for step length - setting id ({@value}).
   * Multiplies the value from the Pareto distribution.
   * Default value is {@link #DEFAULT_SCALE_FACTOR}.
   */
  public static final String SCALE_FACTOR_S = "scaleFactor";

  public static final double DEFAULT_ALPHA = 1.5;
  public static final double DEFAULT_MIN_STEP = 0.1;
  public static final double DEFAULT_SCALE_FACTOR = 1.0;

  private double alpha;
  private double minStep;
  private double scaleFactor;

  private Coord currentPosition;

  public LevyWalkMovement(Settings settings) {
    super(settings);

    Settings levySettings = new Settings(LEVY_WALK_NS);
    this.alpha = levySettings.getDouble(ALPHA_S, DEFAULT_ALPHA);
    this.minStep = levySettings.getDouble(MIN_STEP_S, DEFAULT_MIN_STEP);
    this.scaleFactor = levySettings.getDouble(SCALE_FACTOR_S, DEFAULT_SCALE_FACTOR);

    if (this.alpha <= 0) {
      throw new IllegalArgumentException("Alpha must be > 0 for Levy Walk.");
    }
    if (this.minStep < 0) {
      throw new IllegalArgumentException("Minimum step must be >= 0.");
    }
  }

  protected LevyWalkMovement(LevyWalkMovement proto) {
    super(proto);
    this.alpha = proto.alpha;
    this.minStep = proto.minStep;
    this.scaleFactor = proto.scaleFactor;

    if (proto.currentPosition != null) {
      this.currentPosition = proto.currentPosition.clone();
    }
  }

  @Override
  public Coord getInitialLocation() {
    assert rng != null : "MovementModel RNG not initialized!";

    double worldX = getMaxX();
    double worldY = getMaxY();

    double x = rng.nextDouble() * worldX;
    double y = rng.nextDouble() * worldY;

    this.currentPosition = new Coord(x, y);
    return this.currentPosition.clone();
  }

  @Override
  public Path getPath() {
    assert rng != null : "MovementModel RNG not initialized!";

    if (this.currentPosition == null) {
      getInitialLocation();
    }

    Path path = new Path(generateSpeed());
    path.addWaypoint(this.currentPosition.clone());

    double u = 0.0;
    while (u == 0.0) {
      u = rng.nextDouble();
    }
    double paretoStep = Math.pow(u, -1.0 / this.alpha);
    double stepLength = this.minStep + paretoStep * this.scaleFactor;

    double theta = rng.nextDouble() * 2.0 * Math.PI;

    double dx = stepLength * Math.cos(theta);
    double dy = stepLength * Math.sin(theta);
    double newX = this.currentPosition.getX() + dx;
    double newY = this.currentPosition.getY() + dy;

    double worldMaxX = getMaxX();
    double worldMaxY = getMaxY();

    if (newX >= 0 && newX <= worldMaxX && newY >= 0 && newY <= worldMaxY) {
      this.currentPosition.setLocation(newX, newY);
    }

    path.addWaypoint(this.currentPosition.clone());

    return path;
  }

  @Override
  public double nextPathAvailable() {
    return 0;
  }

  @Override
  public LevyWalkMovement replicate() {
    return new LevyWalkMovement(this);
  }
}
