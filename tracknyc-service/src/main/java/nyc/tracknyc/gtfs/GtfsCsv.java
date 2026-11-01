package nyc.tracknyc.gtfs;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Streaming RFC 4180 CSV reader for GTFS text files: quoted fields, doubled quotes,
 * embedded commas/newlines, CRLF line endings and a UTF-8 BOM are all handled. Rows are
 * delivered one at a time so even the full subway stop_times.txt never sits in memory.
 */
public final class GtfsCsv {

    private GtfsCsv() {
    }

    /** One data row with column lookup by header name. */
    public static final class Row {
        private final Map<String, Integer> header;
        private final List<String> values;
        private final long lineNumber;

        Row(Map<String, Integer> header, List<String> values, long lineNumber) {
            this.header = header;
            this.values = values;
            this.lineNumber = lineNumber;
        }

        /** Trimmed value, or "" if the column is absent or the row is short. */
        public String get(String column) {
            Integer i = header.get(column);
            if (i == null || i >= values.size()) {
                return "";
            }
            return values.get(i).trim();
        }

        public String require(String column) {
            String v = get(column);
            if (v.isEmpty()) {
                throw new GtfsFormatException("missing required '" + column + "' on line " + lineNumber);
            }
            return v;
        }

        public int getInt(String column, int dflt) {
            String v = get(column);
            if (v.isEmpty()) {
                return dflt;
            }
            try {
                return Integer.parseInt(v);
            } catch (NumberFormatException e) {
                throw new GtfsFormatException("bad integer '" + v + "' in '" + column + "' on line " + lineNumber);
            }
        }

        public double requireDouble(String column) {
            String v = require(column);
            try {
                return Double.parseDouble(v);
            } catch (NumberFormatException e) {
                throw new GtfsFormatException("bad number '" + v + "' in '" + column + "' on line " + lineNumber);
            }
        }

        public boolean has(String column) {
            return header.containsKey(column);
        }

        public long lineNumber() {
            return lineNumber;
        }
    }

    public static long read(InputStream in, Consumer<Row> consumer) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16);
        List<String> headerFields = parseRecord(r);
        if (headerFields == null) {
            return 0;
        }
        Map<String, Integer> header = new HashMap<>();
        for (int i = 0; i < headerFields.size(); i++) {
            String h = headerFields.get(i).trim();
            if (i == 0 && !h.isEmpty() && h.charAt(0) == '﻿') {
                h = h.substring(1);
            }
            header.put(h, i);
        }
        long count = 0;
        List<String> fields;
        while ((fields = parseRecord(r)) != null) {
            if (fields.size() == 1 && fields.get(0).isEmpty()) {
                continue; // blank line
            }
            count++;
            consumer.accept(new Row(header, fields, count + 1));
        }
        return count;
    }

    /** Parses one logical record (which may span physical lines); null at EOF. */
    static List<String> parseRecord(BufferedReader r) throws IOException {
        int c = r.read();
        if (c == -1) {
            return null;
        }
        List<String> fields = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean quoted = false;
        while (true) {
            if (quoted) {
                if (c == -1) {
                    throw new GtfsFormatException("unterminated quoted field");
                }
                if (c == '"') {
                    r.mark(1);
                    int n = r.read();
                    if (n == '"') {
                        sb.append('"');
                    } else {
                        quoted = false;
                        if (n != -1) {
                            r.reset();
                        }
                    }
                } else {
                    sb.append((char) c);
                }
            } else {
                if (c == -1 || c == '\n') {
                    fields.add(sb.toString());
                    return fields;
                }
                if (c == '\r') {
                    r.mark(1);
                    int n = r.read();
                    if (n != '\n' && n != -1) {
                        r.reset();
                    }
                    fields.add(sb.toString());
                    return fields;
                }
                if (c == ',') {
                    fields.add(sb.toString());
                    sb.setLength(0);
                } else if (c == '"' && sb.length() == 0) {
                    quoted = true;
                } else {
                    sb.append((char) c);
                }
            }
            c = r.read();
        }
    }
}
