package cat.tecnocampus.library.scenario;

/** Wall-clock time of several samples, in milliseconds. The median is what to compare. */
public record Timing(int samples, double medianMs, double minMs, double maxMs) {

    @Override
    public String toString() {
        return "median " + round(medianMs) + " ms (min " + round(minMs) + ", max " + round(maxMs) + ", " + samples + " samples)";
    }

    private static double round(double ms) {
        return Math.round(ms * 100) / 100.0;
    }
}
