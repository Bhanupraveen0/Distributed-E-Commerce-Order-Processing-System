package com.ecommerce;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Thread-safe event logging utility for distributed order processing.
 * Logs to standard output and retains an in-memory buffer for the Web Dashboard.
 */
public class EventLogger {
    public static class LogEntry {
        public final String time;
        public final String source;
        public final String message;
        public final String level; // INFO, SUCCESS, FAIL, COMPENSATION

        public LogEntry(String time, String source, String message, String level) {
            this.time = time;
            this.source = source;
            this.message = message;
            this.level = level;
        }

        public String toJson() {
            return String.format(
                "{\"time\":\"%s\",\"source\":\"%s\",\"message\":\"%s\",\"level\":\"%s\"}",
                escapeJson(time), escapeJson(source), escapeJson(message), escapeJson(level)
            );
        }
    }

    private static final ConcurrentLinkedDeque<LogEntry> logs = new ConcurrentLinkedDeque<>();
    private static final int MAX_LOGS = 250;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    public static void log(String source, String message, String level) {
        String time = LocalTime.now().format(TIME_FORMAT);
        LogEntry entry = new LogEntry(time, source, message, level);
        logs.addLast(entry);
        while (logs.size() > MAX_LOGS) {
            logs.pollFirst();
        }
        System.out.println("[" + source + "] " + message);
    }

    public static void info(String source, String message) {
        log(source, message, "INFO");
    }

    public static void success(String source, String message) {
        log(source, message, "SUCCESS");
    }

    public static void fail(String source, String message) {
        log(source, message, "FAIL");
    }

    public static void compensation(String source, String message) {
        log(source, message, "COMPENSATION");
    }

    public static List<LogEntry> getLogs() {
        return new ArrayList<>(logs);
    }

    public static void clear() {
        logs.clear();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
