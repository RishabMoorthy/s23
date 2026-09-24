package org.framework.config;

/**
 * The execution modes a virtual service can run in.
 *
 * <pre>
 *   Stand-In         always return the mock response; the destination server is never called.
 *   Live Invocation  always call the destination server and return its response; the mock
 *                    response is not considered.
 *   Failover         call the destination server. If the call succeeds, return the server's
 *                    response. If the call fails, return the mock response.
 *   (none)           no mode configured - the service behaves as an ordinary virtual service,
 *                    exactly as Stand-In does.
 * </pre>
 *
 * <p>{@link #from(String)} is deliberately forgiving: the value arrives from a hand-edited .vs
 * file and from a database column, so "Stand In", "Stand-In", "stand_in" and stray whitespace
 * all resolve to the same mode. Anything unrecognised - including a blank element, which is
 * how an unset mode is written - resolves to {@link #NONE}, so an unknown string can never
 * accidentally start routing traffic to a live server.
 */
public enum ExecutionModeType {

    /** No mode configured. Serves the virtual service response, like Stand-In. */
    NONE(""),

    /** Never calls the destination server. */
    STAND_IN("Stand In"),

    /** Always calls the destination server and returns its response. */
    LIVE_INVOCATION("Live Invocation"),

    /** Calls the destination server; on failure returns the mock response instead. */
    FAILOVER("Failover");

    private final String value;

    ExecutionModeType(String value) {
        this.value = value;
    }

    /** The canonical spelling written to the .vs file and the database. */
    public String value() {
        return value;
    }

    public static ExecutionModeType from(String raw) {

        if (raw == null) {
            return NONE;
        }

        String key = raw.trim()
                .toLowerCase()
                .replace('-', ' ')
                .replace('_', ' ')
                .replaceAll("\\s+", " ");

        return switch (key) {
            case "stand in", "standin" -> STAND_IN;
            case "live invocation", "liveinvocation" -> LIVE_INVOCATION;
            case "failover", "fail over" -> FAILOVER;
            default -> NONE;
        };
    }

    /** True when this mode calls the destination server at all. */
    public boolean callsLiveServer() {
        return this == LIVE_INVOCATION || this == FAILOVER;
    }

    /** True when a failed live call should be answered with the mock response. */
    public boolean fallsBackToMock() {
        return this == FAILOVER;
    }

    /**
     * True when a LiveURL is meaningful for this mode. Stand-In and an unset mode carry an
     * empty {@code <LiveURLs/>} element in the .vs file.
     */
    public boolean usesLiveUrls() {
        return callsLiveServer();
    }
}
