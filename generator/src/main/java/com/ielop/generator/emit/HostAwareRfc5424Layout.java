package com.ielop.generator.emit;

import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Node;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginConfiguration;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.layout.AbstractStringLayout;
import org.apache.logging.log4j.core.layout.Rfc5424Layout;
import org.apache.logging.log4j.core.net.Facility;

import java.nio.charset.StandardCharsets;

/**
 * Format C layout: Log4j2's own {@link Rfc5424Layout}, with the HOSTNAME and
 * PROCID header fields taken from the event's context data ({@code host},
 * {@code procid}) instead of the machine running the generator.
 *
 * <p>{@code Rfc5424Layout} always writes the local host name and the real
 * process ID, which would make the output differ between machines and runs
 * (GEN-001). Everything else (PRI, timestamp, APP-NAME, MSGID, structured data,
 * escaping) is the unmodified Log4j2 output.</p>
 */
@Plugin(name = "HostAwareRfc5424Layout", category = Node.CATEGORY, elementType = Layout.ELEMENT_TYPE, printObject = true)
public final class HostAwareRfc5424Layout extends AbstractStringLayout {

    private final Rfc5424Layout delegate;

    private HostAwareRfc5424Layout(Configuration config, Rfc5424Layout delegate) {
        super(config, StandardCharsets.UTF_8, null, null);
        this.delegate = delegate;
    }

    @PluginFactory
    public static HostAwareRfc5424Layout createLayout(
            @PluginAttribute(value = "facility", defaultString = "AUTHPRIV") String facility,
            @PluginAttribute("appName") String appName,
            @PluginAttribute(value = "enterpriseNumber", defaultString = "32473") String enterpriseNumber,
            @PluginAttribute(value = "id", defaultString = "acme") String id,
            @PluginConfiguration Configuration config) {
        Rfc5424Layout rfc = Rfc5424Layout.newBuilder()
                .setConfig(config)
                .setFacility(Facility.toFacility(facility))
                .setAppName(appName)
                .setEin(enterpriseNumber)
                .setId(id)
                .setIncludeMDC(false)
                .setIncludeNL(true)
                .build();
        return new HostAwareRfc5424Layout(config, rfc);
    }

    @Override
    public String toSerializable(LogEvent event) {
        String line = delegate.toSerializable(event);
        // <PRI>1 TIMESTAMP HOSTNAME APP-NAME PROCID ...
        int s1 = line.indexOf(' ');
        int s2 = line.indexOf(' ', s1 + 1);
        int s3 = line.indexOf(' ', s2 + 1);
        int s4 = line.indexOf(' ', s3 + 1);
        int s5 = line.indexOf(' ', s4 + 1);
        if (s5 < 0) {
            return line;
        }
        String host = valueOr(event, "host", "-");
        String procId = valueOr(event, "procid", "-");
        return line.substring(0, s2 + 1) + host + line.substring(s3, s4 + 1) + procId + line.substring(s5);
    }

    private static String valueOr(LogEvent event, String key, String fallback) {
        Object value = event.getContextData().getValue(key);
        return value != null ? value.toString() : fallback;
    }
}
