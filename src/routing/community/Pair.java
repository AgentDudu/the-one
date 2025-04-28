package routing.community;

import core.DTNHost;
import java.util.Objects;

public class Pair {

    public final DTNHost h1;
    public final DTNHost h2;

    public Pair(DTNHost host1, DTNHost host2) {
        if (host1.getAddress() < host2.getAddress()) {
            this.h1 = host1;
            this.h2 = host2;
        } else {
            this.h1 = host2;
            this.h2 = host1;
        }
    }

    public DTNHost getPeer(DTNHost host) {
        if (host == h1) {
            return h2;
        } else if (host == h2) {
            return h1;
        } else {
            return null;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Pair pair = (Pair) o;
        return Objects.equals(h1, pair.h1) && Objects.equals(h2, pair.h2);
    }

    @Override
    public int hashCode() {
        return Objects.hash(h1, h2);
    }

    @Override
    public String toString() {
        return "Pair{" + h1 + " <-> " + h2 + '}';
    }
}