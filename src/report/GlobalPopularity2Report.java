package report;

import core.ConnectionListener;
import core.DTNHost;
import core.Settings;
import core.SimClock;
import core.SimScenario;
import core.SettingsError;
import core.UpdateListener;
import core.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class GlobalPopularity2Report extends Report implements ConnectionListener, UpdateListener {

    public static final String REPORT_INTERVAL_S = "reportInterval";
    public static final int DEFAULT_REPORT_INTERVAL = 86400;

    private Map<DTNHost, Set<DTNHost>> uniqueContacts;
    private Map<DTNHost, Map<Integer, Integer>> popularitySnapshots;
    private double nextReportTime;
    private int reportInterval;

    public GlobalPopularity2Report() {
        init();
    }

    @Override
    protected void init() {
        super.init();
        this.uniqueContacts = new HashMap<>();
        this.popularitySnapshots = new HashMap<>();

        Settings s = getSettings();
        this.reportInterval = s.getInt(REPORT_INTERVAL_S, DEFAULT_REPORT_INTERVAL);
        if (this.reportInterval <= 0) {
            throw new SettingsError("Report interval must be positive: " + this.reportInterval);
        }
        this.nextReportTime = this.reportInterval;
    }

    @Override
    public void hostsConnected(DTNHost h1, DTNHost h2) {
        uniqueContacts.computeIfAbsent(h1, k -> new HashSet<>()).add(h2);
        uniqueContacts.computeIfAbsent(h2, k -> new HashSet<>()).add(h1);
    }

    @Override
    public void hostsDisconnected(DTNHost h1, DTNHost h2) {
        // No action needed
    }

    @Override
    public void updated(List<DTNHost> hosts) {
        while (SimClock.getTime() >= this.nextReportTime) {
            takeSnapshots((int) this.nextReportTime);
            this.nextReportTime += this.reportInterval;
        }
    }

    private void takeSnapshots(int reportTime) {
        for (Map.Entry<DTNHost, Set<DTNHost>> entry : this.uniqueContacts.entrySet()) {
            DTNHost host = entry.getKey();
            int currentPopularity = entry.getValue().size();
            Map<Integer, Integer> hostSnapshots = popularitySnapshots.computeIfAbsent(host, k -> new TreeMap<>());
            hostSnapshots.put(reportTime, currentPopularity);
        }
    }

    @Override
    public void done() {
        World world = null;
        try {
            world = SimScenario.getInstance().getWorld();
        } catch (Exception e) {
             write("ERROR: Could not obtain World instance via SimScenario. Cannot generate report. " + e.getMessage());
             super.done();
             return;
        }
        if (world == null) {
            write("ERROR: Obtained null World instance via SimScenario. Cannot generate report.");
            super.done();
            return;
        }

        List<DTNHost> allHosts = world.getHosts();
        if (allHosts == null || allHosts.isEmpty()) {
             write("No hosts found in the simulation scenario.");
             super.done();
             return;
        }
        allHosts.sort(Comparator.comparingInt(DTNHost::getAddress));

        Set<Integer> reportTimesSet = new HashSet<>();
        for (Map<Integer, Integer> snapshots : popularitySnapshots.values()) {
            reportTimesSet.addAll(snapshots.keySet());
        }

        double maxSimTime = SimClock.getTime();
        int maxReportIntervalTime = 0;
        if (!reportTimesSet.isEmpty()){
             maxReportIntervalTime = Collections.max(reportTimesSet);
        }
        int lastFullIntervalEnd = (int) (Math.ceil(maxSimTime / reportInterval) * reportInterval);
        maxReportIntervalTime = Math.max(maxReportIntervalTime, lastFullIntervalEnd);

        for (int t = reportInterval; t <= maxReportIntervalTime; t += reportInterval) {
            if (t <= maxSimTime + reportInterval || reportTimesSet.contains(t)) {
                 reportTimesSet.add(t);
            }
        }

        List<Integer> sortedReportTimes = new ArrayList<>(reportTimesSet);
        Collections.sort(sortedReportTimes);

        StringBuilder header = new StringBuilder("NodeID");
        for (Integer time : sortedReportTimes) {
            header.append(",Pop@").append(time);
        }
        write(header.toString());

        for (DTNHost host : allHosts) {
             if (host == null) continue;
             StringBuilder row = new StringBuilder(host.toString());
             Map<Integer, Integer> hostSnapshots = popularitySnapshots.get(host);

             int lastKnownPopularity = 0;
             for (Integer reportTime : sortedReportTimes) {
                 int currentPopularity = lastKnownPopularity;

                 if (hostSnapshots != null) {
                     Integer latestSnapshotTime = null;
                      for (Integer snapshotTime : hostSnapshots.keySet()) {
                          if (snapshotTime <= reportTime) {
                              if (latestSnapshotTime == null || snapshotTime > latestSnapshotTime) {
                                  latestSnapshotTime = snapshotTime;
                              }
                          }
                      }
                      if (latestSnapshotTime != null) {
                          currentPopularity = hostSnapshots.get(latestSnapshotTime);
                      }
                 }
                 row.append(",").append(currentPopularity);
                 lastKnownPopularity = currentPopularity;
             }
             write(row.toString());
        }
        super.done();
    }
}