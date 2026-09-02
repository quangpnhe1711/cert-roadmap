package com.certcopilot.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * Prints every skipped test and why, at the end of each run.
 *
 * <p>"2 skipped" in a build log is not a test report. It is indistinguishable
 * from "the two tests that would have caught this were not run", and the whole
 * point of a verification run is to be able to tell those apart. So each skip is
 * named, with its reason, every time - including assumption aborts, which JUnit
 * reports as a separate outcome from a declarative skip.
 *
 * <p>Registered through {@code META-INF/services}, so it applies to Surefire,
 * Failsafe and an IDE alike without anything opting in.
 */
public class SkipReportListener implements TestExecutionListener {

    private final List<String> skipped = new CopyOnWriteArrayList<>();

    @Override
    public void executionSkipped(TestIdentifier identifier, String reason) {
        if (identifier.isTest()) {
            record(identifier, reason);
        }
    }

    @Override
    public void executionFinished(TestIdentifier identifier, TestExecutionResult result) {
        // An assumption that fails mid-test is an abort, not a skip, but to anyone
        // reading the build log it is the same thing: the test did not run.
        if (identifier.isTest() && result.getStatus() == TestExecutionResult.Status.ABORTED) {
            record(identifier, result.getThrowable().map(Throwable::getMessage).orElse("aborted"));
        }
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        if (skipped.isEmpty()) {
            return;
        }
        StringBuilder out = new StringBuilder()
                .append(System.lineSeparator())
                .append("SKIPPED TESTS (").append(skipped.size()).append(')')
                .append(System.lineSeparator());
        skipped.forEach(line -> out.append("  - ").append(line).append(System.lineSeparator()));
        System.out.println(out);
    }

    private void record(TestIdentifier identifier, String reason) {
        String where = identifier.getSource()
                .map(Object::toString)
                .map(SkipReportListener::shorten)
                .orElseGet(identifier::getDisplayName);
        skipped.add(where + " - " + (reason == null || reason.isBlank() ? "no reason given" : reason));
    }

    /** Turns a verbose MethodSource into {@code Class.method}. */
    private static String shorten(String source) {
        String className = between(source, "className = '", "'");
        String methodName = between(source, "methodName = '", "'");
        if (className == null || methodName == null) {
            return source;
        }
        return className.substring(className.lastIndexOf('.') + 1) + "." + methodName;
    }

    private static String between(String text, String prefix, String suffix) {
        int start = text.indexOf(prefix);
        if (start < 0) {
            return null;
        }
        int from = start + prefix.length();
        int end = text.indexOf(suffix, from);
        return end < 0 ? null : text.substring(from, end);
    }
}
