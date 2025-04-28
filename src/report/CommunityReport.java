/*
 * Copyright 2010-2023 The ONE Project
 * Copyright 2010 University of Pittsburgh (Original Community Detection Concept)
 * Released under GPLv3. See LICENSE.txt for details.
 */
package report;

import core.DTNHost;
import core.World;
import core.SimClock;
import core.SimScenario;
import routing.MessageRouter;
import routing.community.CommunityDetectionEngine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * Reports the final community structure observed by nodes in the simulation.
 * This report queries nodes whose routers implement the
 * {@link routing.community.CommunityDetectionEngine} interface to retrieve
 * their view of their local community at the end of the simulation.
 * </p>
 *
 * <p>
 * It collects all reported communities and then prints only the unique
 * community sets found across all nodes.
 * </p>
 *
 * <p>
 * This report relies on the router implementations (e.g., BubbleRapRouter)
 * correctly managing and exposing their community state via the
 * CommunityDetectionEngine interface.
 * </p>
 *
 * <p>
 * NOTE: This report accesses the simulation's World object via
 * SimScenario.getInstance().getWorld(). This assumes SimScenario is implemented
 * as a singleton in your ONE version.
 * </p>
 *
 * @author PJ Dillon (University of Pittsburgh, original concept)
 * @author Generated AI (Integration and refinement for ONE)
 */
public class CommunityReport extends Report {

  private Set<Set<DTNHost>> uniqueCommunities; // Use a Set of Sets for automatic deduplication

  /**
   * Constructor. Initializes the storage for unique communities.
   */
  public CommunityReport() {
    init();
  }

  @Override
  protected void init() {
    super.init();
    this.uniqueCommunities = new HashSet<>();
  }

  /**
   * Called when the simulation is done. Collects community information from
   * all capable nodes and writes the unique communities found.
   */
  @Override
  public void done() {
    write("Community Report at sim time " + SimClock.getIntTime());
    write("--------------------------------------------------");

    World currentWorld = null;
    try {
      // Access the World instance through the SimScenario singleton
      currentWorld = SimScenario.getInstance().getWorld();
    } catch (NullPointerException e) {
      // Handle cases where SimScenario or World might not be initialized yet
      // Although in done() this should generally be safe.
      write("ERROR: Could not obtain World instance via SimScenario. Cannot generate community report.");
      super.done();
      return;
    } catch (Exception e) {
      // Catch other potential exceptions during access
      write("ERROR: Exception while obtaining World instance via SimScenario: " + e.getMessage());
      super.done();
      return;
    }

    if (currentWorld == null) {
      write("ERROR: Obtained null World instance via SimScenario. Cannot generate community report.");
      super.done();
      return;
    }

    List<DTNHost> hosts = currentWorld.getHosts(); // Get hosts from the obtained instance
    if (hosts == null) {
      write("ERROR: World instance returned null host list. Cannot generate community report.");
      super.done();
      return;
    }

    int checkedNodes = 0;
    int communityCapableNodes = 0;

    for (DTNHost host : hosts) {
      if (host == null)
        continue; // Safety check

      checkedNodes++;
      MessageRouter router = host.getRouter();

      // Check if the router implements the required interface
      if (router instanceof CommunityDetectionEngine) {
        communityCapableNodes++;
        CommunityDetectionEngine cde = (CommunityDetectionEngine) router;
        Set<DTNHost> localCommunity = cde.getLocalCommunity();

        if (localCommunity != null && !localCommunity.isEmpty()) {
          // Store a copy for uniqueness check
          this.uniqueCommunities.add(new HashSet<>(localCommunity));
        }
        // else { // Optionally report nodes with empty/null communities }
      }
    }

    if (communityCapableNodes == 0) {
      write("No nodes found with routers implementing CommunityDetectionEngine.");
    } else {
      write("Queried " + checkedNodes + " nodes. Found " + communityCapableNodes
          + " nodes capable of reporting communities.");
      write("Found " + this.uniqueCommunities.size() + " unique communities:");

      // Sort communities by size (descending) for nicer output
      List<Set<DTNHost>> sortedCommunities = new ArrayList<>(this.uniqueCommunities);
      sortedCommunities.sort((set1, set2) -> Integer.compare(set2.size(), set1.size()));

      int communityIndex = 1;
      for (Set<DTNHost> community : sortedCommunities) {
        // Sort members within the community alphabetically by name for consistent
        // output
        List<String> memberNames = community.stream()
            .map(h -> h != null ? h.toString() : "null_host") // Safer mapping
            .sorted()
            .collect(Collectors.toList());

        write("Community " + (communityIndex++) + " (size " + community.size() + "): " + memberNames);
      }
    }

    write("--------------------------------------------------");
    super.done(); // Finalize report writing (close file etc.)
  }
}
