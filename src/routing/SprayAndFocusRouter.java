package routing;

import core.*;
import java.util.*;

/**
 * Implementation of the Spray and Focus routing protocol for The ONE simulator.
 * In the Spray phase, a fixed number of copies are distributed. In the Focus
 * phase,
 * a message is forwarded to a node that has seen the destination more recently.
 */
public class SprayAndFocusRouter extends ActiveRouter {
  /** Identifier for the initial number of copies setting */
  public static final String NROF_COPIES_S = "nrofCopies";
  /** Message property key for the remaining number of copies */
  public static final String MSG_COUNT_PROPERTY = "SprayAndFocus.copies";
  /** Default number of copies if not specified */
  private static final int DEFAULT_NROF_COPIES = 6;

  /** Initial number of message copies */
  private int initialNrofCopies;
  /** Map to track the last encounter time with other hosts */
  private Map<DTNHost, Double> encounterTimes;

  /**
   * Constructor for SprayAndFocusRouter.
   * 
   * @param s Settings object to configure the router
   */
  public SprayAndFocusRouter(Settings s) {
    super(s);
    this.initialNrofCopies = s.contains(NROF_COPIES_S) ? s.getInt(NROF_COPIES_S) : DEFAULT_NROF_COPIES;
    this.encounterTimes = new HashMap<>();
  }

  /**
   * Copy constructor.
   * 
   * @param r The router prototype to copy
   */
  protected SprayAndFocusRouter(SprayAndFocusRouter r) {
    super(r);
    this.initialNrofCopies = r.initialNrofCopies;
    this.encounterTimes = new HashMap<>();
  }

  @Override
  public SprayAndFocusRouter replicate() {
    return new SprayAndFocusRouter(this);
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
      return; // Message wasn't using Spray and Focus
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
   * Tries to send messages using the Spray and Focus strategy.
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
        SprayAndFocusRouter otherRouter = getOtherRouter(other);

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
          // Focus phase: send if the other node has a more recent encounter
          Double myEncounterTime = encounterTimes.get(m.getTo());
          Double otherEncounterTime = otherRouter.encounterTimes.get(m.getTo());

          if (otherEncounterTime != null &&
              (myEncounterTime == null || otherEncounterTime > myEncounterTime)) {
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

    // Update encounter time with the sender
    encounterTimes.put(from, SimClock.getTime());
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
   * Called when a connection is established with another host.
   * Updates encounter times.
   * 
   * @param con The connection object
   */
  @Override
  public void changedConnection(Connection con) {
    if (con.isUp()) {
      DTNHost otherHost = con.getOtherNode(getHost());
      encounterTimes.put(otherHost, SimClock.getTime());
    }
  }

  /**
   * Helper method to get the SprayAndFocusRouter instance of another host.
   * 
   * @param h The host to query
   * @return The SprayAndFocusRouter instance
   */
  private SprayAndFocusRouter getOtherRouter(DTNHost h) {
    MessageRouter router = h.getRouter();
    assert router instanceof SprayAndFocusRouter : "SprayAndFocusRouter only works with other SprayAndFocusRouters";
    return (SprayAndFocusRouter) router;
  }
}
