package routing;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.MessageListener;
import core.Settings;

import java.util.*;

public class SimBetRouter extends ActiveRouter {

  private static final double ALPHA = 0.7; // Weight for similarity
  private static final double BETA = 0.3; // Weight for betweenness aging

  private Map<Integer, Set<Integer>> contactHistory;
  private Map<Integer, Double> betweennessCentrality;
  private int selfId;

  public SimBetRouter(Settings s) {
    super(s);
    contactHistory = new HashMap<>();
    betweennessCentrality = new HashMap<>();
  }

  protected SimBetRouter(SimBetRouter r) {
    super(r);
    this.contactHistory = new HashMap<>(r.contactHistory);
    this.betweennessCentrality = new HashMap<>(r.betweennessCentrality);
    this.selfId = r.selfId;
  }

  @Override
  public void init(DTNHost host, List<MessageListener> mListeners) {
    super.init(host, mListeners);
    selfId = host.getAddress();
    contactHistory.put(selfId, new HashSet<>());
    betweennessCentrality.put(selfId, 0.0);
  }

  @Override
  public void changedConnection(Connection con) {
    super.changedConnection(con);
    if (con.isUp()) {
      int otherId = con.getOtherNode(getHost()).getAddress();
      contactHistory.computeIfAbsent(selfId, k -> new HashSet<>()).add(otherId);
      updateBetweennessCentrality(otherId);
    }
  }

  private double calculateSimBetUtility(int otherId) {
    double similarity = calculateSimilarity(otherId);
    double betweenness = calculateBetweennessCentrality(otherId);
    return ALPHA * similarity + (1 - ALPHA) * betweenness;
  }

  private double calculateSimilarity(int otherId) {
    Set<Integer> myContacts = contactHistory.get(selfId);
    Set<Integer> otherContacts = contactHistory.getOrDefault(otherId, Collections.emptySet());

    if (myContacts == null || myContacts.isEmpty() || otherContacts.isEmpty()) {
      return 0.0;
    }

    int intersectionSize = 0;
    for (Integer contact : myContacts) {
      if (otherContacts.contains(contact)) {
        intersectionSize++;
      }
    }

    int unionSize = myContacts.size() + otherContacts.size() - intersectionSize;
    return unionSize == 0 ? 0.0 : (double) intersectionSize / unionSize;
  }

  private double calculateBetweennessCentrality(int hostId) {
    return betweennessCentrality.getOrDefault(hostId, 0.0);
  }

  private void updateBetweennessCentrality(int hostId) {
    long centrality = contactHistory.values().stream()
        .filter(contacts -> contacts.contains(hostId))
        .count();
    double agedCentrality = betweennessCentrality.getOrDefault(hostId, 0.0) * (1 - BETA);
    betweennessCentrality.put(hostId, agedCentrality + centrality);
  }

  @Override
  public void update() {
    super.update();

    List<Connection> connections = getConnections();
    if (connections.isEmpty()) {
      return;
    }

    double selfUtility = calculateSimBetUtility(selfId);

    for (Connection con : connections) {
      int otherId = con.getOtherNode(getHost()).getAddress();
      double otherUtility = calculateSimBetUtility(otherId);

      if (otherUtility <= selfUtility) {
        continue;
      }

      for (Message msg : getMessageCollection()) {
        if (msg.getTtl() > 0 && !isBlacklistedMessage(msg.getId())) {
          if (startTransfer(msg, con) == RCV_OK) {
            break;
          }
        }
      }
    }
  }

  @Override
  public SimBetRouter replicate() {
    return new SimBetRouter(this);
  }
}
