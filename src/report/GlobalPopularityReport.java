package report;

import core.DTNHost;
import core.SimClock;
import core.SimScenario;
import core.World;
import routing.BubbleRapRouter; // Need to import BubbleRapRouter
import routing.MessageRouter;
import routing.community.Centrality; // Import Centrality
import routing.community.Duration; // Import Duration

import java.util.List;
import java.util.Map;
import java.util.TreeMap; // To sort by host ID

/**
 * Reports the final global popularity (centrality) of nodes using the
 * BubbleRapRouter at the end of the simulation. The output is a CSV file
 * with columns "Node - ID" and "Popularity".
 */
public class GlobalPopularityReport extends Report {

    private Map<Integer, Double> globalPopularities; // Store popularity by host address

    /**
     * Constructor. Initializes the storage for popularities.
     */
    public GlobalPopularityReport() {
        init();
    }

    @Override
    protected void init() {
        super.init();
        // Use TreeMap to store results sorted by node ID (address)
        this.globalPopularities = new TreeMap<>();
    }

    /**
     * Called when the simulation is done. Collects global popularity information
     * from all BubbleRapRouter nodes and writes the report.
     */
    @Override
    public void done() {
        // Generate the long string of commas once
        String trailingCommas = new String(new char[254]).replace("\0", ",");

        write("Global Popularity Report at sim time " + SimClock.getIntTime());
        write("Node - ID,Popularity" + trailingCommas); // CSV Header with trailing commas
        write("--------------------------------------------------");

        World currentWorld = null;
        try {
             // Access the World instance through the SimScenario singleton
             currentWorld = SimScenario.getInstance().getWorld();
         } catch (NullPointerException e) {
             write("ERROR: Could not obtain World instance via SimScenario. Cannot generate report.");
             super.done();
             return;
         } catch (Exception e) {
             write("ERROR: Exception while obtaining World instance via SimScenario: " + e.getMessage());
             super.done();
             return;
         }


        if (currentWorld == null) {
            write("ERROR: Obtained null World instance via SimScenario. Cannot generate report.");
            super.done();
            return;
        }

        List<DTNHost> hosts = currentWorld.getHosts();
        if (hosts == null) {
            write("ERROR: World instance returned null host list. Cannot generate report.");
            super.done();
            return;
        }

        int checkedNodes = 0;
        int bubbleRapNodes = 0;

        for (DTNHost host : hosts) {
            if (host == null) continue;

            checkedNodes++;
            MessageRouter router = host.getRouter();

            // Check if the router is a BubbleRapRouter
            if (router instanceof BubbleRapRouter) {
                bubbleRapNodes++;
                BubbleRapRouter bubbleRouter = (BubbleRapRouter) router;

                // Access centrality algorithm and connection history via assumed public getters
                Centrality centralityAlg = bubbleRouter.getCentrality(); // Assumes public getter exists
                Map<DTNHost, List<Duration>> history = bubbleRouter.getConnectionHistory(); // Assumes public getter exists
                double globalPopularity = 0.0; // Default value

                if (centralityAlg != null && history != null) {
                   // Calculate global centrality using the retrieved algorithm and history
                   globalPopularity = centralityAlg.getGlobalCentrality(history);
                } else {
                   // Log a warning if necessary components couldn't be retrieved
                   System.err.println("WARN: Could not get centrality algorithm or history for host " + host.getAddress() + " in GlobalPopularityReport.");
                }

                // Store the calculated popularity, keyed by the host's address
                globalPopularities.put(host.getAddress(), globalPopularity);
            }
        }

        if (bubbleRapNodes == 0) {
            write("No nodes found with BubbleRapRouter.");
        } else {
            write("Queried " + checkedNodes + " nodes. Found " + bubbleRapNodes + " BubbleRap nodes.");
            write("--------------------------------------------------");

            // Write the popularities sorted by node ID (address)
            for (Map.Entry<Integer, Double> entry : globalPopularities.entrySet()) {
                // Output format: NodeID,PopularityValue,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,
                write(entry.getKey() + "," + format(entry.getValue()) + trailingCommas); // Use format() and add trailing commas
            }
        }

        write("--------------------------------------------------");
        super.done(); // Finalize report writing (close file etc.)
    }
}