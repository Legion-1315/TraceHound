package com.db.estate.ops;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.AppenderBase;

import java.time.Instant;

/**
 * Captures this service's application logs (plus WARN+ from frameworks) into {@link LogBuffer},
 * which {@code GET /ops/logs} serves. This is the service's real log stream, not a generated one.
 */
public class RingBufferAppender extends AppenderBase<ILoggingEvent> {

    @Override
    protected void append(ILoggingEvent event) {
        boolean ours = event.getLoggerName().startsWith("com.db.estate");
        if (!ours && !event.getLevel().isGreaterOrEqual(Level.WARN)) {
            return;
        }
        if (event.getLoggerName().startsWith("com.db.estate.ops")) {
            return; // telemetry plumbing is not part of the service's story
        }
        String message = event.getFormattedMessage();
        IThrowableProxy t = event.getThrowableProxy();
        if (t != null) {
            message = message + " | " + t.getClassName() + ": " + t.getMessage();
        }
        String corr = event.getMDCPropertyMap().getOrDefault("corr", "-");
        LogBuffer.add(new LogBuffer.LogRecord(Instant.ofEpochMilli(event.getTimeStamp()),
                event.getLevel().toString(), corr, shortLogger(event.getLoggerName()), message));
    }

    private static String shortLogger(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? name : name.substring(i + 1);
    }
}
