package nyc.tracknyc.network;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import nyc.tracknyc.gtfs.GtfsRows.CalendarDateRow;
import nyc.tracknyc.gtfs.GtfsRows.CalendarRow;

/** calendar.txt + calendar_dates.txt: is a service id running on a given date? */
public final class ServiceCalendar {

    private final Map<String, CalendarRow> weekly = new HashMap<>();
    private final Map<String, Map<LocalDate, Integer>> exceptions = new HashMap<>();

    public void add(CalendarRow row) {
        weekly.put(row.serviceId(), row);
    }

    public void add(CalendarDateRow row) {
        exceptions.computeIfAbsent(row.serviceId(), k -> new HashMap<>()).put(row.date(), row.exceptionType());
    }

    public boolean isActive(String serviceId, LocalDate date) {
        Integer ex = exceptions.getOrDefault(serviceId, Map.of()).get(date);
        if (ex != null) {
            return ex == 1;
        }
        CalendarRow c = weekly.get(serviceId);
        if (c == null) {
            return false;
        }
        if (date.isBefore(c.start()) || date.isAfter(c.end())) {
            return false;
        }
        return c.days()[date.getDayOfWeek().getValue() - 1];
    }

    /** Feeds without calendar files (rare, but seen in hand-made subsets) are treated as always on. */
    public boolean isEmpty() {
        return weekly.isEmpty() && exceptions.isEmpty();
    }
}
