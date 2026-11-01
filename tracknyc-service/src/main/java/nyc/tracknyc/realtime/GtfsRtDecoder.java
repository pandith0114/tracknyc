package nyc.tracknyc.realtime;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.transit.realtime.GtfsRealtime;
import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtime.TripDescriptor;
import com.google.transit.realtime.GtfsRealtime.TripUpdate;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import nyc.live.core.geo.LatLon;
import nyc.tracknyc.gtfs.GtfsTime;

/**
 * Decodes GTFS-realtime protobuf (via Google's gtfs-realtime-bindings) into {@link RtFeed},
 * including the MTA NYCT extensions. Lenient: one malformed entity is skipped, not fatal.
 */
public final class GtfsRtDecoder {

    private GtfsRtDecoder() {
    }

    public static RtFeed decode(String feedId, byte[] payload) throws InvalidProtocolBufferException {
        FeedMessage msg = FeedMessage.parseFrom(payload);
        if (!msg.hasHeader()) {
            throw new InvalidProtocolBufferException("feed has no header");
        }
        Instant header = Instant.ofEpochSecond(msg.getHeader().getTimestamp());
        List<RtFeed.TripUpdate> trips = new ArrayList<>();
        List<RtFeed.Vehicle> vehicles = new ArrayList<>();
        for (FeedEntity e : msg.getEntityList()) {
            if (e.getIsDeleted()) {
                continue;
            }
            try {
                if (e.hasTripUpdate()) {
                    trips.add(tripUpdate(e.getId(), e.getTripUpdate()));
                }
                if (e.hasVehicle()) {
                    vehicles.add(vehicle(e.getId(), e.getVehicle()));
                }
            } catch (IOException | RuntimeException ex) {
                // skip just this entity; the decode-error metric counts whole-feed failures
            }
        }
        return new RtFeed(feedId, header, trips, vehicles);
    }

    static RtFeed.TripUpdate tripUpdate(String entityId, TripUpdate tu) throws IOException {
        TripDescriptor td = tu.getTrip();
        List<RtFeed.StopTimeUpdate> stus = new ArrayList<>(tu.getStopTimeUpdateCount());
        for (TripUpdate.StopTimeUpdate s : tu.getStopTimeUpdateList()) {
            String[] tracks = NyctExtensions.stopTimeTracks(s.getUnknownFields());
            Instant arr = s.hasArrival() && s.getArrival().hasTime() ? Instant.ofEpochSecond(s.getArrival().getTime()) : null;
            Instant dep = s.hasDeparture() && s.getDeparture().hasTime()
                    ? Instant.ofEpochSecond(s.getDeparture().getTime()) : null;
            Integer delay = s.hasArrival() && s.getArrival().hasDelay() ? s.getArrival().getDelay() : null;
            boolean skipped = s.getScheduleRelationship() == TripUpdate.StopTimeUpdate.ScheduleRelationship.SKIPPED;
            stus.add(new RtFeed.StopTimeUpdate(s.getStopId(), s.hasStopSequence() ? s.getStopSequence() : null,
                    arr, dep, delay, skipped, tracks[0], tracks[1]));
        }
        RtFeed.NyctTrip nyct = NyctExtensions.tripDescriptor(td.getUnknownFields()).orElse(null);
        return new RtFeed.TripUpdate(entityId, td.getTripId(), td.hasRouteId() ? td.getRouteId() : null,
                parseDate(td.hasStartDate() ? td.getStartDate() : null), td.hasStartTime() ? td.getStartTime() : null,
                relationship(td), nyct, tu.hasTimestamp() ? Instant.ofEpochSecond(tu.getTimestamp()) : null, stus);
    }

    static RtFeed.Vehicle vehicle(String entityId, GtfsRealtime.VehiclePosition v) {
        LatLon pos = null;
        Float bearing = null;
        if (v.hasPosition()) {
            float lat = v.getPosition().getLatitude();
            float lon = v.getPosition().getLongitude();
            if (lat != 0f || lon != 0f) {
                pos = LatLon.of(lat, lon);
            }
            if (v.getPosition().hasBearing()) {
                bearing = v.getPosition().getBearing();
            }
        }
        RtFeed.VehicleStatus status = switch (v.getCurrentStatus()) {
            case INCOMING_AT -> RtFeed.VehicleStatus.INCOMING_AT;
            case STOPPED_AT -> RtFeed.VehicleStatus.STOPPED_AT;
            default -> RtFeed.VehicleStatus.IN_TRANSIT_TO;
        };
        TripDescriptor td = v.getTrip();
        return new RtFeed.Vehicle(entityId, td.getTripId(), td.hasRouteId() ? td.getRouteId() : null,
                v.hasStopId() ? v.getStopId() : null, v.hasCurrentStopSequence() ? v.getCurrentStopSequence() : null,
                v.hasCurrentStatus() ? status : null, pos, bearing,
                v.hasTimestamp() ? Instant.ofEpochSecond(v.getTimestamp()) : null);
    }

    private static RtFeed.TripRelationship relationship(TripDescriptor td) {
        if (!td.hasScheduleRelationship()) {
            return RtFeed.TripRelationship.SCHEDULED;
        }
        return switch (td.getScheduleRelationship()) {
            case ADDED -> RtFeed.TripRelationship.ADDED;
            case UNSCHEDULED -> RtFeed.TripRelationship.UNSCHEDULED;
            case CANCELED -> RtFeed.TripRelationship.CANCELED;
            default -> RtFeed.TripRelationship.SCHEDULED;
        };
    }

    private static LocalDate parseDate(String yyyymmdd) {
        if (yyyymmdd == null || yyyymmdd.isBlank()) {
            return null;
        }
        try {
            return GtfsTime.parseDate(yyyymmdd);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
