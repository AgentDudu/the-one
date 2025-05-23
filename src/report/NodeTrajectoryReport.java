package report;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import core.DTNHost;
import core.Settings;
import core.UpdateListener;

/**
 * NodeTrajectoryReport: Reports the (x, y) coordinates of nodes over time.
 * The nodes that are reported and the reporting interval (granularity) can be
 * configured.
 */
public class NodeTrajectoryReport extends Report implements UpdateListener {

  public static final String GRANULARITY = "granularity"; // idk why i didnt call this shit interval
  public static final int DEFAULT_GRANULARITY = 1;
  public static final String REPORTED_HOSTS_S = "reportedHostIndices";
  public static final String REPORTED_GROUP_S = "reportedGroup";

  private final int granularity;
  private double lastUpdate;
  private Set<Integer> reportedHostIndices;
  private String reportedGroupIdPrefix;

  public NodeTrajectoryReport() {
    Settings settings = getSettings();
    this.lastUpdate = 0;
    this.granularity = settings.getInt(GRANULARITY, DEFAULT_GRANULARITY);

    if (settings.contains(REPORTED_GROUP_S)) {
      this.reportedGroupIdPrefix = settings.getSetting(REPORTED_GROUP_S);
      this.reportedHostIndices = null;
    } else if (settings.contains(REPORTED_HOSTS_S)) {
      this.reportedHostIndices = new HashSet<>();
      for (String hostIdxStr : settings.getCsvSetting(REPORTED_HOSTS_S)) {
        try {
          this.reportedHostIndices.add(Integer.parseInt(hostIdxStr.trim()));
        } catch (NumberFormatException e) {
          System.err.println("WARN: Invalid host index '" + hostIdxStr + "' in " +
              REPORTED_HOSTS_S + ". Ignoring.");
        }
      }
      this.reportedGroupIdPrefix = null;
    } else {
      this.reportedHostIndices = null;
      this.reportedGroupIdPrefix = null;
    }

    init();
    write("SimTime,HostID,X,Y");
  }

  @Override
  public void updated(List<DTNHost> hosts) {
    double simTime = getSimTime();
    if (simTime == 0 && this.lastUpdate == 0 && this.granularity > 0) {
      createSnapshot(hosts, simTime);
      this.lastUpdate = simTime;
    } else if (simTime - this.lastUpdate >= this.granularity && this.granularity > 0) {
      createSnapshot(hosts, simTime);
      this.lastUpdate = simTime - (simTime % this.granularity);
      if (simTime > 0 && simTime % this.granularity == 0) {
        this.lastUpdate = simTime;
      }
    } else if (this.granularity == 0 && simTime > this.lastUpdate) {
      createSnapshot(hosts, simTime);
      this.lastUpdate = simTime;
    }
  }

  protected boolean isTracked(DTNHost host, int hostIndex) {
    if (this.reportedGroupIdPrefix != null) {
      return host.toString().startsWith(this.reportedGroupIdPrefix);
    }
    if (this.reportedHostIndices == null) {
      return true;
    }
    return this.reportedHostIndices.contains(hostIndex);
  }

  protected void createSnapshot(List<DTNHost> hosts, double simTime) {
    for (int i = 0; i < hosts.size(); i++) {
      DTNHost host = hosts.get(i);
      if (isTracked(host, i)) {
        String hostIdentifier = host.toString();
        double x = host.getLocation().getX();
        double y = host.getLocation().getY();
        write(String.format("%.2f,%s,%.2f,%.2f", simTime, hostIdentifier, x, y));
      }
    }
  }
}
