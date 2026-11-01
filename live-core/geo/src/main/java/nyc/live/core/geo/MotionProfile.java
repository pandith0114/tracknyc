package nyc.live.core.geo;

/**
 * Maps "fraction of the scheduled run time elapsed" to "fraction of the distance covered".
 * LINEAR assumes constant speed; TRAPEZOIDAL models acceleration out of a station and
 * braking into the next one, which looks much more like a real train.
 */
public sealed interface MotionProfile permits MotionProfile.Linear, MotionProfile.Trapezoidal {

    double distanceFraction(double timeFraction);

    MotionProfile LINEAR = new Linear();

    static MotionProfile trapezoidal(double accelShare) {
        return new Trapezoidal(accelShare);
    }

    static MotionProfile parse(String name) {
        if (name == null || name.isBlank() || name.equalsIgnoreCase("linear")) {
            return LINEAR;
        }
        if (name.equalsIgnoreCase("trapezoidal")) {
            return trapezoidal(0.25);
        }
        throw new IllegalArgumentException("unknown motion profile: " + name);
    }

    record Linear() implements MotionProfile {
        @Override
        public double distanceFraction(double t) {
            return GeoMath.clamp(t, 0, 1);
        }
    }

    /**
     * Speed ramps up linearly for {@code accelShare} of the time, cruises, then ramps down for
     * the same share. Total distance is normalised to 1.
     */
    record Trapezoidal(double accelShare) implements MotionProfile {
        public Trapezoidal {
            if (accelShare <= 0 || accelShare >= 0.5) {
                throw new IllegalArgumentException("accelShare must be in (0, 0.5)");
            }
        }

        @Override
        public double distanceFraction(double time) {
            double t = GeoMath.clamp(time, 0, 1);
            double a = accelShare;
            double vMax = 1.0 / (1.0 - a);
            if (t < a) {
                return 0.5 * vMax * t * t / a;
            }
            if (t <= 1 - a) {
                return 0.5 * vMax * a + vMax * (t - a);
            }
            double r = 1 - t;
            return 1 - 0.5 * vMax * r * r / a;
        }
    }
}
