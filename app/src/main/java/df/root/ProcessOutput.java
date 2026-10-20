package df.root;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Keeps ownership of a child until it exits, even when reporting its output fails.
 * This deliberately has no execution timeout: killing the child can bypass its
 * native cleanup. Only output draining after a confirmed exit is time limited.
 */
final class ProcessOutput {
    private static final int MAX_MESSAGE_CHARS = 4096;
    private static final long DRAIN_NANOS = TimeUnit.SECONDS.toNanos(2);

    private ProcessOutput() {}

    static final class Failure extends Exception {
        private static final long serialVersionUID = 1L;
        final int exitCode;

        Failure(int exitCode, Exception cause) {
            super("Child exited with status " + exitCode
                    + ", but process supervision failed ("
                    + cause.getClass().getSimpleName() + ")", cause);
            this.exitCode = exitCode;
        }
    }

    static int waitFor(Process process, IReporter reporter) throws Exception {
        OutputState state = new OutputState(reporter);
        InputStream output = process.getInputStream();
        Thread reader = new Thread(() -> drain(output, state), "dfroot-output");
        reader.setDaemon(true);
        reader.start();
        closeLater(process.getOutputStream(), "stdin");

        boolean interrupted = false;
        int exitCode;
        for (;;) {
            try {
                exitCode = process.waitFor();
                break;
            } catch (InterruptedException e) {
                if (!interrupted) state.fail(e);
                interrupted = true;
            }
        }

        long deadline = System.nanoTime() + DRAIN_NANOS;
        while (reader.isAlive()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            try {
                TimeUnit.NANOSECONDS.timedJoin(reader, remaining);
            } catch (InterruptedException e) {
                if (!interrupted) state.fail(e);
                interrupted = true;
            }
        }
        if (reader.isAlive()) {
            state.fail(new IOException("Child exited with status " + exitCode
                    + ", but output did not finish draining within 2 seconds"));
            state.stopped.set(true);
        }
        state.reporter.set(null);

        // A reader or stream implementation may hold a lock while blocked.
        // Neither close nor an unlimited join belongs on the owning thread.
        closeLater(output, "stdout");
        closeLater(process.getErrorStream(), "stderr");

        if (interrupted) Thread.currentThread().interrupt();
        Exception failure = state.failure.get();
        if (failure != null) throw new Failure(exitCode, failure);
        return exitCode;
    }

    private static void drain(InputStream output, OutputState state) {
        Reader reader = new InputStreamReader(output, StandardCharsets.UTF_8);
        char[] buffer = new char[1024];
        StringBuilder message = new StringBuilder(MAX_MESSAGE_CHARS);
        try {
            int count;
            while (!state.stopped.get() && (count = reader.read(buffer)) != -1) {
                for (int i = 0; i < count; i++) {
                    if (state.stopped.get()) return;
                    char c = buffer[i];
                    message.append(c);
                    if (c == '\n' || message.length() == MAX_MESSAGE_CHARS) {
                        state.report(message.toString());
                        message.setLength(0);
                    }
                }
            }
            if (!state.stopped.get() && message.length() != 0) {
                state.report(message.toString());
            }
        } catch (IOException | RuntimeException e) {
            if (!state.stopped.get()) state.fail(e);
        }
        // The owner closes the stream after child exit. Closing it here while
        // the child is alive could make a native stdout write fail or signal.
    }

    private static void closeLater(Closeable stream, String name) {
        Thread closer = new Thread(() -> {
            try {
                stream.close();
            } catch (IOException | RuntimeException e) {
                // Closing stdin is best effort, and close errors must not hide
                // a child exit status or an earlier reporting/read failure.
                System.err.println("ProcessOutput: could not close child " + name
                        + " (" + e.getClass().getSimpleName() + ")");
            }
        }, "dfroot-close-" + name);
        closer.setDaemon(true);
        closer.start();
    }

    private static final class OutputState {
        final AtomicReference<IReporter> reporter;
        final AtomicReference<Exception> failure = new AtomicReference<>();
        final AtomicBoolean stopped = new AtomicBoolean();

        OutputState(IReporter reporter) {
            this.reporter = new AtomicReference<>(reporter);
        }

        void report(String message) {
            IReporter target = reporter.get();
            if (target == null) return;
            try {
                target.report(message);
            } catch (RuntimeException e) {
                reporter.compareAndSet(target, null);
                fail(e);
                // Continue draining so a failed reporter cannot fill the pipe.
            }
        }

        void fail(Exception error) {
            if (failure.compareAndSet(null, error)) return;
            Exception first = failure.get();
            if (first != error) first.addSuppressed(error);
        }
    }
}
