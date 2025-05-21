package routing;

import core.*;
import routing.util.RoutingInfo;
import util.Tuple;

import java.util.*;

public class ProphetV3Router extends ActiveRouter {

  public static final double P_INIT = 0.75;
  public static final double DEFAULT_BETA = 0.25;
  public static final double DEFAULT_GAMMA = 0.98;

  public static final String PROPHET_NS = "ProphetRouter";
  public static final String SECONDS_IN_UNIT_S = "secondsInTimeUnit";
  public static final String BETA_S = "beta";
  public static final String GAMMA_S = "gamma";

  public static final String PROPHET_V3_NS = "ProphetV3Router";
  public static final String QUEUEING_POLICY_S = "queueingPolicy";
  public static final String FORWARDING_STRATEGY_S = "forwardingStrategy";

  private int secondsInTimeUnit;
  private double beta;
  private double gamma;

  private Map<DTNHost, Double> preds;
  private double lastAgeUpdate;

  public enum QueueingPolicy {
    FIFO, MOFO, MOPR, SHLI, LEPR
  }

  public enum ForwardingStrategy {
    GRTR, GRTRSORT, GRTRMAX, COIN
  }

  private QueueingPolicy queueingPolicy;
  private ForwardingStrategy forwardingStrategy;

  private Map<String, Integer> forwardingCounts; // MsgID -> count (for MOFO)
  private Map<String, Double> forwardingFavorablePoints; // MsgID -> FP (for MOPR)
  private Random randomGenerator; // For COIN strategy

  /**
   * Constructor. Creates a new message router based on the settings in
   * the given Settings object.
   * 
   * @param s The settings object
   */
  public ProphetV3Router(Settings s) {
    super(s);
    // Base Prophet settings
    Settings prophetSettings = new Settings(PROPHET_NS);
    secondsInTimeUnit = prophetSettings.getInt(SECONDS_IN_UNIT_S);
    if (prophetSettings.contains(BETA_S)) {
      beta = prophetSettings.getDouble(BETA_S);
    } else {
      beta = DEFAULT_BETA;
    }
    if (prophetSettings.contains(GAMMA_S)) {
      gamma = prophetSettings.getDouble(GAMMA_S);
    } else {
      gamma = DEFAULT_GAMMA;
    }

    initPreds(); // Initializes preds and lastAgeUpdate

    // ProphetV3 specific settings
    Settings prophetV3Settings = new Settings(PROPHET_V3_NS);
    if (prophetV3Settings.contains(QUEUEING_POLICY_S)) {
      String policyName = prophetV3Settings.getSetting(QUEUEING_POLICY_S).toUpperCase();
      try {
        this.queueingPolicy = QueueingPolicy.valueOf(policyName);
      } catch (IllegalArgumentException e) {
        throw new SettingsError("Invalid queueing policy: " + policyName +
            ". Valid options are: " + Arrays.toString(QueueingPolicy.values()));
      }
    } else {
      this.queueingPolicy = QueueingPolicy.FIFO; // Default
    }

    if (prophetV3Settings.contains(FORWARDING_STRATEGY_S)) {
      String strategyName = prophetV3Settings.getSetting(FORWARDING_STRATEGY_S).toUpperCase();
      try {
        this.forwardingStrategy = ForwardingStrategy.valueOf(strategyName);
      } catch (IllegalArgumentException e) {
        throw new SettingsError("Invalid forwarding strategy: " + strategyName +
            ". Valid options are: " + Arrays.toString(ForwardingStrategy.values()));
      }
    } else {
      this.forwardingStrategy = ForwardingStrategy.GRTRMAX; // Default (original Prophet behavior)
    }

    this.forwardingCounts = new HashMap<>();
    this.forwardingFavorablePoints = new HashMap<>();
    this.randomGenerator = new Random(SimClock.getIntTime()); // Initialize for COIN
  }

  /**
   * Copy constructor.
   * 
   * @param r The router prototype where setting values are copied from
   */
  protected ProphetV3Router(ProphetV3Router r) {
    super(r);
    this.secondsInTimeUnit = r.secondsInTimeUnit;
    this.beta = r.beta;
    this.gamma = r.gamma;
    initPreds(); // Initializes this.preds and this.lastAgeUpdate based on current time

    this.queueingPolicy = r.queueingPolicy;
    this.forwardingStrategy = r.forwardingStrategy;
    this.forwardingCounts = new HashMap<>(); // New node starts with fresh counts for MOFO
    this.forwardingFavorablePoints = new HashMap<>(); // New node starts with fresh FPs for MOPR
    this.randomGenerator = new Random(SimClock.getIntTime()); // Re-initialize for COIN
  }

  private void initPreds() {
    this.preds = new HashMap<>();
    this.lastAgeUpdate = SimClock.getTime(); // Initialize with current time
  }

  @Override
  public void changedConnection(Connection con) {
    super.changedConnection(con);
    if (con.isUp()) {
      DTNHost otherHost = con.getOtherNode(getHost());
      // Ensure the other host is also a ProphetV3Router for pred exchange
      if (otherHost.getRouter() instanceof ProphetV3Router) {
        updateDeliveryPredFor(otherHost);
        updateTransitivePreds(otherHost);
      }
    }
  }

  private void updateDeliveryPredFor(DTNHost host) {
    ageDeliveryPreds(); // Age preds before update
    double oldValue = getPredFor(host);
    double newValue = oldValue + (1 - oldValue) * P_INIT;
    preds.put(host, newValue);
  }

  public double getPredFor(DTNHost host) {
    ageDeliveryPreds(); // Ensure preds are current before returning
    return preds.getOrDefault(host, 0.0);
  }

  private void updateTransitivePreds(DTNHost host) {
    ageDeliveryPreds(); // Age own preds first
    MessageRouter otherRouter = host.getRouter();
    // This check should ideally be done by the caller (changedConnection)
    // but double-checking here is safer.
    if (!(otherRouter instanceof ProphetV3Router)) {
      return;
    }

    double pForHost = getPredFor(host); // P(a,b)
    Map<DTNHost, Double> othersPreds = ((ProphetV3Router) otherRouter).getDeliveryPreds(); // This will also age other's
                                                                                           // preds

    for (Map.Entry<DTNHost, Double> e : othersPreds.entrySet()) {
      DTNHost destHostC = e.getKey();
      if (destHostC == getHost()) {
        continue; // Don't add transitive pred for self
      }

      double pOldAC = getPredFor(destHostC); // P(a,c)_old
      double pBC = e.getValue(); // P(b,c)
      double pNewAC = pOldAC + (1 - pOldAC) * pForHost * pBC * beta;
      preds.put(destHostC, pNewAC);
    }
  }

  private void ageDeliveryPreds() {
    double timeDiff = (SimClock.getTime() - this.lastAgeUpdate) / secondsInTimeUnit;
    if (timeDiff == 0) {
      return;
    }
    double mult = Math.pow(gamma, timeDiff);
    for (Map.Entry<DTNHost, Double> e : preds.entrySet()) {
      e.setValue(e.getValue() * mult);
    }
    this.lastAgeUpdate = SimClock.getTime();
  }

  private Map<DTNHost, Double> getDeliveryPreds() {
    ageDeliveryPreds(); // Make sure the aging is done
    return new HashMap<>(this.preds); // Return a copy to prevent external modification
  }

  @Override
  public void update() {
    super.update(); // Handles active connection management and TTL checks
    if (!canStartTransfer() || isTransferring()) {
      return; // Nothing to transfer or is currently transferring
    }

    // Try messages that could be delivered to final recipient
    if (exchangeDeliverableMessages() != null) { // exchangeDeliverableMessages is from ActiveRouter
      return;
    }

    // Try other messages based on forwarding strategy
    tryOtherMessages();
  }

  // This is a helper method, not overriding from ActiveRouter
  protected Tuple<Message, Connection> tryOtherMessages() {
    List<Tuple<Message, Connection>> messagesToSend = new ArrayList<>();
    Collection<Message> myMessages = getMessageCollection();

    for (Connection con : getConnections()) {
      DTNHost otherNode = con.getOtherNode(getHost());
      if (!(otherNode.getRouter() instanceof ProphetV3Router)) {
        continue; // Only interact with other ProphetV3 routers for P-value exchange
      }
      ProphetV3Router otherRouter = (ProphetV3Router) otherNode.getRouter();

      if (otherRouter.isTransferring()) {
        continue; // Skip hosts that are transferring
      }

      for (Message m : myMessages) {
        if (otherRouter.hasMessage(m.getId()) || m.getTo() == otherNode) {
          continue; // Skip messages that the other one has or if other is destination
        }

        boolean forwardThisMessage = false;
        double p_b_d = otherRouter.getPredFor(m.getTo()); // P(B,D) - other's pred for dest
        double p_a_d = getPredFor(m.getTo()); // P(A,D) - my pred for dest

        switch (this.forwardingStrategy) {
          case GRTR:
          case GRTRSORT: // Sorting handled later
          case GRTRMAX: // Sorting handled later
            if (p_b_d > p_a_d) {
              forwardThisMessage = true;
            }
            break;
          case COIN:
            // As this strategy does not consider the delivery predictabilities
            // in making its decision, it will not be used in a PROPHET system.
            // For PROPHET system, COIN implies Epidemic with a random chance.
            // The paper mentions it's similar to Epidemic but with a coin toss.
            // For PROPHET, this means if P_B_D > P_A_D (standard PROPHET condition for
            // GRTR),
            // then apply coin toss. Or, if it's to compare against simple random pruning
            // of Epidemic, it means forward if coin toss > 0.5, regardless of P-values.
            // The latter seems more in line with the paper's intent for COIN as a
            // benchmark.
            if (this.randomGenerator.nextDouble() > 0.5) {
              forwardThisMessage = true;
            }
            break;
        }
        if (forwardThisMessage) {
          messagesToSend.add(new Tuple<>(m, con));
        }
      }
    }

    if (messagesToSend.isEmpty()) {
      return null;
    }

    // Sort messages based on forwarding strategy
    switch (this.forwardingStrategy) {
      case GRTRSORT:
        messagesToSend.sort((t1, t2) -> {
          Message m1 = t1.getKey();
          Connection c1 = t1.getValue();
          DTNHost o1 = c1.getOtherNode(getHost());
          ProphetV3Router or1 = (ProphetV3Router) o1.getRouter();
          double p_b1_d1 = or1.getPredFor(m1.getTo());
          double p_a1_d1 = getPredFor(m1.getTo());
          double diff1 = p_b1_d1 - p_a1_d1;

          Message m2 = t2.getKey();
          Connection c2 = t2.getValue();
          DTNHost o2 = c2.getOtherNode(getHost());
          ProphetV3Router or2 = (ProphetV3Router) o2.getRouter();
          double p_b2_d2 = or2.getPredFor(m2.getTo());
          double p_a2_d2 = getPredFor(m2.getTo());
          double diff2 = p_b2_d2 - p_a2_d2;

          // Sort by largest difference first (descending)
          int comparison = Double.compare(diff2, diff1);
          return (comparison == 0) ? compareByQueueMode(m1, m2) : comparison;
        });
        break;
      case GRTRMAX:
        messagesToSend.sort((t1, t2) -> {
          Message m1 = t1.getKey();
          Connection c1 = t1.getValue();
          DTNHost o1 = c1.getOtherNode(getHost());
          ProphetV3Router or1 = (ProphetV3Router) o1.getRouter();
          double p_b1_d1 = or1.getPredFor(m1.getTo());

          Message m2 = t2.getKey();
          Connection c2 = t2.getValue();
          DTNHost o2 = c2.getOtherNode(getHost());
          ProphetV3Router or2 = (ProphetV3Router) o2.getRouter();
          double p_b2_d2 = or2.getPredFor(m2.getTo());

          // Sort by P(B,D) descending
          int comparison = Double.compare(p_b2_d2, p_b1_d1);
          return (comparison == 0) ? compareByQueueMode(m1, m2) : comparison;
        });
        break;
      case GRTR: // No specific PROPHET sorting for GRTR, rely on ActiveRouter's queue mode for
                 // ordering
      case COIN: // COIN also relies on general queue mode after filtering
      default:
        // Use general queue mode for ordering the selected messages
        sortByQueueMode(messagesToSend); // This sorts List<Tuple<Message, Connection>> based on Message's receive time
                                         // if FIFO etc.
        break;
    }
    return tryMessagesForConnected(messagesToSend); // tryMessagesForConnected is from ActiveRouter
  }

  @Override
  protected void transferDone(Connection con) {
    Message m = con.getMessage();
    if (m == null) { // Should not happen if transfer was successful
      super.transferDone(con);
      return;
    }

    String msgId = m.getId();
    DTNHost otherHost = con.getOtherNode(getHost());

    // Update for MOFO: increment forwarding count for this message
    this.forwardingCounts.put(msgId, this.forwardingCounts.getOrDefault(msgId, 0) + 1);

    // Update for MOPR: add P-value of receiver for destination to message's FP
    // P is the delivery predictability the receiving node (otherHost) has for the
    // message's destination.
    if (otherHost.getRouter() instanceof ProphetV3Router) {
      double p_receiver_has_for_dest = ((ProphetV3Router) otherHost.getRouter()).getPredFor(m.getTo());
      this.forwardingFavorablePoints.put(msgId,
          this.forwardingFavorablePoints.getOrDefault(msgId, 0.0) + p_receiver_has_for_dest);
    }
    super.transferDone(con); // Important to call super
  }

  @Override
  protected Message getNextMessageToRemove(boolean excludeMsgBeingSent) {
    if (this.getNrofMessages() == 0) {
      return null;
    }

    Collection<Message> messagesInQueue = getMessageCollection();
    List<Message> candidates = new ArrayList<>();
    for (Message m : messagesInQueue) {
      if (excludeMsgBeingSent && isSending(m.getId())) {
        continue;
      }
      candidates.add(m);
    }

    if (candidates.isEmpty()) {
      return null; // All messages are being sent and we're excluding them
    }

    Message messageToDrop = null;

    switch (this.queueingPolicy) {
      case FIFO:
        // Oldest received among candidates is dropped first
        messageToDrop = candidates.stream()
            .min(Comparator.comparingDouble(Message::getReceiveTime))
            .orElse(null);
        break;
      case MOFO:
        // Drop message with max forwarding count; if tie, drop oldest
        messageToDrop = candidates.stream().min(
            Comparator.<Message, Integer>comparing(m -> forwardingCounts.getOrDefault(m.getId(), 0)).reversed() // Max
                                                                                                                // forwards
                                                                                                                // first
                .thenComparing(Message::getReceiveTime) // Then oldest (min receiveTime)
        ).orElse(null);
        break;
      case MOPR:
        // Drop message with max favorable points; if tie, drop oldest
        messageToDrop = candidates.stream().min(
            Comparator.<Message, Double>comparing(m -> forwardingFavorablePoints.getOrDefault(m.getId(), 0.0))
                .reversed() // Max FP first
                .thenComparing(Message::getReceiveTime) // Then oldest
        ).orElse(null);
        break;
      case SHLI:
        // Smallest TTL (shortest life time remaining). Message.getTtl() is remaining
        // TTL.
        // If tie, drop oldest.
        messageToDrop = candidates.stream()
            .min(Comparator.comparingInt(Message::getTtl)
                .thenComparing(Message::getReceiveTime))
            .orElse(null);
        break;
      case LEPR:
        // Lowest P-value this node has for the message's destination
        // If tie, drop oldest.
        messageToDrop = candidates.stream()
            .min(Comparator.comparingDouble((Message m) -> getPredFor(m.getTo()))
                .thenComparing(Message::getReceiveTime))
            .orElse(null);
        break;
      default: // Fallback, should not happen if enum is exhaustive
        messageToDrop = candidates.stream()
            .min(Comparator.comparingDouble(Message::getReceiveTime))
            .orElse(null);
    }
    return messageToDrop;
  }

  @Override
  public void deleteMessage(String msgId, boolean dropped) {
    // Clean up auxiliary data when a message is deleted/dropped
    // to prevent memory leaks and keep data relevant.
    forwardingCounts.remove(msgId);
    forwardingFavorablePoints.remove(msgId);
    super.deleteMessage(msgId, dropped); // Important to call super
  }

  @Override
  public RoutingInfo getRoutingInfo() {
    ageDeliveryPreds(); // Ensure preds are up-to-date before displaying
    RoutingInfo top = super.getRoutingInfo();
    RoutingInfo prophetV3Ri = new RoutingInfo("ProphetV3 specific info");

    // Corrected calls to addMoreInfo
    prophetV3Ri.addMoreInfo(new RoutingInfo("Queueing Policy: " + this.queueingPolicy.name()));
    prophetV3Ri.addMoreInfo(new RoutingInfo("Forwarding Strategy: " + this.forwardingStrategy.name()));

    RoutingInfo predsRi = new RoutingInfo(preds.size() + " delivery prediction(s)");
    List<Map.Entry<DTNHost, Double>> sortedPreds = new ArrayList<>(preds.entrySet());
    // Sort by P-value descending for better readability
    sortedPreds.sort((e1, e2) -> Double.compare(e2.getValue(), e1.getValue()));

    for (Map.Entry<DTNHost, Double> e : sortedPreds) {
      // Corrected call to addMoreInfo
      predsRi.addMoreInfo(new RoutingInfo(String.format("%s : %.6f", e.getKey(), e.getValue())));
    }
    prophetV3Ri.addMoreInfo(predsRi);
    top.addMoreInfo(prophetV3Ri);
    return top;
  }

  @Override
  public MessageRouter replicate() {
    return new ProphetV3Router(this);
  }
}
