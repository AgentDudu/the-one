package routing.community; 

import java.util.Comparator;

import core.DTNHost;


public class ContactEvent {
    public final double endTime;
    public final DTNHost peer;

    public ContactEvent(double endTime, DTNHost peer) {
        this.endTime = endTime;
        this.peer = peer;
    }

    public static Comparator<ContactEvent> endTimeComparator() {
        return Comparator.comparingDouble(ce -> ce.endTime);
    }

    @Override
    public String toString() {
        return "ContactEvent{" +
               "endTime=" + endTime +
               ", peer=" + peer +
               '}';
    }
}