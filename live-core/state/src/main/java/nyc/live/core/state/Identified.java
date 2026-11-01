package nyc.live.core.state;

/** Anything stored in the latest-state store or pushed to clients has a stable id. */
public interface Identified {
    String id();
}
