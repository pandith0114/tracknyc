package nyc.tracknyc;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock tests can move by hand. */
public final class TestClock extends Clock {
    public Instant now;

    public TestClock(Instant now) {
        this.now = now;
    }

    public void advance(Duration d) {
        now = now.plus(d);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId z) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
