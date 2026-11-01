package nyc.tracknyc.network;

/** A line ("L", "Babylon Branch") with its official colour as published in GTFS. */
public record Route(String id, String system, String shortName, String longName, int type, String color,
                    String textColor) {

    /** What riders call it: short name when present ("7"), else the long name ("Babylon Branch"). */
    public String displayName() {
        return shortName == null || shortName.isBlank() ? longName : shortName;
    }
}
