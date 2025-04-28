package routing;

import core.*;
import routing.community.Duration;
import java.util.*;

/**
 * Alternative implementation of the Spray and Focus routing protocol for The
 * ONE simulator.
 * In the Spray phase, a fixed number of copies are distributed. In the Focus
 * phase,
 * a message is forwarded to a "friend" node that has a smaller average contact
 * window
 * interval with the destination, indicating more frequent meetings.
 */
public class SprayAndFocusRouterAlt extends ActiveRouter {
  /** Identifier for the initial number of copies setting */
  public static final String NROF_COPIES_S = "nrofCopies";
  /** Message property key for the remaining number of copies */
  public static final String MSG_COUNT_PROPERTY = "SprayAndFocusAlt.copies";
  /** Default number of copies if not specified */
  private static final int DEFAULT_NROF_COPIES = 6;

  /** Initial number of message copies */
  private int initialNrofCopies;
  /** Map to store connection history as durations for each host */
  private Map<DTNHost, List<Duration>> connectionHistory;

  /**
   * Constructor for SprayAndFocusRouterAlt.
   * 
   * @param s Settings object to configure the router
   */
  public SprayAndFocusRouterAlt(Settings s) {
    super(s);
    this.initialNrofCopies = s.contains(NROF_COPIES_S) ? s.getInt(NROF_COPIES_S) : DEFAULT_NROF_COPIES;
    this.connectionHistory = new HashMap<>();
  }

  /**
   * Copy constructor.
   * 
   * @param r The router prototype to copy
   */
  protected SprayAndFocusRouterAlt(SprayAndFocusRouterAlt r) {
    super(r);
    this.initialNrofCopies = r.initialNrofCopies;
    this.connectionHistory = new HashMap<>();
  }

  @Override
  public SprayAndFocusRouterAlt replicate() {
    return new SprayAndFocusRouterAlt(this);
  }

  @Override
  public void update() {
    super.update();
    if (!canStartTransfer() || isTransferring()) {
      return; // Nothing to do if transferring or can't start a new transfer
    }

    // Try to send messages to final destinations first
    if (exchangeDeliverableMessages() != null) {
      return;
    }

    // Then try to spray or focus messages
    tryOtherMessages();
  }

  @Override
  public int receiveMessage(Message m, DTNHost from) {
    // Accept the message and store it if it's not already present
    return super.receiveMessage(m, from);
  }

  @Override
  protected void transferDone(Connection con) {
    Message m = con.getMessage();
    if (m == null) {
      return;
    }

    Integer nrofCopies = (Integer) m.getProperty(MSG_COUNT_PROPERTY);
    if (nrofCopies == null) {
      return; // Message43 wasn't using Spray and Focus
    }

    DTNHost otherHost = con.getOtherNode(getHost());
    if (m.getTo() == otherHost) {
      // Message reached its destination, no further action needed
      return;
    }

    if (nrofCopies > 1) {
      // Spray phase: halve the copies
      nrofCopies = (int) Math.ceil(nrofCopies / 2.0);
      m.updateProperty(MSG_COUNT_PROPERTY, nrofCopies);
    } else {
      // Focus phase: delete the message from this node after forwarding
      deleteMessage(m.getId(), false);
    }
  }

  /**
   * Tries to send messages using the Spray and Focus strategy with
   * friendship-based focus.
   */
  private void tryOtherMessages() {
    List<Message> messages = new ArrayList<>(getMessageCollection());
    for (Message m : messages) {
      Integer nrofCopies = (Integer) m.getProperty(MSG_COUNT_PROPERTY);
      if (nrofCopies == null) {
        continue; // Skip messages not initialized for Spray and Focus
      }

      for (Connection con : getConnections()) {
        DTNHost other = con.getOtherNode(getHost());
        SprayAndFocusRouterAlt otherRouter = getOtherRouter(other);

        if (otherRouter.isTransferring()) {
          continue; // Skip if the other node is busy
        }

        if (m.getTo() == other) {
          // Direct delivery to destination
          startTransfer(m, con);
          continue;
        }

        if (nrofCopies > 1) {
          // Spray phase: send if copies remain
          startTransfer(m, con);
        } else {
          // Focus phase: send if the other node is a better "friend" of the destination
          double myAvgInterval = calculateAverageContactInterval(m.getTo());
          double otherAvgInterval = otherRouter.calculateAverageContactInterval(m.getTo());

          // If the other node has a smaller average interval (more frequent contact),
          // forward
          if (otherAvgInterval >= 0 && (myAvgInterval < 0 || otherAvgInterval < myAvgInterval)) {
            startTransfer(m, con);
          }
        }
      }
    }
  }

  @Override
  public Message messageTransferred(String id, DTNHost from) {
    Message m = super.messageTransferred(id, from);
    if (m == null) {
      return null;
    }
    return m;
  }

  @Override
  public boolean createNewMessage(Message m) {
    if (super.createNewMessage(m)) {
      // Initialize the number of copies for the new message
      m.addProperty(MSG_COUNT_PROPERTY, initialNrofCopies);
      return true;
    }
    return false;
  }

  /**
   * Called when a connection state changes. Updates connection history.
   * 
   * @param con The connection object
   */
  @Override
  public void changedConnection(Connection con) {
    DTNHost otherHost = con.getOtherNode(getHost());
    List<Duration> history = connectionHistory.computeIfAbsent(otherHost, k -> new ArrayList<>());

    if (con.isUp()) {
      // Connection starts: record the start time
      history.add(new Duration(SimClock.getTime(), Double.MAX_VALUE)); // End time set to max until connection ends
    } else {
      // Connection ends: update the most recent duration with the end time
      if (!history.isEmpty()) {
        Duration lastDuration = history.get(history.size() - 1);
        if (lastDuration.end == Double.MAX_VALUE) { // Check if this is an open duration
          lastDuration.end = SimClock.getTime();
        }
      }
    }
  }

  /**
   * Calculates the average contact window interval with a given host based on
   * connection history.
   * 
   * @param host The host to calculate the interval for
   * @return The average interval in time units, or -1 if insufficient data
   */
  private double calculateAverageContactInterval(DTNHost host) {
    List<Duration> history = connectionHistory.getOrDefault(host, Collections.emptyList());
    if (history.size() < 2) {
      return -1; // Need at least two meetings to calculate an interval
    }

    double totalInterval = 0;
    int intervalCount = 0;

    for (int i = 1; i < history.size(); i++) {
      Duration prev = history.get(i - 1);
      Duration curr = history.get(i);

      // Ensure the previous duration has an end time and current has a start time
      if (prev.end != Double.MAX_VALUE && curr.start != Double.MAX_VALUE) {
        double interval = curr.start - prev.end;
        totalInterval += interval;
        intervalCount++;
      }
    }

    return intervalCount > 0 ? totalInterval / intervalCount : -1;
  }

  /**
   * Helper method to get the SprayAndFocusRouterAlt instance of another host.
   * 
   * @param h The host to query
   * @return The SprayAndFocusRouterAlt instance
   */
  private SprayAndFocusRouterAlt getOtherRouter(DTNHost h) {
    MessageRouter router = h.getRouter();
    assert router instanceof SprayAndFocusRouterAlt
        : "SprayAndFocusRouterAlt only works with other SprayAndFocusRouterAlt";
    return (SprayAndFocusRouterAlt) router;
  }
}
