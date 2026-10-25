package com.ielop.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Generated logs parsed back into simple records, one list per format, for assertions. */
final class LogCorpus {

    record Access(String ip, String user, String method, String path, int status, String userAgent, String rid, String line) {
    }

    record Syslog(String msgId, Map<String, String> sd, String message, String line) {
    }

    record Billing(String level, String thread, String requestId, String logger, String text) {
    }

    static final Pattern ACCESS = Pattern.compile(
            "^(\\S+) - (\\S+) \\[[^]]+] \"(\\S+) (\\S+) HTTP/1\\.1\" (\\d{3}) \\S+ \"[^\"]*\" \"([^\"]*)\" rid=(\\S+) rt=\\S+$");
    static final Pattern SYSLOG = Pattern.compile("^<\\d+>1 \\S+ \\S+ \\S+ \\S+ (\\S+) (-|\\[\\S+((?: \\w+=\"[^\"]*\")*)]) (.*)$");
    static final Pattern SD_PARAM = Pattern.compile("(\\w+)=\"([^\"]*)\"");
    static final Pattern BILLING = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2},\\d{3} \\| (\\S+)\\s*\\| \\[([^\\]]+)] \\| (\\S*) \\| (\\S+?):\\S+ \\| ");

    final List<Access> access = new ArrayList<>();
    final List<JsonNode> workflow = new ArrayList<>();
    final List<Syslog> auth = new ArrayList<>();
    final List<Billing> billing = new ArrayList<>();

    static LogCorpus read(Path logs) throws IOException {
        LogCorpus c = new LogCorpus();
        for (String line : GeneratorRun.lines(logs, "gateway")) {
            Matcher m = ACCESS.matcher(line);
            if (!m.matches()) {
                throw new IllegalStateException("Unparsable access line: " + line);
            }
            c.access.add(new Access(m.group(1), m.group(2), m.group(3), m.group(4), Integer.parseInt(m.group(5)),
                    m.group(6), m.group(7), line));
        }
        ObjectMapper json = new ObjectMapper();
        for (String line : GeneratorRun.lines(logs, "workflow-service")) {
            c.workflow.add(json.readTree(line));
        }
        for (String line : GeneratorRun.lines(logs, "auth-service")) {
            Matcher m = SYSLOG.matcher(line);
            if (!m.matches()) {
                throw new IllegalStateException("Unparsable syslog line: " + line);
            }
            Map<String, String> sd = new LinkedHashMap<>();
            if (m.group(3) != null) {
                Matcher p = SD_PARAM.matcher(m.group(3));
                while (p.find()) {
                    sd.put(p.group(1), p.group(2));
                }
            }
            c.auth.add(new Syslog(m.group(1), sd, m.group(4), line));
        }
        for (Path file : GeneratorRun.files(logs.resolve("billing-service"))) {
            Billing current = null;
            StringBuilder text = null;
            for (String line : java.nio.file.Files.readAllLines(file)) {
                Matcher m = BILLING.matcher(line);
                if (m.find()) {
                    if (current != null) {
                        c.billing.add(new Billing(current.level(), current.thread(), current.requestId(), current.logger(), text.toString()));
                    }
                    current = new Billing(m.group(1), m.group(2), m.group(3), m.group(4), null);
                    text = new StringBuilder(line.substring(m.end()));
                } else if (text != null) {
                    text.append('\n').append(line);
                }
            }
            if (current != null) {
                c.billing.add(new Billing(current.level(), current.thread(), current.requestId(), current.logger(), text.toString()));
            }
        }
        return c;
    }
}
