package com.ielop.generator.emit;

/** Receives every emitted event with the file and first line number it was written to. */
@FunctionalInterface
public interface LineSink {

    void accept(Event event, String file, int lineNo);

    LineSink NONE = (event, file, lineNo) -> { };
}
