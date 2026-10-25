package pl.ftpplayer;

/** Bounded buffering policy; bitrate is bits/second, duration milliseconds. */
public final class PlaybackBufferPolicy {
    private PlaybackBufferPolicy() {}

    public static long bitrate(long bytes, long duration) {
        if (bytes <= 0 || duration <= 0) return 0;
        return (long) Math.min(2_000_000_000d, bytes * 8000d / duration);
    }

    public static int cacheMillis(long bitrate, int profile, boolean tv) {
        int requested = profile == 1 ? 1500 : profile == 2 ? 12000
                : bitrate >= 40_000_000 ? 10000 : bitrate >= 15_000_000 ? 8000
                : bitrate >= 5_000_000 ? 5000 : 3500;
        if (bitrate <= 0 && profile != 1) {
            return profile == 2 ? (tv ? 4000 : 6000) : (tv ? 2500 : 3500);
        }
        // Pozostaw pamięć dla dekodera, powierzchni obrazu i aplikacji Python.
        long budget = (tv ? 24L : 48L) * 1024 * 1024;
        if (bitrate > 0) requested = Math.min(requested,
                (int) Math.max(1, Math.min(12000, budget * 8000 / bitrate)));
        return requested;
    }

    /** Allow for bursty demuxers, without changing the user's fast-start choice. */
    public static int initialCache(long bitrate, int profile, boolean tv, String filename) {
        int target = cacheMillis(bitrate, profile, tv);
        String name = filename == null ? "" : filename.toLowerCase(java.util.Locale.ROOT);
        boolean bursty = name.matches(".*\\.(mov|avi|wmv|mpg|mpeg|rm|rmvb|vob|ts|m2ts)$");
        if (profile == 0 && bursty) target = Math.max(target, tv ? 4500 : 6000);
        return bounded(target, bitrate, tv);
    }

    /** Increase buffering after a sustained stall, even when bitrate is unknown. */
    public static int afterStall(int current, long bitrate, int profile, boolean tv) {
        if (profile != 0) return current;
        return Math.max(current, bounded(Math.min(12000,
                Math.max(current + 3000, cacheMillis(bitrate, profile, tv))), bitrate, tv));
    }

    private static int bounded(int target, long bitrate, boolean tv) {
        long budget = (tv ? 24L : 48L) * 1024 * 1024;
        return bitrate <= 0 ? target : (int) Math.min(target, Math.max(1, budget * 8000 / bitrate));
    }
}
