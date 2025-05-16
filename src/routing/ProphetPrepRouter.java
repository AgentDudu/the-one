package routing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;
import core.SimClock;
import routing.util.RoutingInfo;
import util.Tuple;

/**
 * Implementation of PRoPHET PREP router as described in
 * "An Efficient Routing Protocol Using the History of Delivery Predictability
 * in Opportunistic Networks"
 * by Eun Hak Lee, Dong Yeong Seo, and Yun Won Chung.
 *
 * This router extends ActiveRouter and incorporates PRoPHET logic with
 * the PREP (Previous Predictability) enhancement.
 */
public class ProphetPrepRouter extends ActiveRouter {
  // Constants from ProphetRouter
  public static final double P_INIT = 0.75;
  public static final double DEFAULT_BETA = 0.25;
  public static final double DEFAULT_GAMMA = 0.98;

  public static final String PROPHET_NS = "ProphetRouter"; // Keep for settings compatibility
  public static final String SECONDS_IN_UNIT_S = "secondsInTimeUnit";
  public static final String BETA_S = "beta";
  public static final String GAMMA_S = "gamma";

  // PRoPHET specific fields
  private int secondsInTimeUnit;
  private double beta;
  private double gamma;
  private Map<DTNHost, Double> preds; // Delivery predictabilities (P)
  private double lastAgeUpdate;

  // PREP specific field
  /**
   * Stores the delivery predictability the *receiving* node had for a destination
   * at the time it last received a message destined for that destination.
   * Key: Destination Host, Value: Previous Predictability (preP)
   */
  private Map<DTNHost, Double> previousPredictabilities;

  /**
   * Constructor. Creates a new message router based on the settings in
   * the given Settings object.
   *
   * @param s The settings object
   */
  public ProphetPrepRouter(Settings s) {
    super(s);
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

    initPreds(); // Initialize PRoPHET predictabilities
    initPreviousPreds(); // Initialize PREP specific map
  }

  /**
   * Copy constructor.
   *
   * @param r The router prototype where setting values are copied from
   */
  protected ProphetPrepRouter(ProphetPrepRouter r) {
    super(r);
    this.secondsInTimeUnit = r.secondsInTimeUnit;
    this.beta = r.beta;
    this.gamma = r.gamma;

    initPreds(); // Initialize PRoPHET predictabilities for the new instance
    // Deep copy of predictabilities if they were initialized in r
    if (r.preds != null) {
      this.preds = new HashMap<>(r.preds);
    }
    this.lastAgeUpdate = r.lastAgeUpdate;

    initPreviousPreds(); // Initialize PREP specific map for the new instance
    // Deep copy of previous predictabilities
    if (r.previousPredictabilities != null) {
      this.previousPredictabilities = new HashMap<>(r.previousPredictabilities);
    }
  }

  /**
   * Initializes PRoPHET predictability map.
   */
  private void initPreds() {
    this.preds = new HashMap<>();
    this.lastAgeUpdate = 0; // Initialize lastAgeUpdate, important for first aging
  }

  /**
   * Initializes PREP previous predictability map.
   */
  private void initPreviousPreds() {
    this.previousPredictabilities = new HashMap<>();
  }

  // --- PRoPHET Core Logic (copied/adapted from ProphetRouter) ---

  @Override
  public void changedConnection(Connection con) {
    super.changedConnection(con); // Handles ActiveRouter connection changes

    if (con.isUp()) {
      DTNHost otherHost = con.getOtherNode(getHost());
      // Ensure the other router is compatible before P value exchange
      if (otherHost.getRouter() instanceof ProphetPrepRouter) {
        updateDeliveryPredFor(otherHost);
        updateTransitivePreds(otherHost);
      } else {
        // Optionally handle incompatible router types, e.g., by only aging
        ageDeliveryPreds();
      }
    } else {
      // Connection is down, just ensure preds are aged if we check them
      ageDeliveryPreds();
    }
  }

  /**
   * Updates delivery predictions for a host.
   * P(a,b) = P(a,b)_old + (1 - P(a,b)_old) * P_INIT
   *
   * @param host The host we just met
   */
  private void updateDeliveryPredFor(DTNHost host) {
    double oldValue = getPredFor(host);
    double newValue = oldValue + (1 - oldValue) * P_INIT;
    preds.put(host, newValue);
  }

  /**
   * Returns the current prediction (P) value for a host or 0 if entry for
   * the host doesn't exist.
   *
   * @param host The host to look the P for
   * @return the current P value
   */
  public double getPredFor(DTNHost host) {
    ageDeliveryPreds();
    return preds.getOrDefault(host, 0.0);
  }

  /**
   * Updates transitive (A->B->C) delivery predictions.
   * P(a,c) = P(a,c)_old + (1 - P(a,c)_old) * P(a,b) * P(b,c) * BETA
   *
   * @param Bhost The B host who we just met
   */
  private void updateTransitivePreds(DTNHost Bhost) {
    if (!(Bhost.getRouter() instanceof ProphetPrepRouter)) {
      return;
    }
    ProphetPrepRouter otherRouter = (ProphetPrepRouter) Bhost.getRouter();
    double pForB = getPredFor(Bhost);

    Map<DTNHost, Double> othersPreds = otherRouter.getDeliveryPreds();

    for (Map.Entry<DTNHost, Double> entry : othersPreds.entrySet()) {
      DTNHost Chost = entry.getKey();
      if (Chost == getHost()) {
        continue;
      }

      double pForCToB = entry.getValue();
      double pOldAC = getPredFor(Chost);
      double pNewAC = pOldAC + (1 - pOldAC) * pForB * pForCToB * beta;
      preds.put(Chost, pNewAC);
    }
  }

  /**
   * Ages all entries in the delivery predictions.
   * P(a,b) = P(a,b)_old * (GAMMA ^ k)
   */
  private void ageDeliveryPreds() {
    double currentTime = SimClock.getTime();
    double timeDiff = (currentTime - this.lastAgeUpdate) / secondsInTimeUnit;

    if (timeDiff == 0) {
      return;
    }

    double mult = Math.pow(gamma, timeDiff);
    for (Map.Entry<DTNHost, Double> e : preds.entrySet()) {
      e.setValue(e.getValue() * mult);
    }
    this.lastAgeUpdate = currentTime;
  }

  /**
   * Returns a map of this router's delivery predictions (P values).
   * Ensures aging is done before returning.
   *
   * @return A map of delivery predictions.
   */
  private Map<DTNHost, Double> getDeliveryPreds() {
    ageDeliveryPreds();
    return this.preds;
  }

  /**
   * Returns the previous predictability (preP) value for a destination host.
   *
   * @param host The destination host.
   * @return The preP value, or null if not found.
   */
  public Double getPreviousPredFor(DTNHost host) {
    return this.previousPredictabilities.get(host);
  }

  /**
   * Sets the previous predictability (preP) value for a destination host.
   *
   * @param destination The destination host.
   * @param pred        The predictability value to store as preP.
   */
  protected void setPreviousPredFor(DTNHost destination, double pred) {
    this.previousPredictabilities.put(destination, pred);
  }

  /**
   * Overrides ActiveRouter's method. Stores preP upon successful message
   * reception.
   */
  @Override
  public Message messageTransferred(String id, DTNHost from) {
    Message msg = super.messageTransferred(id, from);

    if (msg != null && hasMessage(msg.getId())) {
      DTNHost destination = msg.getTo();
      if (destination != getHost()) {
        double currentPredToDest = getPredFor(destination);
        setPreviousPredFor(destination, currentPredToDest);
      }
    }

    return msg;
  }

  @Override
  public void update() {
    super.update();

    if (!canStartTransfer() || isTransferring()) {
      return;
    }

    if (exchangeDeliverableMessages() != null) {
      return;
    }

    List<Tuple<Message, Connection>> messagesToTry = getMessagesForForwarding();

    if (!messagesToTry.isEmpty()) {
      Collections.sort(messagesToTry, new TupleComparator());
      tryMessagesForConnected(messagesToTry);
    }
  }

  /**
   * Selects messages eligible for forwarding based on PRoPHET PREP logic.
   *
   * @return A list of (Message, Connection) tuples eligible for forwarding.
   */
  private List<Tuple<Message, Connection>> getMessagesForForwarding() {
    List<Tuple<Message, Connection>> eligibleMessages = new ArrayList<>();
    Collection<Message> msgCollection = getMessageCollection();

    if (msgCollection.isEmpty() || getConnections().isEmpty()) {
      return eligibleMessages;
    }

    for (Connection con : getConnections()) {
      DTNHost otherHost = con.getOtherNode(getHost());

      if (!(otherHost.getRouter() instanceof ProphetPrepRouter)) {
        continue;
      }
      ProphetPrepRouter otherRouter = (ProphetPrepRouter) otherHost.getRouter();

      if (isTransferring(otherHost) || otherRouter.isTransferring()) {
        continue;
      }

      for (Message m : msgCollection) {
        if (m.getTo() == otherHost) {
          continue;
        }
        if (otherRouter.hasMessage(m.getId())) {
          continue;
        }

        DTNHost dest = m.getTo();
        double myPredToDest = getPredFor(dest);
        double otherPredToDest = otherRouter.getPredFor(dest);

        if (otherPredToDest > myPredToDest) {
          Double myPrePToDest = getPreviousPredFor(dest);

          if (myPrePToDest == null || otherPredToDest >= myPrePToDest) {
            eligibleMessages.add(new Tuple<>(m, con));
          }
        }
      }
    }
    return eligibleMessages;
  }

  /**
   * Helper to check if a specific connection to another host is busy.
   * (This is a simplified check; ActiveRouter's isTransferring() is more
   * general).
   */
  private boolean isTransferring(DTNHost otherHost) {
    for (Connection con : getConnections()) {
      if (con.getOtherNode(getHost()) == otherHost) {
        return !con.isReadyForTransfer();
      }
    }
    return false;
  }

  /**
   * Comparator for Message-Connection-Tuples. Orders by the P value of the
   * *other* host to the message's destination (GRTRMax).
   * Tie-breaking uses the queue mode from MessageRouter (via ActiveRouter).
   */
  private class TupleComparator implements Comparator<Tuple<Message, Connection>> {
    public int compare(Tuple<Message, Connection> tuple1, Tuple<Message, Connection> tuple2) {
      DTNHost otherNode1 = tuple1.getValue().getOtherNode(getHost());
      DTNHost otherNode2 = tuple2.getValue().getOtherNode(getHost());

      if (!(otherNode1.getRouter() instanceof ProphetPrepRouter) ||
          !(otherNode2.getRouter() instanceof ProphetPrepRouter)) {
        System.err.println("Warning: TupleComparator encountered non-ProphetPrepRouter peer.");
        return ProphetPrepRouter.this.compareByQueueMode(tuple1.getKey(), tuple2.getKey());
      }

      ProphetPrepRouter otherRouter1 = (ProphetPrepRouter) otherNode1.getRouter();
      ProphetPrepRouter otherRouter2 = (ProphetPrepRouter) otherNode2.getRouter();

      double p1 = otherRouter1.getPredFor(tuple1.getKey().getTo());
      double p2 = otherRouter2.getPredFor(tuple2.getKey().getTo());

      if (Double.compare(p1, p2) == 0) {
        return ProphetPrepRouter.this.compareByQueueMode(tuple1.getKey(), tuple2.getKey());
      } else {
        return Double.compare(p2, p1);
      }
    }
  }

  @Override
  public RoutingInfo getRoutingInfo() {
    ageDeliveryPreds();
    RoutingInfo top = super.getRoutingInfo();

    RoutingInfo prophetRi = new RoutingInfo(preds.size() + " PRoPHET delivery prediction(s) (P)");
    List<Map.Entry<DTNHost, Double>> sortedPreds = new ArrayList<>(preds.entrySet());
    sortedPreds.sort(Comparator.comparing(e -> e.getKey().toString()));
    for (Map.Entry<DTNHost, Double> e : sortedPreds) {
      prophetRi.addMoreInfo(new RoutingInfo(String.format("%s : %.6f", e.getKey(), e.getValue())));
    }
    top.addMoreInfo(prophetRi);

    RoutingInfo prepRi = new RoutingInfo(previousPredictabilities.size() + " PREP previous prediction(s) (preP)");
    List<Map.Entry<DTNHost, Double>> sortedPreps = new ArrayList<>(previousPredictabilities.entrySet());
    sortedPreps.sort(Comparator.comparing(e -> e.getKey().toString()));
    for (Map.Entry<DTNHost, Double> e : sortedPreps) {
      prepRi.addMoreInfo(new RoutingInfo(String.format("%s : %.6f", e.getKey(), e.getValue())));
    }
    top.addMoreInfo(prepRi);

    return top;
  }

  @Override
  public MessageRouter replicate() {
    return new ProphetPrepRouter(this);
  }
}
