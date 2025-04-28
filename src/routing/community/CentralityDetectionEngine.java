package routing.community;

public interface CentralityDetectionEngine {
    public double getGlobalCentrality();
    public double getLocalCentrality();
    public int[] getArrayCentrality();
}