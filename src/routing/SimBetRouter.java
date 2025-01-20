package routing;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.MessageListener;
import core.Settings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SimBetRouter extends ActiveRouter {

  private Map<DTNHost, Set<DTNHost>> contactHistory;
  private Map<DTNHost, Double> betweennessCentrality;

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
    contactHistory.put(host, new HashSet<>());
    betweennessCentrality.put(host, 0.0);
  }

  @Override
  public void changedConnection(Connection con) {
    super.changedConnection(con);
    if (con.isUp()) {
      DTNHost other = con.getOtherNode(getHost());
      contactHistory.get(getHost()).add(other);
      updateBetweennessCentrality(other);
    }
  }

  @Override
  public SimBetRouter replicate() {
    return new SimBetRouter(this);
  }

  private double calculateSimilarity(DTNHost other) {
    Set<DTNHost> myContacts = contactHistory.get(getHost());
    Set<DTNHost> otherContacts = contactHistory.get(other);

    if (myContacts == null || otherContacts == null) {
      return 0.0;
    }

    Set<DTNHost> intersection = new HashSet<>(myContacts);
    intersection.retainAll(otherContacts);

    Set<DTNHost> union = new HashSet<>(myContacts);
    union.addAll(otherContacts);

    return (double) intersection.size() / union.size();
  }

  private double calculateBetweennessCentrality(DTNHost host) {
    return betweennessCentrality.getOrDefault(host, 0.0);
  }

  @Override
  public void update() {
    super.update();

    List<Connection> connections = getConnections();
    if (connections.isEmpty()) {
      return;
    }

    for (Connection con : connections) {
      DTNHost other = con.getOtherNode(getHost());
      double similarity = calculateSimilarity(other);
      double betweenness = calculateBetweennessCentrality(other);

      if (!shouldForwardMessage(similarity, betweenness)) {
        continue;
      }

      List<Message> messages = new ArrayList<>(getMessageCollection());
      sortByQueueMode(messages);

      for (Message msg : messages) {
        if (msg.getTo() == other) {
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

  private void updateBetweennessCentrality(DTNHost host) {
    double centrality = contactHistory.values().stream()
        .filter(contacts -> contacts.contains(host))
        .count();
    betweennessCentrality.put(host, centrality);
  }

  @Override
  public void deleteMessage(String id, boolean drop) {
    super.deleteMessage(id, drop);
  }

}
