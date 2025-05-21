package routing;

import core.*;
import routing.util.RoutingInfo;
import util.Tuple;

import java.util.*;

public class ProphetV4Router extends ActiveRouter { // Renamed for new version

  // --- Constants (same as V3) ---
  public static final double P_INIT = 0.75;
  public static final double DEFAULT_BETA = 0.25;
  public static final double DEFAULT_GAMMA = 0.98;

  public static final String PROPHET_NS = "ProphetRouter";
  public static final String SECONDS_IN_UNIT_S = "secondsInTimeUnit";
  public static final String BETA_S = "beta";
  public static final String GAMMA_S = "gamma";

  public static final String PROPHET_V4_NS = "ProphetV4Router"; // New NS for clarity if needed
  public static final String QUEUEING_POLICY_S = "queueingPolicy";
  public static final String FORWARDING_STRATEGY_S = "forwardingStrategy";

  private int secondsInTimeUnit;
  private double beta;
  private double gamma;

  private Map<DTNHost, Double> preds;
  private double lastAgeUpdate; // Timestamp of the last aging operation

  public enum QueueingPolicy {
    FIFO, MOFO, MOPR, SHLI, LEPR
  }

  public enum ForwardingStrategy {
    GRTR, GRTRSort, GRTRMax, COIN
  }

  private QueueingPolicy queueingPolicy;
  private ForwardingStrategy forwardingStrategy;

  private Map<String, Integer> forwardingCounts;
  private Map<String, Double> forwardingFavorablePoints;
  private Random randomGenerator;

  public ProphetV4Router(Settings s) {
    super(s);
    Settings prophetSettings = new Settings(PROPHET_NS); // Use base for core Prophet params
    secondsInTimeUnit = prophetSettings.getInt(SECONDS_IN_UNIT_S);
    beta = prophetSettings.getDouble(BETA_S, DEFAULT_BETA);
    gamma = prophetSettings.getDouble(GAMMA_S, DEFAULT_GAMMA);

    // Use ProphetV4_NS for V4 specific params if any are added later,
    // for now, use V3's definitions as they are policy/strategy related.
    Settings policySettings = new Settings(PROPHET_V4_NS); // <--- CORRECTED HERE

    String policyName = policySettings.getSetting(QUEUEING_POLICY_S, "FIFO").toUpperCase();
    try {
      this.queueingPolicy = QueueingPolicy.valueOf(policyName);
    } catch (IllegalArgumentException e) {
      throw new SettingsError("Invalid queueing policy in " + PROPHET_V4_NS + // <--- And here for error message
          ": " + policyName);
    }

    // For forwarding strategy, the paper has GRTRMax, so default should match that.
    // The error says "GRTRMAX" was found, so settings file likely has that.
    // Your enum has "GRTRMax". Let's assume setting file will use "GRTRMax" or
    // "GRTRSort" etc.
    String strategyNameInSetting = policySettings.getSetting(FORWARDING_STRATEGY_S, "GRTRMax");
    String strategyNameForEnum = strategyNameInSetting.toUpperCase();
    // Special handling if your enum uses mixed case like GRTRMax and setting file
    // might be all caps
    if (strategyNameForEnum.equals("GRTRMAX"))
      strategyNameForEnum = "GRTRMax";
    if (strategyNameForEnum.equals("GRTRSORT"))
      strategyNameForEnum = "GRTRSort";

    try {
      this.forwardingStrategy = ForwardingStrategy.valueOf(strategyNameForEnum);
    } catch (IllegalArgumentException e) {
      throw new SettingsError("Invalid forwarding strategy in " + PROPHET_V4_NS + // <--- And here for error message
          ": " + strategyNameInSetting + ". Processed as: " + strategyNameForEnum);
    }
    initPreds(); // Initializes preds and lastAgeUpdate

    this.forwardingCounts = new HashMap<>();
    this.forwardingFavorablePoints = new HashMap<>();
    this.randomGenerator = new Random(SimClock.getIntTime());
  }

  protected ProphetV4Router(ProphetV4Router r) {
    super(r);
    this.secondsInTimeUnit = r.secondsInTimeUnit;
    this.beta = r.beta;
    this.gamma = r.gamma;
    // initPreds() called by super or needs to be called here.
    // Let's be explicit:
    this.preds = new HashMap<>(r.preds); // Deep copy preds
    this.lastAgeUpdate = r.lastAgeUpdate;

    this.queueingPolicy = r.queueingPolicy;
    this.forwardingStrategy = r.forwardingStrategy;
    // MOFO/MOPR data is instance specific, new maps are correct.
    this.forwardingCounts = new HashMap<>();
    this.forwardingFavorablePoints = new HashMap<>();
    this.randomGenerator = new Random(SimClock.getIntTime()); // New RNG for replica
  }

  private void initPreds() {
    this.preds = new HashMap<>();
    this.lastAgeUpdate = SimClock.getTime(); // Initialize with current time
  }

  /**
   * Optimized: Called once per router update if time has advanced.
   */
  private void ensurePredsAged() {
    double currentTime = SimClock.getTime();
    // Check if aging is needed based on current time vs last AGING time
    if (currentTime > this.lastAgeUpdate) {
      double timeDiffUnits = (currentTime - this.lastAgeUpdate) / secondsInTimeUnit;
      // Check timeDiffUnits too, as very small currentTime - lastAgeUpdate might be <
      // secondsInTimeUnit
      if (timeDiffUnits >= 1.0 || (timeDiffUnits > 0 && preds.size() > 0)) { // Age if at least one unit passed or if
                                                                             // preds exist and any time passed
        double mult = Math.pow(this.gamma, timeDiffUnits);
        for (Map.Entry<DTNHost, Double> e : preds.entrySet()) {
          e.setValue(e.getValue() * mult);
        }
        this.lastAgeUpdate = currentTime;
      } else if (preds.isEmpty() && currentTime > this.lastAgeUpdate) {
        // If no preds, still update lastAgeUpdate to prevent unnecessary checks next
        // time if time is same
        this.lastAgeUpdate = currentTime;
      }
    } else if (this.lastAgeUpdate == 0 && this.preds.isEmpty() && currentTime > 0) {
      // Initial case if lastAgeUpdate was default 0 and first update is > 0
      this.lastAgeUpdate = currentTime;
    }
  }

  @Override
  public void changedConnection(Connection con) {
    super.changedConnection(con);
    if (con.isUp()) {
      DTNHost otherHost = con.getOtherNode(getHost());
      if (otherHost.getRouter() instanceof ProphetV4Router) { // Ensure compatibility
        ensurePredsAged(); // Age before P-value updates
        updateDeliveryPredFor(otherHost);
        updateTransitivePreds(otherHost);
      }
    }
  }

  private void updateDeliveryPredFor(DTNHost host) {
    // ensurePredsAged() should have been called by the caller (changedConnection or
    // update)
    double oldValue = getPredFor(host); // getPredFor will now NOT call age
    double newValue = oldValue + (1 - oldValue) * P_INIT;
    preds.put(host, newValue);
  }

  /**
   * Returns P-value. Assumes ensurePredsAged() has been called appropriately
   * by the context (e.g., once in update() or changedConnection()).
   */
  public double getPredFor(DTNHost host) {
    // DO NOT CALL ageDeliveryPreds() here anymore for optimization
    return preds.getOrDefault(host, 0.0);
  }

  private void updateTransitivePreds(DTNHost hostB) {
    // ensurePredsAged() should have been called by changedConnection
    MessageRouter otherRouterRaw = hostB.getRouter();
    if (!(otherRouterRaw instanceof ProphetV4Router)) {
      return;
    }
    ProphetV4Router otherRouter = (ProphetV4Router) otherRouterRaw;
    otherRouter.ensurePredsAged(); // Ensure other router's preds are also aged before getting them

    double p_ab = getPredFor(hostB);
    Map<DTNHost, Double> othersPreds = otherRouter.getDeliveryPredsMap(); // Get direct map

    for (Map.Entry<DTNHost, Double> entry : othersPreds.entrySet()) {
      DTNHost destHostC = entry.getKey();
      if (destHostC == getHost()) {
        continue;
      }
      double p_bc = entry.getValue();
      double p_ac_old = getPredFor(destHostC);
      double p_ac_new = p_ac_old + (1 - p_ac_old) * p_ab * p_bc * beta;
      preds.put(destHostC, p_ac_new);
    }
  }

  /**
   * Returns the internal predictions map. Caller should ensure it's aged.
   * Used by updateTransitivePreds and getRoutingInfo.
   */
  private Map<DTNHost, Double> getDeliveryPredsMap() {
    // DO NOT CALL ageDeliveryPreds() here. Assume aged by context.
    return this.preds; // Return direct reference for internal use, copy for external if needed by API
  }

  @Override
  public void update() {
    super.update();
    ensurePredsAged(); // OPTIMIZATION: Age preds once per update cycle

    if (!canStartTransfer() || isTransferring()) {
      return;
    }
    if (exchangeDeliverableMessages() != null) {
      return;
    }
    tryOtherMessages();
  }

  // tryOtherMessages remains largely the same, but calls to getPredFor()
  // will now be faster as they don't trigger aging.
  protected Tuple<Message, Connection> tryOtherMessages() {
    List<Tuple<Message, Connection>> messagesToSend = new ArrayList<>();
    Collection<Message> myMessages = getMessageCollection();

    for (Connection con : getConnections()) {
      DTNHost otherNode = con.getOtherNode(getHost());
      if (!(otherNode.getRouter() instanceof ProphetV4Router)) {
        continue;
      }
      ProphetV4Router otherRouter = (ProphetV4Router) otherNode.getRouter();
      otherRouter.ensurePredsAged(); // Ensure other router's preds are aged for this interaction

      if (otherRouter.isTransferring()) {
        continue;
      }

      for (Message m : myMessages) {
        if (otherRouter.hasMessage(m.getId()) || m.getTo() == otherNode) {
          continue;
        }

        boolean forwardThisMessage = false;
        // These getPredFor calls are now optimized (don't re-age)
        double p_b_d = otherRouter.getPredFor(m.getTo());
        double p_a_d = getPredFor(m.getTo());

        switch (this.forwardingStrategy) {
          case GRTR:
          case GRTRSort:
          case GRTRMax:
            if (p_b_d > p_a_d) {
              forwardThisMessage = true;
            }
            break;
          case COIN:
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

    // Sorting logic (same as V3)
    switch (this.forwardingStrategy) {
      case GRTRSort:
        messagesToSend.sort((t1, t2) -> {
          Message m1 = t1.getKey();
          DTNHost o1 = t1.getValue().getOtherNode(getHost());
          ProphetV4Router or1 = (ProphetV4Router) o1.getRouter(); // Safe cast due to earlier check
          double p_b1_d1 = or1.getPredFor(m1.getTo());
          double p_a1_d1 = getPredFor(m1.getTo());
          double diff1 = p_b1_d1 - p_a1_d1;

          Message m2 = t2.getKey();
          DTNHost o2 = t2.getValue().getOtherNode(getHost());
          ProphetV4Router or2 = (ProphetV4Router) o2.getRouter();
          double p_b2_d2 = or2.getPredFor(m2.getTo());
          double p_a2_d2 = getPredFor(m2.getTo());
          double diff2 = p_b2_d2 - p_a2_d2;

          int comparison = Double.compare(diff2, diff1);
          return (comparison == 0) ? compareByQueueMode(m1, m2) : comparison;
        });
        break;
      case GRTRMax:
        messagesToSend.sort((t1, t2) -> {
          Message m1 = t1.getKey();
          DTNHost o1 = t1.getValue().getOtherNode(getHost());
          ProphetV4Router or1 = (ProphetV4Router) o1.getRouter();
          double p_b1_d1 = or1.getPredFor(m1.getTo());

          Message m2 = t2.getKey();
          DTNHost o2 = t2.getValue().getOtherNode(getHost());
          ProphetV4Router or2 = (ProphetV4Router) o2.getRouter();
          double p_b2_d2 = or2.getPredFor(m2.getTo());

          int comparison = Double.compare(p_b2_d2, p_b1_d1);
          return (comparison == 0) ? compareByQueueMode(m1, m2) : comparison;
        });
        break;
      default: // GRTR, COIN
        sortByQueueMode(messagesToSend);
        break;
    }
    return tryMessagesForConnected(messagesToSend);
  }

  // transferDone remains the same as V3
  @Override
  protected void transferDone(Connection con) {
    Message m = con.getMessage();
    if (m == null) {
      super.transferDone(con);
      return;
    }
    String msgId = m.getId();
    DTNHost otherHost = con.getOtherNode(getHost());

    this.forwardingCounts.put(msgId, this.forwardingCounts.getOrDefault(msgId, 0) + 1);

    if (otherHost.getRouter() instanceof ProphetV4Router) {
      // Ensure other's preds are aged before getting P-value for MOPR update
      ((ProphetV4Router) otherHost.getRouter()).ensurePredsAged();
      double p_receiver_has_for_dest = ((ProphetV4Router) otherHost.getRouter()).getPredFor(m.getTo());
      this.forwardingFavorablePoints.put(msgId,
          this.forwardingFavorablePoints.getOrDefault(msgId, 0.0) + p_receiver_has_for_dest);
    }
    super.transferDone(con);
  }

  // getNextMessageToRemove remains the same as V3 (uses getPredFor, which is now
  // faster)
  @Override
  protected Message getNextMessageToRemove(boolean excludeMsgBeingSent) {
    if (this.getNrofMessages() == 0)
      return null;
    Collection<Message> messagesInQueue = getMessageCollection();
    List<Message> candidates = new ArrayList<>();
    for (Message m : messagesInQueue) {
      if (excludeMsgBeingSent && isSending(m.getId()))
        continue;
      candidates.add(m);
    }
    if (candidates.isEmpty())
      return null;

    // ensurePredsAged() should have been called in update() if this is part of
    // buffer management
    // during an update cycle. If called outside (e.g. direct receive), aging might
    // be needed.
    // For LEPR, current P-values are needed.
    // It's safer to ensure preds are aged before LEPR if this can be called outside
    // main update loop.
    // However, getNextMessageToRemove is typically called from makeRoomForMessage,
    // which is
    // part of receiveMessage or createNewMessage, which are within an update cycle
    // or event.
    // The ensurePredsAged() in update() should cover most cases.

    Message messageToDrop = null;
    switch (this.queueingPolicy) {
      case FIFO:
        messageToDrop = candidates.stream().min(Comparator.comparingDouble(Message::getReceiveTime)).orElse(null);
        break;
      case MOFO:
        messageToDrop = candidates.stream().min(
            Comparator.<Message, Integer>comparing(m -> forwardingCounts.getOrDefault(m.getId(), 0)).reversed()
                .thenComparing(Message::getReceiveTime))
            .orElse(null);
        break;
      case MOPR:
        messageToDrop = candidates.stream().min(
            Comparator.<Message, Double>comparing(m -> forwardingFavorablePoints.getOrDefault(m.getId(), 0.0))
                .reversed()
                .thenComparing(Message::getReceiveTime))
            .orElse(null);
        break;
      case SHLI:
        messageToDrop = candidates.stream()
            .min(Comparator.comparingInt(Message::getTtl).thenComparing(Message::getReceiveTime))
            .orElse(null);
        break;
      case LEPR:
        // LEPR needs current P-values. The global ensurePredsAged() in update() helps.
        messageToDrop = candidates.stream()
            .min(Comparator.comparingDouble((Message m) -> getPredFor(m.getTo())) // getPredFor is now faster
                .thenComparing(Message::getReceiveTime))
            .orElse(null);
        break;
      default:
        messageToDrop = candidates.stream().min(Comparator.comparingDouble(Message::getReceiveTime)).orElse(null);
    }
    return messageToDrop;
  }

  // deleteMessage remains the same as V3
  @Override
  public void deleteMessage(String msgId, boolean dropped) {
    forwardingCounts.remove(msgId);
    forwardingFavorablePoints.remove(msgId);
    super.deleteMessage(msgId, dropped);
  }

  @Override
  public RoutingInfo getRoutingInfo() {
    ensurePredsAged(); // Ensure preds are aged before displaying
    RoutingInfo top = super.getRoutingInfo();
    RoutingInfo prophetV4Ri = new RoutingInfo("ProphetV4 specific info");
    prophetV4Ri.addMoreInfo(new RoutingInfo("Queueing Policy: " + this.queueingPolicy.name()));
    prophetV4Ri.addMoreInfo(new RoutingInfo("Forwarding Strategy: " + this.forwardingStrategy.name()));

    Map<DTNHost, Double> currentPreds = getDeliveryPredsMap(); // Get direct map (already aged)
    RoutingInfo predsRi = new RoutingInfo(currentPreds.size() + " delivery prediction(s)");
    List<Map.Entry<DTNHost, Double>> sortedPreds = new ArrayList<>(currentPreds.entrySet());
    sortedPreds.sort((e1, e2) -> Double.compare(e2.getValue(), e1.getValue()));

    for (Map.Entry<DTNHost, Double> e : sortedPreds) {
      predsRi.addMoreInfo(new RoutingInfo(String.format("%s : %.6f", e.getKey(), e.getValue())));
    }
    prophetV4Ri.addMoreInfo(predsRi);
    top.addMoreInfo(prophetV4Ri);
    return top;
  }

  @Override
  public MessageRouter replicate() {
    return new ProphetV4Router(this); // Return new type
  }
}
