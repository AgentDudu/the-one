package routing;

import core.*;
import java.util.*;

/**
 * Revised PeopleRankRouterAlt implementation for the ONE simulator.
 * Uses a PeopleRank algorithm where nodes are ranked based on social
 * connections
 * defined by group membership. Messages are forwarded only to nodes with
 * strictly
 * higher PeopleRank or to the destination.
 */
public class PeopleRankRouter extends ActiveRouter {
  /** Current PeopleRank of this node */
  private double myPeR;
  /** Set of social neighbors based on group membership */
  private Set<DTNHost> socialNeighbors;
  /** PeopleRank values of social neighbors */
  private Map<DTNHost, Double> neighborPeRs;
  /** Degrees of social neighbors */
  private Map<DTNHost, Integer> neighborDegrees;
  /** Damping factor for PeopleRank calculation */
  private double d;
  /** Group ID for this router instance */
  private String groupID;
  /** Flag to indicate if social neighbors have been initialized */
  private boolean socialNeighborsSet = false;

  /** Static map to store host-to-groupID mappings, initialized once */
  private static Map<DTNHost, String> hostToGroup = null;

  /**
   * Constructor that initializes the router with settings.
   * 
   * @param s Settings object containing groupID and dampingFactor
   */
  public PeopleRankRouter(Settings s) {
    super(s);
    this.groupID = s.getSetting("groupID");
    this.d = s.getDouble("dampingFactor", 0.8); // Adjusted default to 0.8
  }

  /**
   * Copy constructor.
   * 
   * @param r The router instance to replicate
   */
  public PeopleRankRouter(PeopleRankRouter r) {
    super(r);
    this.groupID = r.groupID;
    this.d = r.d;
  }

  /**
   * Gets the group ID of this router.
   * 
   * @return The group ID
   */
  public String getGroupID() {
    return groupID;
  }

  @Override
  public void init(DTNHost host, List<MessageListener> mListeners) {
    super.init(host, mListeners);
    // Initialization of social graph moved to update() to ensure all hosts are
    // available
  }

  @Override
  public void update() {
    super.update();

    // Initialize the static host-to-group map once
    if (hostToGroup == null) {
      synchronized (PeopleRankRouter.class) {
        if (hostToGroup == null) {
          hostToGroup = new HashMap<>();
          for (DTNHost h : SimScenario.getInstance().getHosts()) {
            PeopleRankRouter router = (PeopleRankRouter) h.getRouter();
            hostToGroup.put(h, router.getGroupID());
          }
        }
      }
    }

    // Set social neighbors for this instance once
    if (!socialNeighborsSet) {
      socialNeighbors = new HashSet<>();
      for (DTNHost h : SimScenario.getInstance().getHosts()) {
        if (hostToGroup.get(h).equals(this.groupID) && h != getHost()) {
          socialNeighbors.add(h);
        }
      }
      socialNeighborsSet = true;
      // Initialize neighbor data structures
      neighborPeRs = new HashMap<>();
      neighborDegrees = new HashMap<>();
      int degree = socialNeighbors.size();
      for (DTNHost neighbor : socialNeighbors) {
        neighborPeRs.put(neighbor, 0.0);
        neighborDegrees.put(neighbor, degree);
      }
      myPeR = 0.0;
    }

    // Skip updates if transferring or unable to start transfer
    if (isTransferring() || !canStartTransfer()) {
      return;
    }

    // Process each connection
    List<Connection> connections = getConnections();
    for (Connection con : connections) {
      DTNHost otherHost = con.getOtherNode(getHost());
      PeopleRankRouter otherRouter = (PeopleRankRouter) otherHost.getRouter();
      double otherPeR = otherRouter.getPeR();

      // Update PeopleRank if the other host is a social neighbor
      if (socialNeighbors.contains(otherHost)) {
        int otherDegree = otherRouter.getSocialDegree();
        neighborPeRs.put(otherHost, otherPeR);
        neighborDegrees.put(otherHost, otherDegree);
        double sum = 0;
        for (DTNHost neighbor : socialNeighbors) {
          double perJ = neighborPeRs.get(neighbor);
          int degreeJ = neighborDegrees.get(neighbor);
          if (degreeJ > 0) {
            sum += perJ / degreeJ;
          }
        }
        myPeR = (1 - d) + d * sum;
      }

      // Select messages to forward with stricter condition
      List<Message> messagesToSend = new ArrayList<>();
      for (Message m : getMessageCollection()) {
        // Forward only if other node's PeopleRank is strictly higher or it's the
        // destination
        if (otherPeR > myPeR || otherHost == m.getTo()) {
          messagesToSend.add(m);
        }
      }
      if (!messagesToSend.isEmpty()) {
        sortByQueueMode(messagesToSend);
        tryAllMessages(con, messagesToSend);
      }
    }
  }

  /**
   * Gets the current PeopleRank of this node.
   * 
   * @return The PeopleRank value
   */
  public double getPeR() {
    return myPeR;
  }

  /**
   * Gets the social degree (number of social neighbors).
   * 
   * @return The size of the socialNeighbors set
   */
  public int getSocialDegree() {
    return socialNeighbors != null ? socialNeighbors.size() : 0;
  }

  @Override
  public MessageRouter replicate() {
    return new PeopleRankRouter(this);
  }
}
