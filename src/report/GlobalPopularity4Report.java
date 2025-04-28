package report;

import core.DTNHost;
import core.SimScenario;
import routing.DecisionEngineRouter;
import routing.MessageRouter;
import routing.RoutingDecisionEngine;
import routing.community.CentralityDetectionEngine;

import java.util.*;

public class GlobalPopularity4Report extends Report {
  public GlobalPopularity4Report() {
    init();
  }

  @Override
  public void done() {
    List<DTNHost> nodes = SimScenario.getInstance().getHosts();
    Map<DTNHost, List<Integer>> arrayCentrality = new HashMap<DTNHost, List<Integer>>();

    for (DTNHost h : nodes) {
      MessageRouter r = h.getRouter();
      if (!(r instanceof DecisionEngineRouter))
        continue;
      RoutingDecisionEngine de = ((DecisionEngineRouter) r).getDecisionEngine();
      if (!(de instanceof CentralityDetectionEngine))
        continue;

      CentralityDetectionEngine ctd = (CentralityDetectionEngine) de;
      int[] arrayku = ctd.getArrayCentrality();

      List<Integer> myarray = new ArrayList<Integer>();

      for (int cent : arrayku) {
        myarray.add(cent);
      }
      arrayCentrality.put(h, myarray);
    }

    // for (Map.Entry<DTNHost, List<Integer>> entry : arrayCentrality.entrySet()) {
    // DTNHost h = entry.getKey();
    // List<Integer> list = entry.getValue();
    // write(entry.getKey() + ": " + entry.getValue());
    // }
    arrayCentrality.entrySet().stream()
        .sorted(Map.Entry.comparingByKey()) // This requires DTNHost to implement Comparable
        .forEach(entry -> {
          DTNHost h = entry.getKey();
          List<Integer> list = entry.getValue();
          write(h + ": " + list);
        });

    super.done();
  }
}
