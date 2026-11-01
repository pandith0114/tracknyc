package nyc.tracknyc.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import nyc.live.core.geo.LatLon;
import nyc.live.core.geo.Polyline;
import nyc.tracknyc.network.NetworkModel;
import nyc.tracknyc.network.Route;
import nyc.tracknyc.network.Segment;
import nyc.tracknyc.network.Stop;
import nyc.tracknyc.network.SystemNetwork;
import nyc.tracknyc.network.Trip;

/** Builds the static JSON / GeoJSON documents served to the map. Pure functions over the model. */
public final class NetworkViews {

    private NetworkViews() {
    }

    public record RouteView(String system, String id, String publicId, String shortName, String longName,
                            String color, String textColor) {
        static RouteView of(Route r) {
            return new RouteView(r.system(), r.id(), NetworkModel.publicId(r.system(), r.id()), r.displayName(),
                    r.longName(), "#" + r.color(), "#" + r.textColor());
        }
    }

    public static List<RouteView> routes(SystemNetwork net) {
        List<RouteView> out = new ArrayList<>();
        net.routes().stream().sorted((a, b) -> a.displayName().compareToIgnoreCase(b.displayName()))
                .forEach(r -> out.add(RouteView.of(r)));
        return out;
    }

    /** One LineString feature per (route, shape); falls back to segments if a feed has no shapes. */
    public static Map<String, Object> lines(SystemNetwork net) {
        Map<String, String> routeByShape = new LinkedHashMap<>();
        for (Trip t : net.trips()) {
            if (t.shapeId() != null) {
                routeByShape.putIfAbsent(t.shapeId(), t.routeId());
            }
        }
        List<Map<String, Object>> features = new ArrayList<>();
        routeByShape.forEach((shapeId, routeId) -> {
            Polyline line = net.shapes().get(shapeId);
            if (line != null) {
                features.add(lineFeature(net, routeId, shapeId, line));
            }
        });
        if (features.isEmpty()) {
            Map<String, String> routeBySegment = new HashMap<>();
            for (Trip t : net.trips()) {
                for (int i = 1; i < t.stopCount(); i++) {
                    routeBySegment.putIfAbsent(Segment.key(t.stopId(i - 1), t.stopId(i)), t.routeId());
                }
            }
            for (Segment s : net.segments()) {
                String routeId = routeBySegment.get(s.key());
                if (routeId != null) {
                    features.add(lineFeature(net, routeId, s.key(), s.geometry()));
                }
            }
        }
        return featureCollection(features);
    }

    private static Map<String, Object> lineFeature(SystemNetwork net, String routeId, String id, Polyline line) {
        Route r = net.route(routeId).orElse(null);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("system", net.system());
        props.put("routeId", routeId);
        props.put("route", r == null ? routeId : r.displayName());
        props.put("color", "#" + (r == null ? "808183" : r.color()));
        props.put("shapeId", id);
        List<double[]> coords = new ArrayList<>(line.size());
        for (LatLon p : line.points()) {
            coords.add(new double[] {p.lon(), p.lat()});
        }
        return feature(Map.of("type", "LineString", "coordinates", coords), props);
    }

    /** Stations with the routes that stop there. */
    public static Map<String, Object> stations(SystemNetwork net) {
        Map<String, Set<String>> routesAtStation = new HashMap<>();
        for (Trip t : net.trips()) {
            for (int i = 0; i < t.stopCount(); i++) {
                String station = net.stationOf(t.stopId(i)).map(Stop::id).orElse(t.stopId(i));
                routesAtStation.computeIfAbsent(station, k -> new TreeSet<>()).add(t.routeId());
            }
        }
        List<Map<String, Object>> features = new ArrayList<>();
        for (Stop s : net.stations()) {
            Set<String> routes = routesAtStation.get(s.id());
            if (routes == null) {
                continue; // stations nothing stops at are noise on the map
            }
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("id", NetworkModel.publicId(net.system(), s.id()));
            props.put("system", net.system());
            props.put("name", s.name());
            props.put("routes", List.copyOf(routes));
            features.add(feature(Map.of("type", "Point",
                    "coordinates", new double[] {s.location().lon(), s.location().lat()}), props));
        }
        return featureCollection(features);
    }

    static Map<String, Object> feature(Map<String, Object> geometry, Map<String, Object> props) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("type", "Feature");
        f.put("geometry", geometry);
        f.put("properties", props);
        return f;
    }

    public static Map<String, Object> featureCollection(List<Map<String, Object>> features) {
        Map<String, Object> fc = new LinkedHashMap<>();
        fc.put("type", "FeatureCollection");
        fc.put("features", features);
        return fc;
    }

    /** Merges several FeatureCollections (one per system) into one. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> merge(List<Map<String, Object>> collections) {
        List<Map<String, Object>> all = new ArrayList<>();
        for (Map<String, Object> fc : collections) {
            all.addAll((List<Map<String, Object>>) fc.get("features"));
        }
        return featureCollection(all);
    }
}
