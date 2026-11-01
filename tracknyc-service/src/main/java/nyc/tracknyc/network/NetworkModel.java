package nyc.tracknyc.network;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** All loaded systems. Replaced atomically when a nightly static refresh finishes. */
public final class NetworkModel {

    private final Map<String, SystemNetwork> systems;

    public NetworkModel(Collection<SystemNetwork> systems) {
        Map<String, SystemNetwork> m = new LinkedHashMap<>();
        for (SystemNetwork s : systems) {
            m.put(s.system(), s);
        }
        this.systems = Map.copyOf(m);
    }

    public static NetworkModel empty() {
        return new NetworkModel(java.util.List.of());
    }

    public Optional<SystemNetwork> system(String id) {
        return Optional.ofNullable(systems.get(id));
    }

    public Collection<SystemNetwork> systems() {
        return systems.values();
    }

    /** Public ids are namespaced: "subway:127" so subway and LIRR ids can never clash. */
    public static String publicId(String system, String id) {
        return system + ':' + id;
    }

    /** Splits "subway:127" into ["subway", "127"]; returns null for malformed ids. */
    public static String[] splitPublicId(String publicId) {
        int i = publicId == null ? -1 : publicId.indexOf(':');
        if (i <= 0 || i == publicId.length() - 1) {
            return null;
        }
        return new String[] {publicId.substring(0, i), publicId.substring(i + 1)};
    }
}
