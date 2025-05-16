/*
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details.
 */
package movement;

import core.Coord;
import core.Settings;

import java.util.ArrayList;
import java.util.List;

/**
 * Crowd movement model (Version 2) based on home areas and a central gathering
 * place,
 * inspired by the logic from "Evaluation of Queueing Policies and Forwarding
 * Strategies for Routing in Intermittently Connected Networks" by Lindgren et
 * al.
 * Nodes have a home community and probabilistically move between their home,
 * a gathering place, and other communities.
 */
public class CrowdMovementV2 extends MovementModel {

  /**
   * How many waypoints should there be per path. A single waypoint means
   * moving directly to a location in the chosen next area.
   */
  private static final int PATH_LENGTH = 1;

  private Coord lastWaypoint;

  private int homeArea = -1;

  private static final int GATHERING_PLACE_AREA_ID = 9;
  private static final int MIN_COMMUNITY_AREA_ID = 1;
  private static final int MAX_COMMUNITY_AREA_ID = 8;
  private static final int NUM_COMMUNITY_AREAS = MAX_COMMUNITY_AREA_ID - MIN_COMMUNITY_AREA_ID + 1;

  private static final double PROB_HOME_TO_GATHERING_PLACE = 0.8;
  private static final double PROB_AWAY_TO_HOME = 0.9;

  public CrowdMovementV2(Settings settings) {
    super(settings);
  }

  protected CrowdMovementV2(CrowdMovementV2 rwp) {
    super(rwp);
    this.homeArea = rwp.homeArea;
  }

  /**
   * Returns the initial placement for a host.
   * Assigns a home area and places the node within its home area.
   *
   * @return Initial position on the map, within the node's home area.
   */
  @Override
  public Coord getInitialLocation() {
    assert rng != null : "MovementModel not initialized!";

    if (this.homeArea == -1) {
      this.homeArea = rng.nextInt(NUM_COMMUNITY_AREAS) + MIN_COMMUNITY_AREA_ID;
    }

    Coord initialCoord = generateCoordInTargetArea(this.homeArea);
    this.lastWaypoint = initialCoord;
    return initialCoord;
  }

  @Override
  public Path getPath() {
    Path p = new Path(generateSpeed());
    p.addWaypoint(this.lastWaypoint.clone());

    int nextAreaId = chooseNextArea();
    Coord nextDestCoord = generateCoordInTargetArea(nextAreaId);

    for (int i = 0; i < PATH_LENGTH; i++) {
      p.addWaypoint(nextDestCoord);
    }

    this.lastWaypoint = nextDestCoord.clone();
    return p;
  }

  /**
   * Chooses the next area to move to based on current location and home area.
   * This implements the logic from Table 1 of the reference paper.
   *
   * @return The ID of the next area to target.
   */
  protected int chooseNextArea() {
    if (this.homeArea == -1) {
      return rng.nextInt(NUM_COMMUNITY_AREAS) + MIN_COMMUNITY_AREA_ID;
    }
    if (this.lastWaypoint == null) {
      return this.homeArea;
    }

    int currentArea = getCurrentAreaFromCoord(this.lastWaypoint);
    double decisionRoll = rng.nextDouble();

    if (currentArea == this.homeArea) {
      if (decisionRoll < PROB_HOME_TO_GATHERING_PLACE) {
        return GATHERING_PLACE_AREA_ID;
      } else {
        return chooseRandomOtherCommunity(this.homeArea, -1);
      }
    } else {
      if (decisionRoll < PROB_AWAY_TO_HOME) {
        return this.homeArea;
      } else {
        int excludeCurrent = (currentArea != GATHERING_PLACE_AREA_ID) ? currentArea : -1;
        return chooseRandomOtherCommunity(this.homeArea, excludeCurrent);
      }
    }
  }

  /**
   * Helper to choose a random community area, excluding specified areas.
   * 
   * @param excludeArea1 Area ID to exclude.
   * @param excludeArea2 Second area ID to exclude (use -1 if none).
   * @return A valid community area ID.
   */
  private int chooseRandomOtherCommunity(int excludeArea1, int excludeArea2) {
    List<Integer> possibleAreas = new ArrayList<>();
    for (int i = MIN_COMMUNITY_AREA_ID; i <= MAX_COMMUNITY_AREA_ID; i++) {
      if (i != excludeArea1 && i != excludeArea2) {
        possibleAreas.add(i);
      }
    }

    if (possibleAreas.isEmpty()) {
      return (this.homeArea != -1 && this.homeArea != excludeArea1 && this.homeArea != excludeArea2) ? this.homeArea
          : MIN_COMMUNITY_AREA_ID;
    }
    return possibleAreas.get(rng.nextInt(possibleAreas.size()));
  }

  /**
   * Generates a random coordinate within the specified target area.
   * The area definitions follow a 3x3 grid:
   * 1 2 3
   * 4 9 5 (9 is Gathering Place)
   * 6 7 8
   * 
   * @param targetAreaID The ID of the area (1-9).
   * @return A random coordinate within that area.
   */
  protected Coord generateCoordInTargetArea(int targetAreaID) {
    double x = 0, y = 0;
    double maxX = getMaxX();
    double maxY = getMaxY();

    double thirdX = maxX / 3.0;
    double twoThirdsX = maxX * 2.0 / 3.0;
    double thirdY = maxY / 3.0;
    double twoThirdsY = maxY * 2.0 / 3.0;

    switch (targetAreaID) {
      case 1:
        x = rng.nextDouble() * thirdX;
        y = rng.nextDouble() * (maxY - twoThirdsY) + twoThirdsY;
        break;
      case 2:
        x = rng.nextDouble() * (twoThirdsX - thirdX) + thirdX;
        y = rng.nextDouble() * (maxY - twoThirdsY) + twoThirdsY;
        break;
      case 3:
        x = rng.nextDouble() * (maxX - twoThirdsX) + twoThirdsX;
        y = rng.nextDouble() * (maxY - twoThirdsY) + twoThirdsY;
        break;
      case 4:
        x = rng.nextDouble() * thirdX;
        y = rng.nextDouble() * (twoThirdsY - thirdY) + thirdY;
        break;
      case GATHERING_PLACE_AREA_ID:
        x = rng.nextDouble() * (twoThirdsX - thirdX) + thirdX;
        y = rng.nextDouble() * (twoThirdsY - thirdY) + thirdY;
        break;
      case 5:
        x = rng.nextDouble() * (maxX - twoThirdsX) + twoThirdsX;
        y = rng.nextDouble() * (twoThirdsY - thirdY) + thirdY;
        break;
      case 6:
        x = rng.nextDouble() * thirdX;
        y = rng.nextDouble() * thirdY;
        break;
      case 7:
        x = rng.nextDouble() * (twoThirdsX - thirdX) + thirdX;
        y = rng.nextDouble() * thirdY;
        break;
      case 8:
        x = rng.nextDouble() * (maxX - twoThirdsX) + twoThirdsX;
        y = rng.nextDouble() * thirdY;
        break;
      default:
        x = rng.nextDouble() * maxX;
        y = rng.nextDouble() * maxY;
    }
    return new Coord(x, y);
  }

  /**
   * Determines which area a given coordinate falls into.
   * Uses the same 3x3 grid as generateCoordInTargetArea.
   * 
   * @param coord The coordinate to check.
   * @return The ID of the area (1-9).
   */
  private int getCurrentAreaFromCoord(Coord coord) {
    if (coord == null) {
      return GATHERING_PLACE_AREA_ID;
    }

    double x = coord.getX();
    double y = coord.getY();
    double maxX = getMaxX();
    double maxY = getMaxY();

    double x_boundary1 = maxX / 3.0;
    double x_boundary2 = maxX * 2.0 / 3.0;
    double y_boundary1 = maxY / 3.0;
    double y_boundary2 = maxY * 2.0 / 3.0;

    if (y >= y_boundary2) {
      if (x < x_boundary1)
        return 1;
      if (x < x_boundary2)
        return 2;
      return 3;
    } else if (y >= y_boundary1) {
      if (x < x_boundary1)
        return 4;
      if (x < x_boundary2)
        return GATHERING_PLACE_AREA_ID;
      return 5;
    } else {
      if (x < x_boundary1)
        return 6;
      if (x < x_boundary2)
        return 7;
      return 8;
    }
  }

  @Override
  public CrowdMovementV2 replicate() {
    return new CrowdMovementV2(this);
  }
}
