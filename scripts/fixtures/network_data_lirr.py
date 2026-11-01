"""
Hand-entered LIRR data for the SYNTHETIC fixtures.

As with the subway data: coordinates are APPROXIMATE, hand-entered, and exist only so the
replay demo has something plausible to draw. Station names follow the real branch, but stop
ids, trip ids and the timetable are invented. Replace with the real MTA LIRR GTFS download
(scripts/fetch_real_data.sh).
"""

# One branch is enough to exercise the second system: Penn Station - Babylon, the LIRR's
# busiest, with the two express patterns it actually runs (all-stops and skip-stop).
# Tuples: (stop_id, name, lat, lon) -- coordinates APPROXIMATE.
LIRR_STATIONS = [
    ("237", "Penn Station", 40.7506, -73.9935),
    ("102", "Atlantic Terminal", 40.6840, -73.9770),
    ("214", "Nostrand Av", 40.6774, -73.9500),
    ("128", "East New York", 40.6786, -73.8997),
    ("129", "Jamaica", 40.7004, -73.8095),
    ("188", "Rosedale", 40.6630, -73.7380),
    ("245", "Valley Stream", 40.6640, -73.7090),
    ("153", "Lynbrook", 40.6580, -73.6720),
    ("190", "Rockville Centre", 40.6590, -73.6400),
    ("13", "Baldwin", 40.6560, -73.6100),
    ("103", "Freeport", 40.6570, -73.5830),
    ("162", "Merrick", 40.6580, -73.5520),
    ("12", "Bellmore", 40.6670, -73.5270),
    ("245B", "Wantagh", 40.6790, -73.5100),
    ("210", "Seaford", 40.6700, -73.4900),
    ("152", "Massapequa", 40.6670, -73.4740),
    ("151", "Massapequa Park", 40.6800, -73.4490),
    ("11", "Amityville", 40.6790, -73.4170),
    ("49", "Copiague", 40.6800, -73.3980),
    ("147", "Lindenhurst", 40.6870, -73.3740),
    ("10", "Babylon", 40.6980, -73.3250),
]

# Express pattern: stations an express train actually stops at (index into LIRR_STATIONS).
LIRR_EXPRESS_STOPS = [0, 1, 4, 7, 10, 13, 16, 20]

LIRR_ROUTE_ID = "10"
LIRR_ROUTE_NAME = "Babylon Branch"
LIRR_COLOR = "00985F"

# Peak-direction headways, in minutes, by pattern.
LIRR_LOCAL_HEADWAY_MIN = 30
LIRR_EXPRESS_HEADWAY_MIN = 30

# Synthetic running model.
LIRR_SPEED_MPS = 22.0
LIRR_EXPRESS_SPEED_MPS = 28.0
LIRR_DWELL_S = 60
