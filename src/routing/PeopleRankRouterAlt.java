package routing;

import core.*;
import java.util.*;

/**
 * PeopleRankRouterAlt implementation for the ONE simulator using
 * RoutingDecisionEngine.
 * Uses a PeopleRank algorithm where nodes are ranked based on social
 * connections
 * defined by group membership. Messages are forwarded only to nodes with
 * strictly
 * higher PeopleRank or to the destination.
 */
public class PeopleRankRouterAlt implements RoutingDecisionEngine {
  private double myPeR;
  private Set<DTNHost> socialNeighbors;
  private Map<DTNHost, Double> neighborPeRs;
  private Map<DTNHost, Integer> neighborDegrees;
  private double d;
  private String groupID;
  private DTNHost thisHost;
  private boolean socialNeighborsSet = false;

  private static Map<DTNHost, String> hostToGroup = null;

  public PeopleRankRouterAlt(Settings s) {
    this.groupID = s.getSetting("groupID");
    this.d = s.getDouble("dampingFactor", 0.8);
    this.socialNeighbors = new HashSet<>();
    this.neighborPeRs = new HashMap<>();
    this.neighborDegrees = new HashMap<>();
    this.myPeR = 0.0;
  }

  public PeopleRankRouterAlt(PeopleRankRouterAlt r) {
    this.groupID = r.groupID;
    this.d = r.d;
    this.myPeR = r.myPeR;
    this.socialNeighbors = new HashSet<>(r.socialNeighbors);
    this.neighborPeRs = new HashMap<>(r.neighborPeRs);
    this.neighborDegrees = new HashMap<>(r.neighborDegrees);
    this.socialNeighborsSet = r.socialNeighborsSet;
  }

  public void init(DTNHost host) {
    this.thisHost = host;
  }

  @Override
  public void connectionUp(DTNHost thisHost, DTNHost peer) {
    updatePeopleRank(peer);
  }

  @Override
  public void connectionDown(DTNHost thisHost, DTNHost peer) {
    // No action needed
  }

  @Override
  public void doExchangeForNewConnection(Connection con, DTNHost peer) {
    MessageRouter peerRouter = peer.getRouter();
    if (peerRouter instanceof DecisionEngineRouter) {
      RoutingDecisionEngine peerDecisionEngine = ((DecisionEngineRouter) peerRouter).getDecisionEngine();
      if (peerDecisionEngine instanceof PeopleRankRouterAlt) {
        PeopleRankRouterAlt peerPR = (PeopleRankRouterAlt) peerDecisionEngine;
        if (socialNeighbors.contains(peer)) {
          neighborPeRs.put(peer, peerPR.getPeR());
          neighborDegrees.put(peer, peerPR.getSocialDegree());
        }
        updatePeopleRank(peer);
      }
    }
  }

  @Override
  public boolean newMessage(Message m) {
    return true;
  }

  @Override
  public boolean isFinalDest(Message m, DTNHost aHost) {
    return m.getTo() == aHost;
  }

  @Override
  public boolean shouldSaveReceivedMessage(Message m, DTNHost thisHost) {
    return m.getTo() != thisHost;
  }

  @Override
  public boolean shouldSendMessageToHost(Message m, DTNHost otherHost) {
    MessageRouter otherRouter = otherHost.getRouter();
    if (otherRouter instanceof DecisionEngineRouter) {
      RoutingDecisionEngine otherDecisionEngine = ((DecisionEngineRouter) otherRouter).getDecisionEngine();
      if (otherDecisionEngine instanceof PeopleRankRouterAlt) {
        PeopleRankRouterAlt otherPR = (PeopleRankRouterAlt) otherDecisionEngine;
        double otherPeR = otherPR.getPeR();
        return otherPeR > myPeR || otherHost == m.getTo();
      }
    }
    return otherHost == m.getTo(); // Default to destination-only if not compatible
  }

  @Override
  public boolean shouldDeleteSentMessage(Message m, DTNHost otherHost) {
    return otherHost == m.getTo();
  }

  @Override
  public boolean shouldDeleteOldMessage(Message m, DTNHost hostReportingOld) {
    return true;
  }

  @Override
  public RoutingDecisionEngine replicate() {
    return new PeopleRankRouterAlt(this);
  }

  private void updatePeopleRank(DTNHost peer) {
    if (hostToGroup == null) {
      synchronized (PeopleRankRouterAlt.class) {
        if (hostToGroup == null) {
          hostToGroup = new HashMap<>();
          for (DTNHost h : SimScenario.getInstance().getHosts()) {
            MessageRouter router = h.getRouter();
            if (router instanceof DecisionEngineRouter) {
              RoutingDecisionEngine de = ((DecisionEngineRouter) router).getDecisionEngine();
              if (de instanceof PeopleRankRouterAlt) {
                hostToGroup.put(h, ((PeopleRankRouterAlt) de).groupID);
              }
            }
          }
        }
      }
    }

    if (!socialNeighborsSet) {
      for (DTNHost h : SimScenario.getInstance().getHosts()) {
        if (hostToGroup.get(h) != null && hostToGroup.get(h).equals(this.groupID) && h != thisHost) {
          socialNeighbors.add(h);
        }
      }
      socialNeighborsSet = true;
      int degree = socialNeighbors.size();
      for (DTNHost neighbor : socialNeighbors) {
        neighborPeRs.put(neighbor, 0.0);
        neighborDegrees.put(neighbor, degree);
      }
    }

    MessageRouter peerRouter = peer.getRouter();
    if (peerRouter instanceof DecisionEngineRouter) {
      RoutingDecisionEngine peerDecisionEngine = ((DecisionEngineRouter) peerRouter).getDecisionEngine();
      if (peerDecisionEngine instanceof PeopleRankRouterAlt && socialNeighbors.contains(peer)) {
        PeopleRankRouterAlt peerPR = (PeopleRankRouterAlt) peerDecisionEngine;
        neighborPeRs.put(peer, peerPR.getPeR());
        neighborDegrees.put(peer, peerPR.getSocialDegree());
        double sum = 0;
        for (DTNHost neighbor : socialNeighbors) {
          double perJ = neighborPeRs.get(neighbor);
          int degreeJ = neighborDegrees.get(neighbor);
          if (degreeJ > 0) {
            sum += perJ / degreeJ;
          }
        }
        myPeR = (1 - d) + d * sum;
      }
    }
  }

  public double getPeR() {
    return myPeR;
  }

  public int getSocialDegree() {
    return socialNeighbors != null ? socialNeighbors.size() : 0;
  }
}
