"""
Hand-entered network data for the SYNTHETIC fixtures.

Everything here is approximate and exists only so the replay demo has something
plausible to draw. Station coordinates were hand-entered and are APPROXIMATE (typically
within ~100 m, sometimes more); stop IDs follow the public MTA GTFS naming scheme for the
subway lines but must not be relied on. Track shapes are generated as straight lines between
stations. Replace all of it with the real GTFS downloads (see scripts/fetch_real_data.sh).
"""

# ---------------------------------------------------------------------------
# NYC Subway subset: the 1 (South Ferry - 96 St only), the 7 and the L.
# Station lists are ordered in the "N" (direction_id 0) direction of travel.
# Tuples: (parent stop_id, name, lat, lon)  -- coordinates APPROXIMATE.
# ---------------------------------------------------------------------------

SUBWAY_LINES = {
    "1": {
        "long_name": "Broadway - 7 Avenue Local (fixture subset: South Ferry - 96 St)",
        "color": "EE352E",
        "text_color": "FFFFFF",
        "headway_min": 6,
        "path": "03R",
        "rt_trip_id_has_path": False,
        "feed": "subway-1234567",
        "stations": [
            ("142", "South Ferry", 40.7020, -74.0131),
            ("139", "Rector St", 40.7073, -74.0136),
            ("138", "WTC Cortlandt", 40.7114, -74.0121),
            ("137", "Chambers St", 40.7155, -74.0093),
            ("136", "Franklin St", 40.7192, -74.0069),
            ("135", "Canal St", 40.7222, -74.0062),
            ("134", "Houston St", 40.7283, -74.0053),
            ("133", "Christopher St-Stonewall", 40.7334, -74.0029),
            ("132", "14 St", 40.7378, -74.0000),
            ("131", "18 St", 40.7410, -73.9978),
            ("130", "23 St", 40.7441, -73.9956),
            ("129", "28 St", 40.7475, -73.9934),
            ("128", "34 St-Penn Station", 40.7506, -73.9911),
            ("127", "Times Sq-42 St", 40.7553, -73.9873),
            ("126", "50 St", 40.7618, -73.9840),
            ("125", "59 St-Columbus Circle", 40.7682, -73.9819),
            ("124", "66 St-Lincoln Center", 40.7735, -73.9822),
            ("123", "72 St", 40.7784, -73.9819),
            ("122", "79 St", 40.7839, -73.9799),
            ("121", "86 St", 40.7889, -73.9767),
            ("120", "96 St", 40.7936, -73.9723),
        ],
    },
    "7": {
        "long_name": "Flushing Local",
        "color": "B933AD",
        "text_color": "FFFFFF",
        "headway_min": 5,
        "path": "97R",
        "rt_trip_id_has_path": False,
        "feed": "subway-1234567",
        "stations": [
            ("726", "34 St-Hudson Yards", 40.7555, -74.0020),
            ("725", "Times Sq-42 St", 40.7552, -73.9872),
            ("724", "5 Av", 40.7538, -73.9814),
            ("723", "Grand Central-42 St", 40.7513, -73.9761),
            ("721", "Vernon Blvd-Jackson Av", 40.7426, -73.9536),
            ("720", "Hunters Point Av", 40.7424, -73.9487),
            ("719", "Court Sq", 40.7471, -73.9456),
            ("718", "Queensboro Plaza", 40.7507, -73.9402),
            ("716", "33 St-Rawson St", 40.7448, -73.9309),
            ("715", "40 St-Lowery St", 40.7436, -73.9241),
            ("714", "46 St-Bliss St", 40.7431, -73.9185),
            ("713", "52 St", 40.7441, -73.9126),
            ("712", "61 St-Woodside", 40.7456, -73.9029),
            ("711", "69 St", 40.7462, -73.8963),
            ("710", "74 St-Broadway", 40.7469, -73.8912),
            ("709", "82 St-Jackson Hts", 40.7476, -73.8837),
            ("708", "90 St-Elmhurst Av", 40.7485, -73.8767),
            ("707", "Junction Blvd", 40.7484, -73.8695),
            ("706", "103 St-Corona Plaza", 40.7497, -73.8627),
            ("705", "111 St", 40.7517, -73.8554),
            ("702", "Mets-Willets Point", 40.7546, -73.8456),
            ("701", "Flushing-Main St", 40.7596, -73.8301),
        ],
    },
    "L": {
        "long_name": "14 St-Canarsie Local",
        "color": "A7A9AC",
        "text_color": "000000",
        "headway_min": 6,
        "path": "01R",
        "rt_trip_id_has_path": True,
        "feed": "subway-l",
        "stations": [
            ("L29", "Canarsie-Rockaway Pkwy", 40.6466, -73.9019),
            ("L28", "E 105 St", 40.6503, -73.8995),
            ("L27", "New Lots Av", 40.6589, -73.8991),
            ("L26", "Livonia Av", 40.6640, -73.9005),
            ("L25", "Sutter Av", 40.6694, -73.9017),
            ("L24", "Atlantic Av", 40.6758, -73.9030),
            ("L22", "Broadway Junction", 40.6786, -73.9032),
            ("L21", "Bushwick Av-Aberdeen St", 40.6829, -73.9051),
            ("L20", "Wilson Av", 40.6889, -73.9040),
            ("L19", "Halsey St", 40.6955, -73.9043),
            ("L17", "Myrtle-Wyckoff Avs", 40.6995, -73.9119),
            ("L16", "DeKalb Av", 40.7036, -73.9184),
            ("L15", "Jefferson St", 40.7066, -73.9229),
            ("L14", "Morgan Av", 40.7062, -73.9332),
            ("L13", "Montrose Av", 40.7074, -73.9399),
            ("L12", "Grand St", 40.7116, -73.9404),
            ("L11", "Graham Av", 40.7146, -73.9440),
            ("L10", "Lorimer St", 40.7140, -73.9503),
            ("L08", "Bedford Av", 40.7173, -73.9567),
            ("L06", "1 Av", 40.7307, -73.9815),
            ("L05", "3 Av", 40.7329, -73.9862),
            ("L03", "14 St-Union Sq", 40.7348, -73.9907),
            ("L02", "6 Av", 40.7373, -73.9969),
            ("L01", "8 Av", 40.7398, -74.0025),
        ],
    },
}

# Service span for every synthetic schedule (seconds after midnight of the service day).
SERVICE_START_S = 5 * 3600
SERVICE_END_S = 24 * 3600

# Synthetic running model: cruise speed (m/s) between stations plus a fixed dwell.
SUBWAY_SPEED_MPS = 9.0
SUBWAY_DWELL_S = 30
