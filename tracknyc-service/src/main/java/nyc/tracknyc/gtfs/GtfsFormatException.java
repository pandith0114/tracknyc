package nyc.tracknyc.gtfs;

/** Malformed GTFS input. */
public class GtfsFormatException extends RuntimeException {
    public GtfsFormatException(String message) {
        super(message);
    }
}
