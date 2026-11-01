package nyc.tracknyc.position;

/** How the engine turns arrival predictions into a map position. */
public enum PositionMode {
    /** Milestone 1: draw every train at the next station it will reach. */
    NEXT_STOP,
    /** Milestone 2+: estimate the position along the track shape between the two stops. */
    INTERPOLATE;

    public static PositionMode parse(String s) {
        return s == null ? INTERPOLATE : valueOf(s.trim().toUpperCase().replace('-', '_'));
    }
}
