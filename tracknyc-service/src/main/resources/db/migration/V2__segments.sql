-- Precomputed track geometry between every pair of consecutive stops.
--
-- This is what makes the position engine cheap: estimating a train is a hash lookup plus
-- arithmetic instead of a geometry query per train per tick.

CREATE TABLE segment (
    system             text    NOT NULL,
    from_stop_id       text    NOT NULL,
    to_stop_id         text    NOT NULL,
    shape_id           text,
    geom               geometry(LineString, 4326) NOT NULL,
    length_m           double precision NOT NULL,
    scheduled_run_sec  integer NOT NULL,
    PRIMARY KEY (system, from_stop_id, to_stop_id)
);
CREATE INDEX segment_geom_idx ON segment USING gist (geom);

-- Rebuilds the segments of one system from its trips, stops and shapes.
--
-- For each stop pair we locate both stops on the trip's shape (ST_LineLocatePoint) and cut the
-- shape between them (ST_LineSubstring). A shape is only trusted when both stops snap to within
-- MAX_SNAP_M of it and the stop order agrees with the shape direction; otherwise the segment
-- falls back to a straight line, which is honest about what we know.
--
-- Lengths are measured on the geography type, i.e. in metres on the spheroid.
CREATE OR REPLACE FUNCTION tracknyc_rebuild_segments(p_system text) RETURNS integer AS $$
DECLARE
    max_snap_m constant double precision := 250;
    inserted   integer;
BEGIN
    DELETE FROM segment WHERE system = p_system;

    WITH pairs AS (
        -- Every consecutive stop pair of every trip, with the scheduled run time and shape.
        SELECT DISTINCT ON (st1.stop_id, st2.stop_id)
               st1.stop_id                        AS from_stop_id,
               st2.stop_id                        AS to_stop_id,
               t.shape_id                         AS shape_id,
               GREATEST(1, st2.arrival_sec - st1.departure_sec) AS scheduled_run_sec
          FROM gtfs_stop_time st1
          JOIN gtfs_stop_time st2
            ON st2.system = st1.system
           AND st2.trip_id = st1.trip_id
           AND st2.stop_sequence = (
                 SELECT MIN(st3.stop_sequence) FROM gtfs_stop_time st3
                  WHERE st3.system = st1.system AND st3.trip_id = st1.trip_id
                    AND st3.stop_sequence > st1.stop_sequence)
          JOIN gtfs_trip t ON t.system = st1.system AND t.trip_id = st1.trip_id
         WHERE st1.system = p_system
         ORDER BY st1.stop_id, st2.stop_id, t.shape_id NULLS LAST
    ),
    located AS (
        SELECT p.from_stop_id,
               p.to_stop_id,
               p.scheduled_run_sec,
               p.shape_id,
               sh.geom                                        AS shape_geom,
               a.geom                                         AS from_geom,
               b.geom                                         AS to_geom,
               CASE WHEN sh.geom IS NULL THEN NULL ELSE ST_LineLocatePoint(sh.geom, a.geom) END AS fa,
               CASE WHEN sh.geom IS NULL THEN NULL ELSE ST_LineLocatePoint(sh.geom, b.geom) END AS fb
          FROM pairs p
          JOIN gtfs_stop a  ON a.system = p_system AND a.stop_id = p.from_stop_id
          JOIN gtfs_stop b  ON b.system = p_system AND b.stop_id = p.to_stop_id
          LEFT JOIN gtfs_shape sh ON sh.system = p_system AND sh.shape_id = p.shape_id
    ),
    cut AS (
        SELECT l.*,
               CASE
                 WHEN l.fa IS NOT NULL AND l.fb IS NOT NULL AND l.fb > l.fa
                      AND ST_Distance(ST_LineInterpolatePoint(l.shape_geom, l.fa)::geography,
                                      l.from_geom::geography) <= max_snap_m
                      AND ST_Distance(ST_LineInterpolatePoint(l.shape_geom, l.fb)::geography,
                                      l.to_geom::geography) <= max_snap_m
                 THEN ST_LineSubstring(l.shape_geom, l.fa, l.fb)
                 ELSE ST_MakeLine(l.from_geom, l.to_geom)
               END AS geom,
               (l.fa IS NOT NULL AND l.fb IS NOT NULL AND l.fb > l.fa) AS used_shape
          FROM located l
    )
    INSERT INTO segment (system, from_stop_id, to_stop_id, shape_id, geom, length_m, scheduled_run_sec)
    SELECT p_system,
           from_stop_id,
           to_stop_id,
           CASE WHEN used_shape THEN shape_id END,
           geom,
           ST_Length(geom::geography),
           scheduled_run_sec
      FROM cut
     WHERE ST_NumPoints(geom) >= 2;

    GET DIAGNOSTICS inserted = ROW_COUNT;
    RETURN inserted;
END;
$$ LANGUAGE plpgsql;
