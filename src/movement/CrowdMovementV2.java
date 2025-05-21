package movement;

import core.Coord;
import core.Settings;
// import core.SimClock; // Not strictly needed if super.rng is used and well-seeded

// import java.util.ArrayList; // No longer needed
// import java.util.List;    // No longer needed
// import java.util.Random; // Using super.rng

/**
 * CrowdMovementV5: Further micro-optimization attempts on V4.
 * Focuses on the single-exclusion random choice.
 */
public class CrowdMovementV2 extends MovementModel { // Changed name for clarity

  // --- Model Parameters (from paper & interpretation) ---
  private static final int NUM_COMMUNITIES = 11;
  private static final int GATHERING_PLACE_AREA_INDEX = NUM_COMMUNITIES; // 11
  private static final int TOTAL_AREAS = NUM_COMMUNITIES + 1; // 12

  private static final int GRID_COLUMNS = 4;
  private static final int GRID_ROWS = 3;

  private static final double PROB_HOME_TO_GATHERING_PLACE = 0.8;
  private static final double PROB_AWAY_TO_HOME = 0.9;

  // --- Instance Variables ---
  private int homeAreaIndex; // 0 to NUM_COMMUNITIES-1
  private Coord currentDestination;
  private Coord lastPosition;

  private double cellWidth;
  private double cellHeight;
  private boolean dimensionsCached = false;

  public CrowdMovementV2(Settings settings) {
    super(settings);
  }

  protected CrowdMovementV2(CrowdMovementV2 proto) {
    super(proto);
    this.homeAreaIndex = proto.homeAreaIndex;
    this.cellWidth = proto.cellWidth;
    this.cellHeight = proto.cellHeight;
    this.dimensionsCached = proto.dimensionsCached;

    if (proto.lastPosition != null) {
      this.lastPosition = proto.lastPosition.clone();
    }
    // currentDestination will be set by the first getPath if lastPosition is null
    // or if it's a fresh path. If lastPosition is non-null, it implies a state.
    // However, currentDestination is decided *within* getPath, so it doesn't need
    // deep copy here.
  }

  private void cacheCellDimensions() {
    if (!dimensionsCached) {
      // Ensure getMaxX/Y return valid dimensions
      double worldX = getMaxX();
      double worldY = getMaxY();

      if (worldX <= 0 || worldY <= 0 || GRID_COLUMNS <= 0 || GRID_ROWS <= 0) {
        System.err.println("CRITICAL ERROR: CrowdMovementV5 - Invalid world/grid dimensions. World: "
            + worldX + "x" + worldY + ", Grid: " + GRID_COLUMNS + "x" + GRID_ROWS);
        // Fallback to prevent division by zero / negative cells
        this.cellWidth = 1.0;
        this.cellHeight = 1.0;
      } else {
        this.cellWidth = worldX / GRID_COLUMNS;
        this.cellHeight = worldY / GRID_ROWS;
      }
      dimensionsCached = true;
    }
  }

  @Override
  public Coord getInitialLocation() {
    assert rng != null : "MovementModel RNG not initialized!";
    cacheCellDimensions();

    this.homeAreaIndex = rng.nextInt(NUM_COMMUNITIES); // 0 to NUM_COMMUNITIES-1

    this.currentDestination = getRandomCoordInArea(this.homeAreaIndex);
    this.lastPosition = this.currentDestination.clone();
    return this.lastPosition;
  }

  @Override
  public Path getPath() {
    assert rng != null : "MovementModel RNG not initialized!";
    cacheCellDimensions();

    if (this.lastPosition == null) {
      getInitialLocation(); // Robust initialization
    }

    Path path = new Path(generateSpeed());
    path.addWaypoint(this.lastPosition.clone());

    int currentLogicalArea = getAreaIndexFromCoord(this.lastPosition);
    int nextLogicalArea = chooseNextLogicalArea(currentLogicalArea);

    this.currentDestination = getRandomCoordInArea(nextLogicalArea);
    path.addWaypoint(this.currentDestination.clone());

    this.lastPosition = this.currentDestination.clone();
    return path;
  }

  private int chooseNextLogicalArea(int currentAreaIdx) {
    double decisionRoll = rng.nextDouble();
    boolean isInHomeCommunity = (currentAreaIdx == this.homeAreaIndex);

    if (isInHomeCommunity) {
      if (decisionRoll < PROB_HOME_TO_GATHERING_PLACE) {
        return GATHERING_PLACE_AREA_INDEX;
      } else {
        return getRandomOtherCommunityIndexOptimized(this.homeAreaIndex);
      }
    } else {
      if (decisionRoll < PROB_AWAY_TO_HOME) {
        return this.homeAreaIndex;
      } else {
        if (currentAreaIdx == GATHERING_PLACE_AREA_INDEX) {
          return getRandomOtherCommunityIndexOptimized(this.homeAreaIndex);
        } else {
          if (rng.nextBoolean()) {
            return GATHERING_PLACE_AREA_INDEX;
          } else {
            return getRandomOtherCommunityIndexExcludingTwo(this.homeAreaIndex, currentAreaIdx);
          }
        }
      }
    }
  }

  /**
   * Optimized: Gets a random community index (0 to NUM_COMMUNITIES-1) that is NOT
   * the excludeIndex.
   * Avoids looping by direct mapping.
   */
  private int getRandomOtherCommunityIndexOptimized(int excludeIndex) {
    if (NUM_COMMUNITIES <= 1) {
      // If only one community, it must be the one to exclude, or no exclusion makes
      // sense.
      // This case implies no "other" community exists. Returning 0 (or a default) is
      // a fallback.
      return 0;
    }
    // Generate a random number in the range [0, NUM_COMMUNITIES - 2]
    // This range has one less item than the total number of communities.
    int randomIndex = rng.nextInt(NUM_COMMUNITIES - 1);

    // If the random index is greater than or equal to the one we want to exclude,
    // it means it falls in the part of the sequence *after* the excluded item.
    // So, we shift it by one to "skip over" the excluded item.
    if (randomIndex >= excludeIndex) {
      return randomIndex + 1;
    } else {
      return randomIndex; // The chosen index is before the excluded one, so it's fine.
    }
  }

  /**
   * Gets a random community index (0 to NUM_COMMUNITIES-1) that is NOT
   * excludeIndex1 NOR excludeIndex2.
   * Kept the loop version as direct mapping for two exclusions is more complex
   * and less readable
   * for potentially minor gain with N=11.
   */
  private int getRandomOtherCommunityIndexExcludingTwo(int excludeIndex1, int excludeIndex2) {
    if (NUM_COMMUNITIES < 1)
      return 0; // Should not happen
    if (NUM_COMMUNITIES == 1)
      return 0; // Only one option
    if (NUM_COMMUNITIES == 2) {
      // If we must exclude both, there's no choice. This is an edge case.
      // Or if one exclude is invalid.
      if (excludeIndex1 == 0 && excludeIndex2 == 1 || excludeIndex1 == 1 && excludeIndex2 == 0) {
        // This situation implies an issue with calling logic or NUM_COMMUNITIES
        // definition
        System.err.println(
            "WARN: Attempting to exclude all available communities in getRandomOtherCommunityIndexExcludingTwo");
        return 0; // Fallback
      }
      // If only one is excluded (e.g. exclude1=0, exclude2=5 (invalid)), return the
      // other valid one.
      return (excludeIndex1 == 0 || excludeIndex2 == 0) ? 1 : 0;
    }

    int chosenIndex;
    int retries = 0;
    final int MAX_RETRIES = NUM_COMMUNITIES * 2; // Safety break for unlikely infinite loop
    do {
      chosenIndex = rng.nextInt(NUM_COMMUNITIES);
      if (retries++ > MAX_RETRIES && NUM_COMMUNITIES > 2) { // Only break if there should be valid options
        System.err.println("WARN: Exceeded max retries in getRandomOtherCommunityIndexExcludingTwo. " +
            "Ex1: " + excludeIndex1 + ", Ex2: " + excludeIndex2 + ", Chosen: " + chosenIndex);
        // Fallback to just avoiding one of them if stuck
        return getRandomOtherCommunityIndexOptimized(excludeIndex1);
      }
    } while (chosenIndex == excludeIndex1 || chosenIndex == excludeIndex2);
    return chosenIndex;
  }

  private Coord getRandomCoordInArea(int areaIndex) {
    if (areaIndex < 0 || areaIndex >= TOTAL_AREAS) {
      System.err.println(
          "WARN: Invalid targetAreaIndex " + areaIndex + " in getRandomCoordInArea. Defaulting to random world coord.");
      return new Coord(rng.nextDouble() * getMaxX(), rng.nextDouble() * getMaxY());
    }

    int row = areaIndex / GRID_COLUMNS;
    int col = areaIndex % GRID_COLUMNS;

    double minX = col * this.cellWidth;
    double minY = row * this.cellHeight;

    double x = minX + (rng.nextDouble() * this.cellWidth);
    double y = minY + (rng.nextDouble() * this.cellHeight);

    x = Math.max(0, Math.min(x, getMaxX() - 1e-9)); // Using a small epsilon
    y = Math.max(0, Math.min(y, getMaxY() - 1e-9));

    return new Coord(x, y);
  }

  private int getAreaIndexFromCoord(Coord coord) {
    if (coord == null) {
      System.err.println("WARN: getCurrentAreaFromCoord called with null coord. Defaulting.");
      return GATHERING_PLACE_AREA_INDEX;
    }

    double x = coord.getX();
    double y = coord.getY();

    x = Math.max(0, Math.min(x, getMaxX() - 1e-9));
    y = Math.max(0, Math.min(y, getMaxY() - 1e-9));

    if (this.cellWidth <= 0 || this.cellHeight <= 0) { // Check cached values
      System.err.println("WARN: Zero or negative cell dimension in getAreaIndexFromCoord. " +
          "cellWidth: " + this.cellWidth + ", cellHeight: " + this.cellHeight +
          ". Recaching and defaulting.");
      dimensionsCached = false; // Force recache on next relevant call
      cacheCellDimensions(); // Attempt to fix
      if (this.cellWidth <= 0 || this.cellHeight <= 0)
        return GATHERING_PLACE_AREA_INDEX; // Still bad
    }

    int col = (int) (x / this.cellWidth);
    int row = (int) (y / this.cellHeight);

    col = Math.min(Math.max(col, 0), GRID_COLUMNS - 1);
    row = Math.min(Math.max(row, 0), GRID_ROWS - 1);

    return row * GRID_COLUMNS + col;
  }

  @Override
  public double nextPathAvailable() {
    return 0;
  }

  @Override
  public CrowdMovementV2 replicate() { // Changed return type
    return new CrowdMovementV2(this);
  }
}
