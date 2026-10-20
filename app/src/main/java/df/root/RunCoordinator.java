package df.root;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** Process-wide admission and results; this class owns no Android component. */
public final class RunCoordinator {
    public interface Work {
        /** Return only after native work and resource cleanup have completed. */
        int run(IReporter reporter) throws Exception;
    }

    public static final class Snapshot {
        public final boolean running;
        public final String text;
        public final String status;
        public final long revision;

        Snapshot(boolean running, String text, String status, long revision) {
            this.running = running;
            this.text = text;
            this.status = status;
            this.revision = revision;
        }
    }

    private final Executor worker;
    private final RunLogStore logs;
    private final IReporter fallback;
    private volatile boolean running;
    private Session active;
    private long generation;
    private long revision;
    private String admissionFailure;
    private String lastSnapshotText;
    private String lastSnapshotStatus;
    private boolean lastSnapshotRunning;

    private static final class Session {
        final long id;
        volatile boolean accepting = true;
        volatile boolean begun;

        Session(long id) { this.id = id; }
    }

    public RunCoordinator(Executor worker, RunLogStore logs, IReporter fallback) {
        if (worker == null || logs == null) throw new NullPointerException();
        this.worker = worker;
        this.logs = logs;
        this.fallback = fallback;
    }

    /**
     * Never queues a duplicate. Completion runs exactly once for every request,
     * including rejected requests, and is invoked outside this object's monitor.
     * Production callers should pass only resource cleanup, never an Activity.
     */
    public boolean start(String origin, Work work, Runnable completion) {
        AtomicBoolean delivered = new AtomicBoolean();
        Runnable finishRequest = () -> {
            if (delivered.compareAndSet(false, true)) complete(completion);
        };
        final Session session;
        synchronized (this) {
            if (running) {
                session = null;
            } else {
                running = true;
                admissionFailure = null;
                session = new Session(++generation);
                active = session;
                revision++;
            }
        }
        if (session == null) {
            finishRequest.run();
            return false;
        }
        try {
            worker.execute(() -> execute(session, origin, work, finishRequest));
            return true;
        } catch (Throwable rejected) {
            synchronized (this) {
                if (active == session && running) {
                    session.accepting = false;
                    running = false;
                    admissionFailure = "Unable to start run: " + rejected;
                    revision++;
                }
            }
            safeFallback("Unable to start run: " + rejected + "\n");
            finishRequest.run();
            return false;
        }
    }

    public boolean isRunning() {
        return running;
    }

    /** Serializes settings mutations with run admission. */
    public synchronized boolean whileIdle(Runnable mutation) {
        if (running) return false;
        mutation.run();
        return true;
    }

    /** May read native output and restored logs; invoke on a background thread. */
    public Snapshot snapshot() {
        for (;;) {
            Session observed;
            boolean busy;
            boolean queued;
            synchronized (this) {
                observed = active;
                busy = running;
                queued = busy && !observed.begun;
            }
            RunLogStore.View view = queued
                    ? new RunLogStore.View("Run queued; waiting for the worker.\n", "Starting")
                    : logs.read();
            synchronized (this) {
                if (observed != active || busy != running
                        || (queued && observed.begun)) continue;
                String text = view.text;
                String status = view.status;
                if (admissionFailure != null) {
                    text += "\n" + admissionFailure + "\n";
                    status = admissionFailure;
                }
                if (!text.equals(lastSnapshotText) || !status.equals(lastSnapshotStatus)
                        || running != lastSnapshotRunning) {
                    revision++;
                    lastSnapshotText = text;
                    lastSnapshotStatus = status;
                    lastSnapshotRunning = running;
                }
                return new Snapshot(running, text, status, revision);
            }
        }
    }

    /** Includes both Java and native output from the current and previous runs. */
    public String exportText() {
        String text = logs.exportText();
        synchronized (this) {
            if (running && !active.begun) text = "Run queued; waiting for the worker.\n" + text;
            if (admissionFailure != null) text += "\n" + admissionFailure + "\n";
        }
        return text;
    }

    private void execute(Session session, String origin, Work work, Runnable completion) {
        String result;
        try {
            String diagnostic = logs.begin(origin);
            synchronized (this) {
                session.begun = true;
                revision++;
            }
            safeFallback(diagnostic);
            if (diagnostic != null) {
                result = "Startup not attempted: could not prepare this run’s log files"
                        + " (exit code 11)";
            } else {
                int code = work.run(message -> report(session, message));
                result = resultFor(code);
            }
        } catch (Throwable failure) {
            result = "Run failed: " + failure;
            safeFallback(result + "\n");
        }
        try {
            String diagnostic;
            synchronized (session) {
                session.accepting = false;
                diagnostic = logs.finish(result);
            }
            synchronized (this) {
                revision++;
            }
            safeFallback(result + "\n");
            safeFallback(diagnostic);
        } catch (Throwable loggingFailure) {
            safeFallback("Unable to record terminal result: " + loggingFailure + "\n");
        } finally {
            synchronized (this) {
                if (active == session) {
                    session.accepting = false;
                    running = false;
                    revision++;
                }
            }
            complete(completion);
        }
    }

    private void report(Session session, String message) {
        String diagnostic;
        synchronized (session) {
            if (!session.accepting) return;
            diagnostic = logs.append(message);
        }
        synchronized (this) {
            revision++;
        }
        safeFallback(message);
        safeFallback(diagnostic);
    }

    private void complete(Runnable callback) {
        if (callback == null) return;
        try {
            callback.run();
        } catch (Throwable failure) {
            safeFallback("Run completion callback failed: " + failure + "\n");
        }
    }

    private void safeFallback(String message) {
        if (fallback == null || message == null || message.isEmpty()) return;
        try {
            fallback.report(message);
        } catch (Throwable ignored) {
            // Diagnostics must not interrupt native execution or its cleanup.
        }
    }

    static String resultFor(int code) {
        switch (code) {
            case 0: return "Launch completed; root unverified (exit code 0)";
            case 1: return "Bootstrap failed (exit code 1)";
            case 2: return "Run incomplete (exit code 2)";
            case 3: return "Patch failure (exit code 3)";
            case 4: return "Cleanup failed (exit code 4)";
            case 10: return "Already attempted; outcome unverified (exit code 10)";
            case 11: return "Preflight failed (exit code 11)";
            default: return "Run exited with code " + code + "; outcome unverified";
        }
    }
}
