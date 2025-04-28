/*
 * Copyright 2010 Aalto University, ComNet
 * Copyright 2010 University of Pittsburgh (Bubble Rap logic)
 * Released under GPLv3. See LICENSE.txt for details.
 */
package routing;

import core.*;
import routing.community.*;
import routing.util.RoutingInfo;

import java.util.*;

/**
 * Implementation of the Bubble Rap routing algorithm.
 * Bubble Rap utilizes social network concepts (community detection and
 * centrality)
 * to make forwarding decisions.
 *
 * @author PJ Dillon (University of Pittsburgh, original Bubble Rap logic)
 */
public class BubbleRapRouter extends ActiveRouter implements CommunityDetectionEngine {

  // --- Settings Keys ---
  /** Community detection algorithm class -setting id ({@value}). */
  public static final String COMMUNITY_ALG_SETTING = "communityAlg";
  /** Centrality algorithm class -setting id ({@value}). */
  public static final String CENTRALITY_ALG_SETTING = "centralityAlg";

  // --- Default Algorithm Classes ---
  private static final String DEFAULT_COMMUNITY_ALG = "routing.community.KCliqueCommunityDetection";
  private static final String DEFAULT_CENTRALITY_ALG = "routing.community.SWindowCentrality";

  // --- Member Variables ---
  /** The community detection algorithm instance. */
  protected CommunityDetection communityDetection;
  /** The centrality calculation algorithm instance. */
  protected Centrality centrality;

  /** Stores the connection history: Host -> List of connection durations. */
  protected Map<DTNHost, List<Duration>> connectionHistory;
  /** Stores the start time of current connections. */
  protected Map<Connection, Double> connectionStartTimes;

  /**
   * Constructor. Creates a new message router based on the settings in
   * the given Settings object.
   * 
   * @param s The settings object
   */
  public BubbleRapRouter(Settings s) {
    super(s);
    Settings communitySettings = new Settings("CommunityDetection");
    Settings centralitySettings = new Settings("Centrality");

    // Load configured or default community detection algorithm
    String communityAlgClass = s.getSetting(COMMUNITY_ALG_SETTING, DEFAULT_COMMUNITY_ALG);
    this.communityDetection = (CommunityDetection) communitySettings.createIntializedObject(communityAlgClass);

    // Load configured or default centrality algorithm
    String centralityAlgClass = s.getSetting(CENTRALITY_ALG_SETTING, DEFAULT_CENTRALITY_ALG);
    this.centrality = (Centrality) centralitySettings.createIntializedObject(centralityAlgClass);

  }

  /**
   * Copy constructor.
   * 
   * @param r The router prototype where setting values are copied from
   */
  protected BubbleRapRouter(BubbleRapRouter r) {
    super(r);
    // Replicate community and centrality algorithms
    this.communityDetection = r.communityDetection.replicate();
    this.centrality = r.centrality.replicate();
    // Initialize local state
    this.connectionHistory = new HashMap<>();
    this.connectionStartTimes = new HashMap<>();
  }

  @Override
  public void init(DTNHost host, List<MessageListener> mListeners) {
    super.init(host, mListeners);
    // Initialize local state (needed after replication)
    if (this.connectionHistory == null) {
      this.connectionHistory = new HashMap<>();
    }
    if (this.connectionStartTimes == null) {
      this.connectionStartTimes = new HashMap<>();
    }
    // Ensure the host is part of its own community initially if the algorithm
    // requires it
    // (This might be handled within the specific community detection's
    // newConnection/init logic)
  }

  /**
   * Called when a connection's state changes. Updates connection history and
   * informs the community detection algorithm.
   * 
   * @param con The connection whose state changed
   */
  @Override
  public void changedConnection(Connection con) {
    super.changedConnection(con); // Handle energy reduction etc.

    DTNHost myHost = getHost();
    DTNHost peer = con.getOtherNode(myHost);

    // Ensure peer has a router and it supports community detection (ideally
    // BubbleRapRouter)
    MessageRouter peerRouter = peer.getRouter();
    CommunityDetection peerCommunityDetection = null;

    if (peerRouter instanceof BubbleRapRouter) {
      peerCommunityDetection = ((BubbleRapRouter) peerRouter).communityDetection;
    } else {
      // Peer uses a different router or doesn't support community detection.
      // Depending on the community algorithm, this might be okay or might cause
      // issues.
      // For K-Clique/SIMPLE, we need the peer's state. Let's log a warning for now.
      if (con.isUp()) {
        System.err
            .println("Warning: Peer " + peer + " does not use BubbleRapRouter. Community detection might be impaired.");
      }
      // Cannot proceed with community updates requiring peer's state.
      // Handle connection history only.
      if (con.isUp()) {
        connectionStartTimes.put(con, SimClock.getTime());
      } else {
        recordConnectionDuration(con, peer);
      }
      return; // Exit early if peer doesn't have compatible state
    }

    if (con.isUp()) {
      // Connection established
      connectionStartTimes.put(con, SimClock.getTime());
      if (this.communityDetection != null && peerCommunityDetection != null) {
        this.communityDetection.newConnection(myHost, peer, peerCommunityDetection);
      }
    } else {
      // Connection lost
      List<Duration> history = recordConnectionDuration(con, peer);
      if (this.communityDetection != null && peerCommunityDetection != null && history != null) {
        this.communityDetection.connectionLost(myHost, peer, peerCommunityDetection, history);
      }
    }
  }

  /**
   * Records the duration of a finished connection and updates the history.
   * 
   * @param con  The connection that ended.
   * @param peer The peer host of the connection.
   * @return The full connection history list for the peer, or null if no start
   *         time was found.
   */
  private List<Duration> recordConnectionDuration(Connection con, DTNHost peer) {
    Double startTime = connectionStartTimes.remove(con);
    if (startTime != null) {
      double endTime = SimClock.getTime();
      Duration duration = new Duration(startTime, endTime);

      List<Duration> history = connectionHistory.computeIfAbsent(peer, k -> new ArrayList<>());
      history.add(duration);
      return history; // Return the updated history
    } else {
      System.err.println("Warning: Connection end event for " + peer + " without start time.");
      return connectionHistory.get(peer); // Return existing history if any
    }
  }

  @Override
  public void update() {
    super.update(); // Handle TTL checks, energy, ongoing transfers

    if (isTransferring() || !canStartTransfer()) {
      return; // Router is busy or has nothing to send/no connections
    }

    // Try to deliver messages directly to final recipients first
    if (tryMessagesForConnected(getMessagesForConnected()) != null) {
      return; // Transfer started
    }

    // --- Bubble Rap Forwarding Logic ---

    // Get available messages, potentially sorted
    List<Message> messages = new ArrayList<>(getMessageCollection());
    if (messages.isEmpty()) {
      return; // No messages to forward
    }
    sortByQueueMode(messages); // Apply sorting (e.g., FIFO)

    // Calculate own centralities (memoized by Centrality classes)
    double myGlobalC = getMyCentrality(true);
    double myLocalC = getMyCentrality(false);

    // Iterate through connections and try to forward based on Bubble Rap rules
    for (Connection con : getConnections()) {
      DTNHost peer = con.getOtherNode(getHost());
      BubbleRapRouter peerRouter = getPeerRouter(con);

      if (peerRouter == null) {
        // Log or handle cases where peer isn't BubbleRap - cannot compare
        // centrality/community
        continue;
      }

      // Calculate peer centralities (accessing directly - simulation shortcut)
      double peerGlobalC = peerRouter.getMyCentrality(true);
      double peerLocalC = peerRouter.getMyCentrality(false);

      boolean peerInMyCommunity = this.communityDetection.isHostInCommunity(peer);

      for (Message m : messages) {
        DTNHost destination = m.getTo();
        boolean destInMyCommunity = this.communityDetection.isHostInCommunity(destination);
        boolean destInPeerCommunity = peerRouter.communityDetection.isHostInCommunity(destination);

        boolean shouldForward = false;

        // Rule 1: Destination is the peer itself (handled by tryMessagesForConnected
        // earlier, but double-check)
        if (destination == peer) {
          shouldForward = true;
        }
        // Rule 2: Destination is in my community
        else if (destInMyCommunity) {
          // Forward if peer is also in my community AND has higher *local* centrality
          if (peerInMyCommunity && peerLocalC > myLocalC) {
            shouldForward = true;
          }
          // Optimization: Forward if peer is the destination's community member (even if
          // lower local C)
          // This case is less standard BubbleRap, more direct-to-community-member, but
          // can be useful.
          // Let's stick closer to standard Bubble: only forward based on local centrality
          // within community.

        }
        // Rule 3: Destination is NOT in my community
        else {
          // Forward if the peer is in the destination's community
          if (destInPeerCommunity) {
            shouldForward = true;
          }
          // Forward if the peer has higher *global* centrality (and isn't in dest
          // community yet)
          else if (peerGlobalC > myGlobalC) {
            shouldForward = true;
          }
        }

        // --- Attempt Transfer ---
        if (shouldForward) {
          int result = startTransfer(m, con);
          if (result == RCV_OK) {
            return; // Transfer started, stop trying for this update cycle
          } else if (result == DENIED_OLD && deleteDelivered) {
            // Handled in startTransfer, but noted here
          } else if (result == TRY_LATER_BUSY) {
            // Connection/peer busy, might try next connection or wait
            break; // Stop trying messages for this busy connection
          }
          // Other denials (policy, space, etc.) - try next message/connection
        }
      } // End for each message
    } // End for each connection
  }

  /**
   * Safely retrieves the BubbleRapRouter instance from the peer of a connection.
   * 
   * @param con The connection to the peer.
   * @return The BubbleRapRouter instance of the peer, or null if the peer doesn't
   *         use BubbleRapRouter.
   */
  private BubbleRapRouter getPeerRouter(Connection con) {
    MessageRouter router = con.getOtherNode(getHost()).getRouter();
    if (router instanceof BubbleRapRouter) {
      return (BubbleRapRouter) router;
    }
    return null;
  }

  /**
   * Calculates the centrality for the current host.
   * 
   * @param global If true, calculates global centrality; otherwise, local.
   * @return The calculated centrality value.
   */
  private double getMyCentrality(boolean global) {
    if (this.centrality == null)
      return 0.0; // Should not happen if initialized

    if (global) {
      return this.centrality.getGlobalCentrality(this.connectionHistory);
    } else {
      // Ensure community detection is available for local centrality
      if (this.communityDetection == null)
        return 0.0;
      return this.centrality.getLocalCentrality(this.connectionHistory, this.communityDetection);
    }
  }

  @Override
  public RoutingInfo getRoutingInfo() {
    RoutingInfo top = super.getRoutingInfo();
    top.addMoreInfo(new RoutingInfo("Community Detection Alg: " + communityDetection.getClass().getSimpleName()));
    top.addMoreInfo(new RoutingInfo("Centrality Alg: " + centrality.getClass().getSimpleName()));
    top.addMoreInfo(new RoutingInfo(String.format("Centrality (Local/Global): %.2f / %.2f",
        getMyCentrality(false), getMyCentrality(true))));
    if (communityDetection != null) {
      Set<DTNHost> localComm = communityDetection.getLocalCommunity();
      top.addMoreInfo(new RoutingInfo("Local community size: " + (localComm != null ? localComm.size() : "N/A")));
    }
    // Optional: Add familiar set size if using SimpleCommunityDetection
    // if (communityDetection instanceof SimpleCommunityDetection) {
    // Set<DTNHost> familiarSet =
    // ((SimpleCommunityDetection)communityDetection).getFamiliarSet();
    // top.addMoreInfo(new RoutingInfo("Familiar set size: " + (familiarSet != null
    // ? familiarSet.size() : "N/A")));
    // }
    return top;
  }

  @Override
  public MessageRouter replicate() {
    return new BubbleRapRouter(this);
  }

  // --- Getters for Report Access ---

  /**
   * Returns the Centrality algorithm instance used by this router.
   * @return The Centrality instance.
   */
  public Centrality getCentrality() {
      return this.centrality;
  }

  /**
   * Returns the connection history map maintained by this router.
   * @return A map where keys are peer hosts and values are lists of connection durations.
   */
  public Map<DTNHost, List<Duration>> getConnectionHistory() {
      return this.connectionHistory;
  }

  // --- Implementation of CommunityDetectionEngine ---

  /**
   * Returns the set of hosts belonging to the local community, as determined
   * by the configured community detection algorithm.
   * 
   * @return A Set of DTNHosts in the local community, or null if detection is not
   *         available.
   */
  @Override
  public Set<DTNHost> getLocalCommunity() {
    if (this.communityDetection != null) {
      return this.communityDetection.getLocalCommunity();
    }
    return null; // Or return an empty set: new HashSet<>();
  }
}
