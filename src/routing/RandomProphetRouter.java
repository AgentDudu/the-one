package routing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import routing.util.RoutingInfo;
import util.Tuple;
import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;
import core.SimClock;

public class RandomProphetRouter extends ActiveRouter {

  public static final double P_INIT = 0.75;
  public static final double DEFAULT_BETA = 0.25;
  public static final double DEFAULT_GAMMA = 0.98;

  public static final String PROPHET_NS = "ProphetRouter";
  public static final String SECONDS_IN_UNIT_S = "secondsInTimeUnit";
  public static final String BETA_S = "beta";
  public static final String GAMMA_S = "gamma";

  private int secondsInTimeUnit;
  private double beta;
  private double gamma;

  private Map<DTNHost, Double> preds;
  private double lastAgeUpdate;

  public RandomProphetRouter(Settings s) {
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

    initPreds();
  }

  protected RandomProphetRouter(RandomProphetRouter r) {
    super(r);
    this.secondsInTimeUnit = r.secondsInTimeUnit;
    this.beta = r.beta;
    this.gamma = r.gamma;
    initPreds();
  }

  private void initPreds() {
    this.preds = new HashMap<DTNHost, Double>();
  }

  @Override
  public void changedConnection(Connection con) {
    super.changedConnection(con);

    if (con.isUp()) {
      DTNHost otherHost = con.getOtherNode(getHost());
      updateDeliveryPredFor(otherHost);
      updateTransitivePreds(otherHost);
    }
  }

  private void updateDeliveryPredFor(DTNHost host) {
    double oldValue = getPredFor(host);
    double newValue = oldValue + (1 - oldValue) * P_INIT;
    preds.put(host, newValue);
  }

  public double getPredFor(DTNHost host) {
    ageDeliveryPreds();
    if (preds.containsKey(host)) {
      return preds.get(host);
    } else {
      return 0;
    }
  }

  private void updateTransitivePreds(DTNHost host) {
    MessageRouter otherRouter = host.getRouter();

    if (otherRouter instanceof RandomProphetRouter) {
      double pForHost = getPredFor(host);
      Map<DTNHost, Double> othersPreds = ((RandomProphetRouter) otherRouter).getDeliveryPreds();

      for (Map.Entry<DTNHost, Double> e : othersPreds.entrySet()) {
        if (e.getKey() == getHost()) {
          continue;
        }

        double pOld = getPredFor(e.getKey());
        double pNew = pOld + (1 - pOld) * pForHost * e.getValue() * beta;
        preds.put(e.getKey(), pNew);
      }
    }
  }

  private void ageDeliveryPreds() {
    double timeDiff = (SimClock.getTime() - this.lastAgeUpdate) /
        secondsInTimeUnit;

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
    ageDeliveryPreds();
    return this.preds;
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

    tryOtherMessages();
  }

  private Tuple<Message, Connection> tryOtherMessages() {
    List<Tuple<Message, Connection>> messages = new ArrayList<Tuple<Message, Connection>>();
    Collection<Message> msgCollection = getMessageCollection();

    for (Connection con : getConnections()) {
      DTNHost other = con.getOtherNode(getHost());
      RandomProphetRouter othRouter = (RandomProphetRouter) other.getRouter();

      if (othRouter.isTransferring()) {
        continue;
      }

      for (Message m : msgCollection) {
        if (othRouter.hasMessage(m.getId())) {
          continue;
        }
        // Random selection messages to send
        Random random = new Random();
        if (random.nextBoolean()) {
          messages.add(new Tuple<Message, Connection>(m, con));
        }
      }
    }

    if (messages.size() == 0) {
      return null;
    }
    // Randomly shuffle the messages to send
    Collections.shuffle(messages);
    return tryMessagesForConnected(messages);
  }

  @Override
  public RoutingInfo getRoutingInfo() {
    ageDeliveryPreds();
    RoutingInfo top = super.getRoutingInfo();
    RoutingInfo ri = new RoutingInfo(preds.size() +
        " delivery prediction(s) (not used for routing)");

    for (Map.Entry<DTNHost, Double> e : preds.entrySet()) {
      DTNHost host = e.getKey();
      Double value = e.getValue();

      ri.addMoreInfo(new RoutingInfo(String.format("%s : %.6f",
          host, value)));
    }

    top.addMoreInfo(ri);
    return top;
  }

  @Override
  public MessageRouter replicate() {
    RandomProphetRouter r = new RandomProphetRouter(this);
    return r;
  }
}
