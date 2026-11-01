-- TrackNYC static GTFS schema (PostgreSQL 16 + PostGIS).
-- Every table is keyed by `system` so the subway, LIRR, ... can be reloaded independently.

CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE gtfs_feed_version (
    system       text PRIMARY KEY,
    fingerprint  text        NOT NULL,
    source       text        NOT NULL,
    timezone     text        NOT NULL DEFAULT 'America/New_York',
    stop_count   integer     NOT NULL DEFAULT 0,
    trip_count   integer     NOT NULL DEFAULT 0,
    loaded_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE gtfs_route (
    system      text    NOT NULL,
    route_id    text    NOT NULL,
    short_name  text,
    long_name   text,
    route_type  integer NOT NULL DEFAULT 1,
    color       char(6) NOT NULL DEFAULT '808183',
    text_color  char(6) NOT NULL DEFAULT 'FFFFFF',
    PRIMARY KEY (system, route_id)
);

CREATE TABLE gtfs_stop (
    system          text    NOT NULL,
    stop_id         text    NOT NULL,
    name            text,
    location_type   integer NOT NULL DEFAULT 0,
    parent_station  text,
    geom            geometry(Point, 4326) NOT NULL,
    PRIMARY KEY (system, stop_id)
);
CREATE INDEX gtfs_stop_geom_idx ON gtfs_stop USING gist (geom);
CREATE INDEX gtfs_stop_parent_idx ON gtfs_stop (system, parent_station);

CREATE TABLE gtfs_shape (
    system    text NOT NULL,
    shape_id  text NOT NULL,
    geom      geometry(LineString, 4326) NOT NULL,
    PRIMARY KEY (system, shape_id)
);
CREATE INDEX gtfs_shape_geom_idx ON gtfs_shape USING gist (geom);

CREATE TABLE gtfs_calendar (
    system      text    NOT NULL,
    service_id  text    NOT NULL,
    monday      boolean NOT NULL,
    tuesday     boolean NOT NULL,
    wednesday   boolean NOT NULL,
    thursday    boolean NOT NULL,
    friday      boolean NOT NULL,
    saturday    boolean NOT NULL,
    sunday      boolean NOT NULL,
    start_date  date    NOT NULL,
    end_date    date    NOT NULL,
    PRIMARY KEY (system, service_id)
);

CREATE TABLE gtfs_calendar_date (
    system          text     NOT NULL,
    service_id      text     NOT NULL,
    date            date     NOT NULL,
    exception_type  smallint NOT NULL,
    PRIMARY KEY (system, service_id, date)
);

CREATE TABLE gtfs_trip (
    system        text     NOT NULL,
    trip_id       text     NOT NULL,
    route_id      text     NOT NULL,
    service_id    text     NOT NULL,
    headsign      text,
    direction_id  smallint NOT NULL DEFAULT 0,
    shape_id      text,
    PRIMARY KEY (system, trip_id)
);
CREATE INDEX gtfs_trip_route_idx ON gtfs_trip (system, route_id);

CREATE TABLE gtfs_stop_time (
    system         text    NOT NULL,
    trip_id        text    NOT NULL,
    stop_sequence  integer NOT NULL,
    stop_id        text    NOT NULL,
    arrival_sec    integer NOT NULL,
    departure_sec  integer NOT NULL,
    PRIMARY KEY (system, trip_id, stop_sequence)
);
CREATE INDEX gtfs_stop_time_stop_idx ON gtfs_stop_time (system, stop_id);
