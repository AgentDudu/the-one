package report;

import core.DTNHost;
import core.Settings;
import core.SimClock;
import core.SimScenario;
import core.SettingsError;
import core.World;

import routing.community.ContactEvent;
import routing.community.ContactHistoryManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reports cumulative global popularity using a ContactHistoryManager.
 * Processes connection history post-simulation via the manager.
 */
public class GlobalPopularity3Report extends Report {

  public static final String REPORT_INTERVAL_S = "reportInterval";
  public static final int DEFAULT_REPORT_INTERVAL = 86400;

  private int reportInterval;
  private ContactHistoryManager contactManager;

  public GlobalPopularity3Report() {
    init();
  }

  @Override
  protected void init() {
    super.init();
    this.reportInterval = getSettings().getInt(REPORT_INTERVAL_S, DEFAULT_REPORT_INTERVAL);
    if (this.reportInterval <= 0) {
      throw new SettingsError("Report interval must be positive: " + reportInterval);
    }

    this.contactManager = new ContactHistoryManager();
    SimScenario.getInstance().addConnectionListener(this.contactManager);
  }

  @Override
  public void done() {
    World world;
    try {
      world = SimScenario.getInstance().getWorld();
      if (world == null || world.getHosts() == null || world.getHosts().isEmpty()) {
        write("ERROR: World or hosts not available via SimScenario.");
        super.done();
        return;
      }
    } catch (Exception e) {
      write("ERROR: Could not get World/hosts via SimScenario: " + e.getMessage());
      super.done();
      return;
    }

    List<DTNHost> allHosts = world.getHosts();
    allHosts.sort(Comparator.comparingInt(DTNHost::getAddress));

    double maxSimTime = SimClock.getTime();
    List<Integer> sortedReportTimes = new ArrayList<>();
    for (int t = reportInterval;; t += reportInterval) {
      sortedReportTimes.add(t);
      if (t >= maxSimTime)
        break;
    }

    write("NodeID," + sortedReportTimes.stream()
        .map(t -> "Pop@" + t)
        .collect(Collectors.joining(",")));

    Map<DTNHost, List<ContactEvent>> allHostEvents = contactManager.getPerHostContactHistory();

    for (DTNHost host : allHosts) {
      if (host == null)
        continue;
      List<ContactEvent> hostEvents = allHostEvents.getOrDefault(host, Collections.emptyList());
      hostEvents.sort(Comparator.comparingDouble(ce -> ce.endTime));

      StringBuilder row = new StringBuilder(host.toString());
      Set<DTNHost> cumulativePeers = new HashSet<>();
      int eventIndex = 0;

      for (Integer reportTime : sortedReportTimes) {
        while (eventIndex < hostEvents.size() && hostEvents.get(eventIndex).endTime <= reportTime) {
          cumulativePeers.add(hostEvents.get(eventIndex).peer);
          eventIndex++;
        }
        row.append(",").append(cumulativePeers.size());
      }
      write(row.toString());
    }

    super.done();
  }
}
