package routing;

import core.*;
import java.util.*;

/**
 * Alternative implementation of Spray and Wait routing protocol using the
 * RoutingDecisionEngine interface. This version supports both binary and
 * standard
 * modes as in the original SprayAndWaitRouter.
 */
public class SprayAndWaitRouterAlt implements RoutingDecisionEngine {
  /** Identifier for the initial number of copies setting */
  public static final String NROF_COPIES_S = "nrofCopies";
  /** Identifier for the binary mode setting */
  public static final String BINARY_MODE_S = "binaryMode";
  /** Message property key for the remaining number of copies */
  public static final String MSG_COUNT_PROPERTY = "SprayAndWaitAlt.copies";
  /** Default number of copies if not specified */
  private static final int DEFAULT_NROF_COPIES = 6;

  /** Initial number of message copies */
  private int initialNrofCopies;
  /** Flag to indicate binary mode (true) or standard mode (false) */
  private boolean isBinary;

  /**
   * Constructor for SprayAndWaitRouterAlt.
   * 
   * @param s Settings object to configure the router
   */
  public SprayAndWaitRouterAlt(Settings s) {
    this.initialNrofCopies = s.contains(NROF_COPIES_S) ? s.getInt(NROF_COPIES_S) : DEFAULT_NROF_COPIES;
    this.isBinary = s.contains(BINARY_MODE_S) ? s.getBoolean(BINARY_MODE_S) : true; // Default to binary mode
  }

  /**
   * Copy constructor.
   * 
   * @param other The SprayAndWaitRouterAlt instance to copy
   */
  public SprayAndWaitRouterAlt(SprayAndWaitRouterAlt other) {
    this.initialNrofCopies = other.initialNrofCopies;
    this.isBinary = other.isBinary;
  }

  @Override
  public RoutingDecisionEngine replicate() {
    return new SprayAndWaitRouterAlt(this);
  }

  @Override
  public void connectionUp(DTNHost thisHost, DTNHost peer) {
    // No specific action needed when a connection comes up
  }

  @Override
  public void connectionDown(DTNHost thisHost, DTNHost peer) {
    // No specific action needed when a connection goes down
  }

  @Override
  public void doExchangeForNewConnection(Connection con, DTNHost peer) {
    // Spray and Wait does not require information exchange between nodes
  }

  @Override
  public boolean newMessage(Message m) {
    // Initialize the message with the initial number of copies
    m.addProperty(MSG_COUNT_PROPERTY, initialNrofCopies);
    return true; // Always accept new messages for forwarding
  }

  @Override
  public boolean isFinalDest(Message m, DTNHost aHost) {
    // Check if this host is the final destination
    Integer nrofCopies = (Integer) m.getProperty(MSG_COUNT_PROPERTY);
    if (nrofCopies == null) {
      return m.getTo() == aHost; // Fallback if property is missing
    }

    // Update copies before delivery to destination
    if (isBinary) {
      nrofCopies = (int) Math.ceil(nrofCopies / 2.0); // Sender keeps ceil(n/2) in binary mode
    } else {
      nrofCopies = 1; // Standard mode: single copy
    }
    m.updateProperty(MSG_COUNT_PROPERTY, nrofCopies);
    return m.getTo() == aHost;
  }

  @Override
  public boolean shouldSaveReceivedMessage(Message m, DTNHost thisHost) {
    // Save the message if this host is not the final destination
    Integer nrofCopies = (Integer) m.getProperty(MSG_COUNT_PROPERTY);
    if (nrofCopies == null || m.getTo() == thisHost) {
      return false;
    }
    return nrofCopies > 0; // Save if there are copies to distribute
  }

  @Override
  public boolean shouldSendMessageToHost(Message m, DTNHost otherHost) {
    // Send if the other host is the destination or if copies remain
    Integer nrofCopies = (Integer) m.getProperty(MSG_COUNT_PROPERTY);
    if (nrofCopies == null) {
      return false; // Not a Spray and Wait message
    }

    if (m.getTo() == otherHost) {
      return true; // Always send to the final destination
    }

    return nrofCopies > 1; // Send only if there are copies to spray
  }

  @Override
  public boolean shouldDeleteSentMessage(Message m, DTNHost otherHost) {
    // Decide whether to delete the message after sending
    Integer nrofCopies = (Integer) m.getProperty(MSG_COUNT_PROPERTY);
    if (nrofCopies == null) {
      return false; // Not a Spray and Wait message
    }

    if (m.getTo() == otherHost) {
      return true; // Delete if sent to the final destination
    }

    if (isBinary) {
      nrofCopies = (int) Math.floor(nrofCopies / 2.0); // Receiver gets floor(n/2) in binary mode
    } else {
      nrofCopies--; // Standard mode: reduce by 1
    }

    m.updateProperty(MSG_COUNT_PROPERTY, nrofCopies);
    return nrofCopies <= 0; // Delete if no copies remain
  }

  @Override
  public boolean shouldDeleteOldMessage(Message m, DTNHost hostReportingOld) {
    // Delete if the reporting host is the destination (message delivered)
    return m.getTo() == hostReportingOld;
  }
}
