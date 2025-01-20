package routing;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.MessageListener;
import core.Settings;

import java.util.*;

/**
 * SimBetRouter optimized for reduced memory usage.
 */
public class SimBetRouter extends ActiveRouter {

  private Map<Integer, Set<Integer>> contactHistory;
  private Map<Integer, Integer> betweennessCentrality;

  public SimBetRouter(Settings s) {
    super(s);
    contactHistory = new HashMap<>();
    betweennessCentrality = new HashMap<>();
  }

  protected SimBetRouter(SimBetRouter r) {
    super(r);
    this.contactHistory = new HashMap<>(r.contactHistory);
    this.betweennessCentrality = new HashMap<>(r.betweennessCentrality);
  }

  @Override
  public void init(DTNHost host, List<MessageListener> mListeners) {
    super.init(host, mListeners);
    contactHistory.put(host.getAddress(), new HashSet<>());
    betweennessCentrality.put(host.getAddress(), 0);
  }

  @Override
  public void changedConnection(Connection con) {
    super.changedConnection(con);
    if (con.isUp()) {
      int selfId = getHost().getAddress();
      int otherId = con.getOtherNode(getHost()).getAddress();
      contactHistory.computeIfAbsent(selfId, k -> new HashSet<>()).add(otherId);
      updateBetweennessCentrality(otherId);
    }
  }

  @Override
  public SimBetRouter replicate() {
    return new SimBetRouter(this);
  }

  private double calculateSimilarity(int otherId) {
    Set<Integer> myContacts = contactHistory.get(getHost().getAddress());
    Set<Integer> otherContacts = contactHistory.getOrDefault(otherId, Collections.emptySet());

    if (myContacts == null || myContacts.isEmpty() || otherContacts.isEmpty()) {
      return 0.0;
    }

    long intersectionSize = myContacts.stream().filter(otherContacts::contains).count();
    long unionSize = myContacts.size() + otherContacts.size() - intersectionSize;

    return unionSize == 0 ? 0.0 : (double) intersectionSize / unionSize;
  }

  private int calculateBetweennessCentrality(int hostId) {
    return betweennessCentrality.getOrDefault(hostId, 0);
  }

  @Override
  public void update() {
    super.update();

    List<Connection> connections = getConnections();
    if (connections.isEmpty()) {
      return;
    }

    for (Connection con : connections) {
      int otherId = con.getOtherNode(getHost()).getAddress();
      double similarity = calculateSimilarity(otherId);
      double betweenness = calculateBetweennessCentrality(otherId);

      if (!shouldForwardMessage(similarity, betweenness)) {
        continue;
      }

      // Process messages directly without intermediate list
      for (Message msg : getMessageCollection()) {
        if (msg.getTo().getAddress() == otherId) {
          if (startTransfer(msg, con) == RCV_OK) {
            break;
          }
        } else if (msg.getTtl() > 0 && !isBlacklistedMessage(msg.getId())) {
          if (startTransfer(msg, con) == RCV_OK) {
            break;
          }
        }
      }
    }
  }

  @Override
  public boolean createNewMessage(Message msg) {
    makeRoomForNewMessage(msg.getSize());
    msg.setTtl(this.msgTtl);
    addToMessages(msg, true);
    return true;
  }

  private boolean shouldForwardMessage(double similarity, double betweenness) {
    double similarityThreshold = 0.4;
    double betweennessThreshold = 0.4;

    return similarity > similarityThreshold || betweenness > betweennessThreshold;
  }

  private void updateBetweennessCentrality(int hostId) {
    int centrality = (int) contactHistory.values().stream()
        .filter(contacts -> contacts.contains(hostId))
        .count();
    betweennessCentrality.put(hostId, centrality);
  }

  @Override
  public void deleteMessage(String id, boolean drop) {
    super.deleteMessage(id, drop);
  }
}
