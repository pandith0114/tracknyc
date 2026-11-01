package nyc.tracknyc.realtime;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.UnknownFieldSet;
import com.google.protobuf.WireFormat;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Reads the MTA's GTFS-realtime extensions straight from protobuf's unknown-field set, so the
 * build needs no protoc step on top of the stock gtfs-realtime-bindings (see ADR 0005).
 *
 * <ul>
 *   <li>NYCT {@code NyctTripDescriptor} — extension 1001 on TripDescriptor:
 *       1 train_id (string), 2 is_assigned (bool), 3 direction (enum NORTH=1 EAST=2 SOUTH=3 WEST=4)</li>
 *   <li>NYCT {@code NyctStopTimeUpdate} — extension 1001 on StopTimeUpdate:
 *       1 scheduled_track, 2 actual_track (strings)</li>
 *   <li>MTA railroad stop-time update — extension 1005 on StopTimeUpdate: 1 track (string).
 *       Field layout is our reading of the MTA railroad docs; treat as best-effort.</li>
 * </ul>
 */
public final class NyctExtensions {

    public static final int NYCT_EXTENSION = 1001;
    public static final int MTA_RAILROAD_EXTENSION = 1005;

    private NyctExtensions() {
    }

    public static Optional<RtFeed.NyctTrip> tripDescriptor(UnknownFieldSet unknown) throws IOException {
        Optional<ByteString> bytes = lastLengthDelimited(unknown, NYCT_EXTENSION);
        if (bytes.isEmpty()) {
            return Optional.empty();
        }
        String trainId = null;
        boolean assigned = false;
        String direction = null;
        CodedInputStream in = bytes.get().newCodedInput();
        int tag;
        while ((tag = in.readTag()) != 0) {
            switch (WireFormat.getTagFieldNumber(tag)) {
                case 1 -> trainId = in.readString();
                case 2 -> assigned = in.readBool();
                case 3 -> direction = switch (in.readEnum()) {
                    case 1 -> "N";
                    case 2 -> "E";
                    case 3 -> "S";
                    case 4 -> "W";
                    default -> null;
                };
                default -> in.skipField(tag);
            }
        }
        return Optional.of(new RtFeed.NyctTrip(trainId, assigned, direction));
    }

    /** Returns {scheduledTrack, actualTrack}; either may be null. */
    public static String[] stopTimeTracks(UnknownFieldSet unknown) throws IOException {
        String scheduled = null;
        String actual = null;
        Optional<ByteString> nyct = lastLengthDelimited(unknown, NYCT_EXTENSION);
        if (nyct.isPresent()) {
            CodedInputStream in = nyct.get().newCodedInput();
            int tag;
            while ((tag = in.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> scheduled = in.readString();
                    case 2 -> actual = in.readString();
                    default -> in.skipField(tag);
                }
            }
        }
        Optional<ByteString> rr = lastLengthDelimited(unknown, MTA_RAILROAD_EXTENSION);
        if (rr.isPresent()) {
            CodedInputStream in = rr.get().newCodedInput();
            int tag;
            while ((tag = in.readTag()) != 0) {
                if (WireFormat.getTagFieldNumber(tag) == 1 && WireFormat.getTagWireType(tag)
                        == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                    actual = in.readString();
                } else {
                    in.skipField(tag);
                }
            }
        }
        return new String[] {scheduled, actual};
    }

    private static Optional<ByteString> lastLengthDelimited(UnknownFieldSet unknown, int number) {
        if (unknown == null || !unknown.hasField(number)) {
            return Optional.empty();
        }
        List<ByteString> list = unknown.getField(number).getLengthDelimitedList();
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(list.size() - 1));
    }
}
