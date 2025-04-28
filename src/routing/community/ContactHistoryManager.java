package routing.community;

import core.ConnectionListener;
import core.DTNHost;
import core.SimClock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap; // Use ConcurrentHashMap for safety if accessed across threads, though unlikely in ONE report listeners

/**
 * Manages connection history by listening to connection events.
 * Stores contact events (end time and peer) per host, suitable for
 * post-simulation analysis like popularity reports.
 */
public class ContactHistoryManager implements ConnectionListener {

  private final Map<Pair, Double> connectionStartTimes;
  private final Map<DTNHost, List<ContactEvent>> hostContactEvents;

  public ContactHistoryManager() {
    this.connectionStartTimes = new ConcurrentHashMap<>();
    this.hostContactEvents = new ConcurrentHashMap<>();
  }

  @Override
  public void hostsConnected(DTNHost h1, DTNHost h2) {
    connectionStartTimes.putIfAbsent(new Pair(h1, h2), SimClock.getTime());
  }

  @Override
  public void hostsDisconnected(DTNHost h1, DTNHost h2) {
    Pair p = new Pair(h1, h2);
    Double startTime = connectionStartTimes.remove(p);

    if (startTime != null) {
      double endTime = SimClock.getTime();
      if (endTime > startTime) {
        ContactEvent eventForH1 = new ContactEvent(endTime, p.h2);
        ContactEvent eventForH2 = new ContactEvent(endTime, p.h1);

        hostContactEvents.computeIfAbsent(p.h1, k -> Collections.synchronizedList(new ArrayList<>())).add(eventForH1);
        hostContactEvents.computeIfAbsent(p.h2, k -> Collections.synchronizedList(new ArrayList<>())).add(eventForH2);
      }
    }
    // Else: Ignore disconnect without start (might happen at sim start/end)
  }

  /**
   * Retrieves the recorded contact history, organized per host.
   * Each host maps to a list of ContactEvents representing connections
   * they participated in, marked by the connection end time.
   *
   * @return A map where keys are hosts and values are lists of their
   *         ContactEvents.
   *         Returns an unmodifiable view to prevent external modification.
   */
  public Map<DTNHost, List<ContactEvent>> getPerHostContactHistory() {
    return hostContactEvents;
  }

  /**
   * Clears all recorded history.
   */
  public void clearHistory() {
    connectionStartTimes.clear();
    hostContactEvents.clear();
  }
}
