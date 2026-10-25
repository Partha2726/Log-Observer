package com.ielop.platform.logsearch;

/** ECS field names written by the ingestion pipeline (spec sections 6.3 and 6.4). */
public final class LogFields {

    public static final String TIMESTAMP = "@timestamp";
    public static final String INGESTED_AT = "ingested_at";
    public static final String SERVICE = "service.name";
    public static final String HOST = "host.name";
    public static final String LOG_FORMAT = "log_format";
    public static final String LEVEL = "log.level";
    public static final String LOGGER = "log.logger";
    public static final String THREAD = "process.thread.name";
    public static final String FILE_PATH = "log.file.path";
    public static final String OFFSET = "log.offset";
    public static final String FLOW_REFS = "flow.refs";
    public static final String USER_ID = "user.id";
    public static final String SESSION_ID = "session.id";
    public static final String REQUEST_ID = "http.request.id";
    public static final String EVENT_ACTION = "event.action";
    public static final String CLIENT_IP = "client.ip";
    public static final String HTTP_METHOD = "http.request.method";
    public static final String URL_PATH = "url.path";
    public static final String HTTP_STATUS = "http.response.status_code";
    public static final String USER_AGENT = "user_agent.original";
    public static final String ERROR_TYPE = "error.type";
    public static final String IS_ERROR = "is_error";
    public static final String IS_WARNING = "is_warning";
    public static final String MESSAGE_BODY = "message_body";
    public static final String MESSAGE = "message";
    public static final String RESOURCE = "flow.resource";
    public static final String TAGS = "tags";
    /** OpenTelemetry (phase 3). */
    public static final String TRACE_ID = "trace.id";
    public static final String SPAN_ID = "span.id";

    private LogFields() {
    }
}
