package df.root;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class RunCoordinatorTest {
    private static int assertions;
    private static final List<Path> temporary = new ArrayList<>();
    private static final List<String> failedAssertions = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        try {
            lazyConstructionAndQueuedAdmission();
            concurrentAdmissionAndCompletion();
            settingsAreSerializedWithAdmission();
            executorRejectionAndCallbacks();
            lateReportersCannotCorruptRuns();
            retentionExportAndReopen();
            unicodeBoundsAndTerminalReserve();
            liveMemorySurvivesShortOrFailedDiskWrites();
            generationFailurePreservesPrevious();
            failedGenerationDoesNotTouchPreviousNativeLog();
            incompleteTerminalAndLaunchCheckpoint();
            interruptedRotationRecovery();
            admissionDoesNotWaitForDiskReads();
            terminalStatusesAndFailures();
            check(failedAssertions.isEmpty(),
                    "assertions inside asynchronous work must also pass: " + failedAssertions);
            System.out.println("RunCoordinator tests passed (" + assertions + " assertions)");
        } finally {
            for (Path root : temporary) deleteTree(root);
        }
    }

    private static void lazyConstructionAndQueuedAdmission() throws Exception {
        Path files = directory();
        QueuedExecutor executor = new QueuedExecutor();
        RunLogStore store = new RunLogStore(files.toFile());
        RunCoordinator runs = new RunCoordinator(executor, store, null);
        check(!Files.exists(files.resolve("diagnostics")), "constructors must do no IO");
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger completions = new AtomicInteger();
        check(runs.start("activity", log -> {
            calls.incrementAndGet();
            log.report("first output\n");
            return 0;
        }, completions::incrementAndGet), "first request admitted");
        check(runs.isRunning(), "busy before worker starts");
        check(!runs.start("boot", log -> {
            throw new AssertionError("duplicate work ran");
        }, completions::incrementAndGet), "queued duplicate rejected");
        check(!runs.whileIdle(() -> { throw new AssertionError("busy mutation"); }),
                "settings rejected while queued");
        check(calls.get() == 0 && executor.tasks.size() == 1, "only one task queued");
        check(completions.get() == 1, "rejected request completes once");
        check(runs.snapshot().status.equals("Starting"), "queued state is explicit");
        check(!Files.exists(files.resolve("diagnostics")), "queued snapshot creates no logs");
        executor.runNext();
        check(calls.get() == 1 && completions.get() == 2, "accepted request completes once");
        check(!runs.isRunning(), "gate released after completion");
        RunCoordinator.Snapshot view = runs.snapshot();
        contains(view.text, "first output", "completed output retained");
        contains(view.status, "root unverified", "launch is not root verification");
        check(runs.snapshot().revision == view.revision, "unchanged snapshots are stable");
    }

    private static void concurrentAdmissionAndCompletion() throws Exception {
        Path files = directory();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ExecutorService callers = Executors.newFixedThreadPool(12);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch submitted = new CountDownLatch(12);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(12);
        AtomicInteger admitted = new AtomicInteger();
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger completions = new AtomicInteger();
        AtomicInteger cleaned = new AtomicInteger();
        RunCoordinator runs = new RunCoordinator(worker, new RunLogStore(files.toFile()), null);
        try {
            for (int i = 0; i < 12; i++) {
                callers.execute(() -> {
                    if (runs.start("concurrent", log -> {
                        executions.incrementAndGet();
                        log.report("still running\n");
                        entered.countDown();
                        await(release);
                        cleaned.incrementAndGet();
                        return 2;
                    }, () -> {
                        completions.incrementAndGet();
                        finished.countDown();
                    })) admitted.incrementAndGet();
                    submitted.countDown();
                });
            }
            await(submitted);
            await(entered);
            check(admitted.get() == 1 && executions.get() == 1,
                    "simultaneous callers execute one run");
            check(completions.get() == 11, "duplicates finish immediately");
            RunCoordinator.Snapshot oldUi = runs.snapshot();
            RunCoordinator.Snapshot recreatedUi = runs.snapshot();
            check(recreatedUi.running && oldUi.text.equals(recreatedUi.text),
                    "new UI receives the same running state and output");
            release.countDown();
            await(finished);
            check(cleaned.get() == 1 && !runs.isRunning(), "completion follows work cleanup");
            check(completions.get() == 12, "all request completions run once");
        } finally {
            release.countDown();
            worker.shutdownNow();
            callers.shutdownNow();
            worker.awaitTermination(5, TimeUnit.SECONDS);
            callers.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static void settingsAreSerializedWithAdmission() throws Exception {
        QueuedExecutor executor = new QueuedExecutor();
        RunCoordinator runs = new RunCoordinator(executor,
                new RunLogStore(directory().toFile()), null);
        CountDownLatch mutationEntered = new CountDownLatch(1);
        CountDownLatch releaseMutation = new CountDownLatch(1);
        CountDownLatch requestEntered = new CountDownLatch(1);
        CountDownLatch requestReturned = new CountDownLatch(1);
        AtomicReference<String> setting = new AtomicReference<>("before");
        Thread mutation = new Thread(() -> runs.whileIdle(() -> {
            check(!runs.isRunning(), "idle mutation may call coordinator reentrantly");
            mutationEntered.countDown();
            await(releaseMutation);
            setting.set("after");
        }));
        Thread request = new Thread(() -> {
            requestEntered.countDown();
            runs.start("boot", log -> {
                check(setting.get().equals("after"), "work sees completed settings mutation");
                return 0;
            }, null);
            requestReturned.countDown();
        });
        mutation.start();
        await(mutationEntered);
        request.start();
        await(requestEntered);
        check(requestReturned.getCount() == 1, "admission waits for idle mutation");
        releaseMutation.countDown();
        mutation.join(5000);
        request.join(5000);
        check(!mutation.isAlive() && !request.isAlive(), "settings/admission do not deadlock");
        executor.runNext();
    }

    private static void executorRejectionAndCallbacks() throws Exception {
        Path files = directory();
        AtomicInteger completions = new AtomicInteger();
        RunCoordinator rejected = new RunCoordinator(command -> {
            throw new RejectedExecutionException("executor unavailable");
        }, new RunLogStore(files.toFile()), message -> {
            throw new IllegalStateException("fallback unavailable");
        });
        check(!rejected.start("boot", log -> 0, () -> {
            completions.incrementAndGet();
            throw new IllegalStateException("completion unavailable");
        }), "executor rejection is returned");
        check(completions.get() == 1 && !rejected.isRunning(),
                "rejection and broken callbacks release the gate exactly once");
        contains(rejected.snapshot().status, "executor unavailable", "rejection visible");
        check(!Files.exists(files.resolve("diagnostics")), "rejection does not rotate logs");

        RunCoordinator direct = direct(directory(), message -> {
            throw new IllegalStateException("fallback unavailable");
        });
        check(direct.start("activity", log -> {
            log.report("report survived fallback\n");
            return 0;
        }, () -> {
            completions.incrementAndGet();
            throw new AssertionError("callback failure");
        }), "callback failures do not reject completed runs");
        check(!direct.isRunning() && completions.get() == 2, "broken callback remains nonfatal");
        contains(direct.snapshot().text, "report survived fallback", "fallback cannot break log");
        RunCoordinator error = new RunCoordinator(command -> {
            throw new AssertionError("executor failed");
        }, new RunLogStore(directory().toFile()), null);
        check(!error.start("boot", log -> 0, completions::incrementAndGet),
                "executor Error also rejects safely");
        check(!error.isRunning() && completions.get() == 3,
                "executor Error cannot strand gate or completion");
        RunCoordinator unusual = new RunCoordinator(command -> {
            command.run();
            throw new RejectedExecutionException("threw after execution");
        }, new RunLogStore(directory().toFile()), null);
        unusual.start("inline", log -> 0, completions::incrementAndGet);
        check(completions.get() == 4, "completion remains once if executor throws after running");
    }

    private static void lateReportersCannotCorruptRuns() throws Exception {
        RunCoordinator runs = direct(directory(), null);
        AtomicReference<IReporter> stale = new AtomicReference<>();
        runs.start("first", log -> {
            stale.set(log);
            log.report("first generation\n");
            return 0;
        }, null);
        stale.get().report("LATE AFTER FINISH\n");
        excludes(runs.snapshot().text, "LATE AFTER FINISH", "late terminal output dropped");
        runs.start("second", log -> {
            stale.get().report("LATE DURING NEXT RUN\n");
            log.report("second generation\n");
            return 2;
        }, null);
        excludes(runs.snapshot().text, "LATE DURING NEXT RUN", "old reporter cannot cross run");
        contains(runs.snapshot().text, "second generation", "current reporter still works");
    }

    private static void retentionExportAndReopen() throws Exception {
        Path files = directory();
        RunCoordinator runs = direct(files, null);
        for (int i = 1; i <= 3; i++) {
            final int number = i;
            runs.start("run-" + number, log -> {
                Path nativeLog = files.resolve("diagnostics/current/bootstrap.log");
                check(Files.isRegularFile(nativeLog), "native log precreated");
                check(Files.getPosixFilePermissions(nativeLog).equals(
                        PosixFilePermissions.fromString("rw-------")), "native log is 0600");
                check(Files.getPosixFilePermissions(nativeLog.getParent()).equals(
                        PosixFilePermissions.fromString("rwx------")), "current directory is 0700");
                log.report("java-" + number + "\n");
                write(nativeLog, "native-" + number + "\n");
                return number;
            }, null);
        }
        String exported = runs.exportText();
        contains(exported, "java-3", "current Java exported");
        contains(exported, "native-3", "current native exported");
        contains(exported, "java-2", "previous Java exported");
        contains(exported, "native-2", "previous native exported");
        excludes(exported, "java-1", "older Java discarded");
        excludes(exported, "native-1", "older native discarded");
        contains(exported, "diagnostics/previous/bootstrap.log", "export source headers");
        try (java.util.stream.Stream<Path> paths = Files.list(files.resolve("diagnostics"))) {
            check(paths.count() == 2, "only current and previous generations retained");
        }
        check(Files.getPosixFilePermissions(files.resolve("diagnostics/previous")).equals(
                PosixFilePermissions.fromString("rwx------")), "previous directory is 0700");

        RunCoordinator restored = direct(files, null);
        contains(restored.snapshot().status, "Patch failure", "terminal status restored");
        contains(restored.snapshot().text, "java-3", "logs survive process recreation");
        write(files.resolve("diagnostics/current/run.log"), "Started but never completed\n");
        RunCoordinator unfinished = direct(files, null);
        check(unfinished.snapshot().status.equals(RunLogStore.UNFINISHED),
                "unfinished restored run has honest outcome");
        check(!unfinished.isRunning(), "old process is not represented as running");
    }

    private static void unicodeBoundsAndTerminalReserve() throws Exception {
        Path files = directory();
        RunCoordinator runs = direct(files, null);
        String huge = repeat("😀漢字é", 30000);
        runs.start("unicode", log -> {
            log.report("old output\n");
            log.report(huge);
            log.report("\nLATEST OUTPUT\n");
            String nativeText = repeat("n", RunLogStore.NATIVE_LIMIT - 2) + "😀OUTSIDE_CAP";
            write(files.resolve("diagnostics/current/bootstrap.log"), nativeText);
            return 4;
        }, null);
        byte[] persisted = Files.readAllBytes(files.resolve("diagnostics/current/run.log"));
        check(persisted.length <= RunLogStore.JAVA_LIMIT, "Java log capped at 128 KiB");
        String javaLog = new String(persisted, StandardCharsets.UTF_8);
        contains(javaLog, "LATEST OUTPUT", "most recent output retained");
        contains(javaLog, "Earlier Java output omitted", "bounded output has truncation notice");
        contains(javaLog, "[DFRoot completion] Cleanup failed", "terminal reserve retained");
        excludes(javaLog, "\ufffd", "UTF-8 is not split at Java cap");
        excludes(runs.snapshot().text, "\ufffd", "UTF-8 is not split at native read cap");
        excludes(runs.snapshot().text, "OUTSIDE_CAP", "native read capped at 64 KiB");
        contains(direct(files, null).snapshot().status, "Cleanup failed", "capped terminal restored");
    }

    private static void liveMemorySurvivesShortOrFailedDiskWrites() throws Exception {
        Path files = directory();
        StringBuilder fallback = new StringBuilder();
        RunCoordinator runs = direct(files, fallback::append);
        runs.start("disk-shortening", log -> {
            log.report("important live output\n");
            write(files.resolve("diagnostics/current/run.log"), "short");
            contains(runs.snapshot().text, "important live output",
                    "short disk file cannot clear live memory");
            log.report("later live output\n");
            return 0;
        }, null);
        contains(direct(files, null).snapshot().text, "important live output",
                "next write repairs shortened disk log");

        runs.start("write-failure", log -> {
            log.report("before disk failure\n");
            Path logPath = files.resolve("diagnostics/current/run.log");
            Files.delete(logPath);
            Files.createDirectory(logPath);
            write(logPath.resolve("obstruction"), "do not replace");
            log.report(repeat("x", 9000) + "\nafter disk failure\n");
            log.report(repeat("y", 9000) + "\nafter repeated disk failure\n");
            contains(runs.snapshot().text, "after disk failure", "failure retains memory output");
            return 1;
        }, null);
        RunCoordinator.Snapshot terminal = runs.snapshot();
        contains(terminal.text, "before disk failure", "older live output survives write failure");
        contains(terminal.text, "Diagnostic logging failed", "write failure visible");
        contains(terminal.status, "Bootstrap failed", "terminal retained despite disk failure");
        contains(fallback.toString(), "Diagnostic logging failed", "write failure reaches fallback");
        check(occurrences(fallback.toString(), "Diagnostic logging failed: save Java log") == 1,
                "failed disk sink is disabled and warning emitted once");
        RunCoordinator restored = direct(files, null);
        check(restored.snapshot().status.equals(RunLogStore.UNFINISHED),
                "failed terminal persistence is not falsely restored as success");
        contains(restored.snapshot().text, "Diagnostic logging failed", "read failure visible");
    }

    private static void generationFailurePreservesPrevious() throws Exception {
        Path files = directory();
        RunCoordinator runs = direct(files, null);
        runs.start("first", log -> { log.report("KEEP_PREVIOUS\n"); return 0; }, null);
        runs.start("second", log -> { log.report("KEEP_CURRENT\n"); return 0; }, null);
        Path previous = files.resolve("diagnostics/previous/run.log");
        byte[] before = Files.readAllBytes(previous);
        Path current = files.resolve("diagnostics/current");
        Files.move(current, files.resolve("saved-current"));
        write(current, "obstruction");
        AtomicInteger workCalls = new AtomicInteger();
        runs.start("cannot-create", log -> {
            workCalls.incrementAndGet();
            log.report("still captured in memory\n");
            return 11;
        }, null);
        check(java.util.Arrays.equals(before, Files.readAllBytes(previous)),
                "generation creation failure preserves previous logs");
        check(workCalls.get() == 0, "fresh log creation failure prevents native work");
        contains(runs.snapshot().text, "Origin: cannot-create", "failed begin retains new memory");
        contains(runs.snapshot().text, "Diagnostic logging failed", "begin failure is visible");
        contains(runs.snapshot().status, "Startup not attempted", "terminal survives begin failure");
    }

    private static void failedGenerationDoesNotTouchPreviousNativeLog() throws Exception {
        Path files = directory();
        RunCoordinator runs = direct(files, null);
        runs.start("existing", log -> {
            log.report("existing Java output\n");
            write(files.resolve("diagnostics/current/bootstrap.log"), "existing native output\n");
            return 0;
        }, null);
        Path javaLog = files.resolve("diagnostics/current/run.log");
        Path nativeLog = files.resolve("diagnostics/current/bootstrap.log");
        byte[] beforeJava = Files.readAllBytes(javaLog);
        byte[] beforeNative = Files.readAllBytes(nativeLog);
        write(files.resolve("diagnostics/previous"), "obstruction");
        AtomicInteger completions = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        runs.start("blocked", log -> {
            calls.incrementAndGet();
            write(nativeLog, "would corrupt attribution");
            return 0;
        }, completions::incrementAndGet);
        check(calls.get() == 0 && completions.get() == 1,
                "failed prepare completes without launching Work");
        check(java.util.Arrays.equals(beforeJava, Files.readAllBytes(javaLog)),
                "failed prepare preserves prior current Java bytes");
        check(java.util.Arrays.equals(beforeNative, Files.readAllBytes(nativeLog)),
                "failed prepare preserves prior current native bytes");
        contains(runs.exportText(), "existing Java output", "failed attempt export retains disk Java");
        contains(runs.exportText(), "existing native output",
                "failed attempt export retains disk native");
        contains(runs.snapshot().status, "exit code 11", "prepare failure has preflight code");
    }

    private static void incompleteTerminalAndLaunchCheckpoint() throws Exception {
        Path files = directory();
        RunCoordinator runs = direct(files, null);
        Path javaLog = files.resolve("diagnostics/current/run.log");
        runs.start("checkpoint", log -> {
            log.report("configuration before native launch\n");
            log.report("* launching native process\n");
            String persisted = new String(Files.readAllBytes(javaLog), StandardCharsets.UTF_8);
            contains(persisted, "configuration before native launch",
                    "prelaunch checkpoint flushes pending configuration");
            contains(persisted, "* launching native process", "launch checkpoint is persisted");
            log.report("pending patch details\n");
            log.report("=== cleanup ===\n");
            persisted = new String(Files.readAllBytes(javaLog), StandardCharsets.UTF_8);
            contains(persisted, "pending patch details", "native stage flushes prior progress");
            contains(persisted, "=== cleanup ===", "native stage is persisted immediately");
            log.report("cleanup: ERROR - restoration incomplete\n");
            persisted = new String(Files.readAllBytes(javaLog), StandardCharsets.UTF_8);
            contains(persisted, "cleanup: ERROR", "explicit native error is persisted immediately");
            return 0;
        }, null);
        String complete = new String(Files.readAllBytes(javaLog), StandardCharsets.UTF_8);
        check(complete.endsWith("\n"), "complete terminal line ends with newline");
        write(javaLog, complete.substring(0, complete.length() - 1));
        check(direct(files, null).snapshot().status.equals(RunLogStore.UNFINISHED),
                "partial terminal line cannot claim completion");
    }

    private static void interruptedRotationRecovery() throws Exception {
        for (int phase = 0; phase < 4; phase++) {
            Path files = directory();
            Path logs = files.resolve("diagnostics");
            Files.createDirectories(logs);
            if (phase == 0) {
                generation(logs.resolve(".current-latest"), "LATEST_REAL", 1000);
                generation(logs.resolve("previous"), "OLDER_REAL", 2000);
            } else {
                generation(logs.resolve("current"), "NEW_ATTEMPT", 500);
                if (phase < 3) {
                    generation(logs.resolve(".current-latest"), "LATEST_REAL", 1000);
                    generation(logs.resolve(phase == 1 ? "previous" : ".previous-older"),
                            "OLDER_REAL", phase == 1 ? 1000 : 2000);
                } else {
                    generation(logs.resolve("previous"), "LATEST_REAL", 1000);
                    generation(logs.resolve(".previous-older"), "OLDER_REAL", 2000);
                }
            }
            generation(logs.resolve(".next-unused"), "NEVER_LAUNCHED", 9000);
            String exported = direct(files, null).exportText();
            contains(exported, "LATEST_REAL", "recovery keeps most recent real run, phase " + phase);
            excludes(exported, "NEVER_LAUNCHED", "unused staging not promoted, phase " + phase);
            if (phase == 0) contains(exported, "OLDER_REAL", "pre-install rollback keeps history");
            else excludes(exported, "OLDER_REAL",
                    "rename role outranks reversed/equal wall clock, phase " + phase);
            try (java.util.stream.Stream<Path> paths = Files.list(logs)) {
                check(paths.count() == 2, "interrupted rotation bounded to two generations");
            }
        }
        Path files = directory();
        Path logs = files.resolve("diagnostics");
        generation(logs.resolve(".next-first-run"), "ONLY_COPY", 1000);
        contains(direct(files, null).exportText(), "ONLY_COPY", "recovery preserves only staged copy");
    }

    private static void generation(Path directory, String text, long modified) throws Exception {
        Files.createDirectories(directory);
        write(directory.resolve("run.log"), text + "\n");
        write(directory.resolve("bootstrap.log"), text + " native\n");
        java.nio.file.attribute.FileTime time =
                java.nio.file.attribute.FileTime.fromMillis(modified);
        Files.setLastModifiedTime(directory.resolve("run.log"), time);
        Files.setLastModifiedTime(directory.resolve("bootstrap.log"), time);
    }

    private static void admissionDoesNotWaitForDiskReads() throws Exception {
        RunLogStore store = new RunLogStore(directory().toFile());
        QueuedExecutor worker = new QueuedExecutor();
        RunCoordinator runs = new RunCoordinator(worker, store, null);
        CountDownLatch readEntered = new CountDownLatch(1);
        CountDownLatch admitted = new CountDownLatch(1);
        Thread reader = new Thread(() -> {
            readEntered.countDown();
            runs.snapshot();
        });
        Thread caller = new Thread(() -> {
            runs.start("while disk blocked", log -> 0, null);
            admitted.countDown();
        });
        synchronized (store) {
            reader.start();
            await(readEntered);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (reader.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline)
                Thread.yield();
            check(reader.getState() == Thread.State.BLOCKED, "snapshot is blocked on disk store");
            caller.start();
            check(admitted.await(2, TimeUnit.SECONDS), "admission avoids snapshot disk lock");
            check(runs.isRunning(), "running state is readable while disk store is blocked");
            check(!runs.whileIdle(() -> { throw new AssertionError("busy mutation"); }),
                    "busy settings check avoids snapshot disk lock");
        }
        caller.join(5000);
        reader.join(5000);
        check(!reader.isAlive() && !caller.isAlive(), "blocked snapshot resumes without deadlock");
        worker.runNext();
    }

    private static void terminalStatusesAndFailures() throws Exception {
        RunCoordinator runs = direct(directory(), null);
        int[] codes = {0, 1, 2, 3, 4, 10, 11, 17};
        String[] expected = {"root unverified", "Bootstrap failed", "incomplete",
                "Patch failure", "Cleanup failed", "Already attempted", "Preflight failed",
                "code 17"};
        for (int i = 0; i < codes.length; i++) {
            final int code = codes[i];
            runs.start("status", log -> code, null);
            contains(runs.snapshot().status, expected[i], "exit code " + code + " mapped honestly");
        }
        AtomicInteger complete = new AtomicInteger();
        runs.start("exception", log -> {
            throw new IOException("native exit code 4; reader failed");
        }, complete::incrementAndGet);
        contains(runs.snapshot().status, "native exit code 4", "exception preserves native detail");
        check(!runs.isRunning() && complete.get() == 1, "exception completes and releases gate");
        runs.start("error", log -> { throw new AssertionError("fatal test work"); },
                complete::incrementAndGet);
        contains(runs.snapshot().status, "fatal test work", "work Error remains visible");
        check(!runs.isRunning() && complete.get() == 2, "work Error completes and releases gate");
    }

    private static RunCoordinator direct(Path files, IReporter fallback) {
        return new RunCoordinator(Runnable::run, new RunLogStore(files.toFile()), fallback);
    }

    private static Path directory() throws IOException {
        Path path = Files.createTempDirectory("dfroot-coordinator-test-");
        temporary.add(path);
        return path;
    }

    private static void write(Path path, String text) throws IOException {
        Files.write(path, text.getBytes(StandardCharsets.UTF_8));
    }

    private static String repeat(String value, int count) {
        StringBuilder text = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) text.append(value);
        return text.toString();
    }

    private static int occurrences(String text, String fragment) {
        int count = 0;
        for (int index = 0; (index = text.indexOf(fragment, index)) >= 0; index += fragment.length())
            count++;
        return count;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("latch timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static void contains(String text, String expected, String message) {
        check(text.contains(expected), message + " (missing: " + expected + ")");
    }

    private static void excludes(String text, String unexpected, String message) {
        check(!text.contains(unexpected), message + " (unexpected: " + unexpected + ")");
    }

    private static synchronized void check(boolean condition, String message) {
        assertions++;
        if (!condition) {
            failedAssertions.add(message);
            throw new AssertionError(message);
        }
    }

    private static void deleteTree(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
                for (Path entry : entries) deleteTree(entry);
            }
        }
        Files.deleteIfExists(path);
    }

    private static final class QueuedExecutor implements Executor {
        final List<Runnable> tasks = new ArrayList<>();

        @Override public synchronized void execute(Runnable command) {
            tasks.add(command);
        }

        void runNext() {
            Runnable task;
            synchronized (this) { task = tasks.remove(0); }
            task.run();
        }
    }
}
