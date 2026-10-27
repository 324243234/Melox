package android.os;

/** Allows the actual Compose runtime to execute in JVM tests without Android tracing. */
public final class Trace {
    private Trace() {}

    public static void beginSection(String sectionName) {}

    public static void endSection() {}
}
