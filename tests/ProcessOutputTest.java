package df.root;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Host-only tests: no native executable, Android APIs, or device access. */
public final class ProcessOutputTest {
    private static final long TEST_TIMEOUT_SECONDS = 8;

    public static void main(String[] args) throws Exception {
        reporterFailureStillDrains();
        eofBeforeExitStillWaits();
        interruptedWaitStillWaits();
        readerFailureStillWaits();
        delayedTrailingOutputIsDelivered();
        postExitDrainAndCloseAreBounded();
        newlineFreeOutputIsBounded();
        stdinCloseFailureDoesNotHideExit();
        System.out.println("ProcessOutput tests passed (8 cases)");
    }

    private static void reporterFailureStillDrains() throws Exception {
        CountingInputStream output = new CountingInputStream(bytes("first\nsecond\nthird\n"));
        FakeProcess child = new FakeProcess(output);
        RuntimeException problem = new IllegalStateException("reporter failed");
        CountDownLatch reported = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        Invocation invocation = start(child, message -> {
            calls.incrementAndGet();
            reported.countDown();
            throw problem;
        });
        await(reported, "reporter called");
        await(output.eof, "output drained after reporter failure");
        check(!invocation.finished(), "reporter failure returned before child exit");
        check(output.closes.get() == 0, "stdout closed while child alive");
        child.exit(4);
        invocation.finish();
        checkFailure(invocation, 4, problem);
        check(calls.get() == 1, "failed reporter called again");
        check(output.count.get() == bytes("first\nsecond\nthird\n").length, "output lost");
        child.assertNotDestroyed();
    }

    private static void eofBeforeExitStillWaits() throws Exception {
        CountingInputStream output = new CountingInputStream(bytes("done\n"));
        FakeProcess child = new FakeProcess(output);
        Invocation invocation = start(child, message -> {});
        await(output.eof, "output EOF");
        await(child.waitStarted, "child wait");
        check(!invocation.finished(), "EOF was mistaken for process exit");
        check(output.closes.get() == 0, "stdout closed at EOF before child exit");
        child.exit(23);
        invocation.finish();
        check(invocation.failure.get() == null, "unexpected EOF failure");
        check(invocation.exitCode == 23, "nonzero child exit was lost");
        child.assertNotDestroyed();
    }

    private static void interruptedWaitStillWaits() throws Exception {
        FakeProcess child = new FakeProcess(new ByteArrayInputStream(new byte[0]));
        Invocation invocation = start(child, message -> {});
        await(child.waitStarted, "initial wait");
        invocation.thread.interrupt();
        await(child.waitInterrupted, "wait interruption");
        await(child.waitRetried, "wait retried after interruption");
        invocation.thread.interrupt();
        await(child.waitInterruptedAgain, "second wait interruption");
        await(child.waitRetriedAgain, "wait retried after second interruption");
        check(!invocation.finished(), "interruption released child ownership");
        child.exit(4);
        invocation.finish();
        Throwable cause = checkFailure(invocation, 4, null).getCause();
        check(cause instanceof InterruptedException,
                "interruption was not reported");
        check(cause.getSuppressed().length == 0,
                "repeated interrupts accumulated redundant exceptions");
        check(invocation.interruptedAtReturn, "interrupt flag was not restored");
        child.assertNotDestroyed();
    }

    private static void readerFailureStillWaits() throws Exception {
        IOException problem = new IOException("read failed");
        CountDownLatch read = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        InputStream output = new InputStream() {
            @Override public int read() throws IOException {
                read.countDown();
                throw problem;
            }
            @Override public void close() {
                closes.incrementAndGet();
            }
        };
        FakeProcess child = new FakeProcess(output);
        Invocation invocation = start(child, message -> {});
        await(read, "failing read");
        await(child.waitStarted, "child wait after read failure");
        check(!invocation.finished(), "reader failure returned before child exit");
        check(closes.get() == 0, "read failure closed stdout while child alive");
        child.exit(4);
        invocation.finish();
        checkFailure(invocation, 4, problem);
        child.assertNotDestroyed();
    }

    private static void delayedTrailingOutputIsDelivered() throws Exception {
        GatedInputStream output = new GatedInputStream(bytes("trailing output"));
        FakeProcess child = new FakeProcess(output);
        StringBuffer messages = new StringBuffer();
        Invocation invocation = start(child, messages::append);
        await(output.readStarted, "trailing read");
        child.exit(4);
        await(child.exitObserved, "child exit observed");
        check(!invocation.finished(), "did not allow trailing output to drain");
        output.release.countDown();
        invocation.finish();
        check(invocation.failure.get() == null, "trailing output failed");
        check(invocation.exitCode == 4, "exit status changed while draining");
        check(messages.toString().equals("trailing output"), "trailing output missing");
        child.assertNotDestroyed();
    }

    private static void postExitDrainAndCloseAreBounded() throws Exception {
        BlockingCloseInputStream output = new BlockingCloseInputStream();
        FakeProcess child = new FakeProcess(output);
        Invocation invocation = start(child, message -> {});
        await(output.readStarted, "blocked read");
        long start = System.nanoTime();
        child.exit(9);
        try {
            invocation.finish();
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            check(elapsedMs >= 1500, "post-exit drain did not get its allowance");
            check(elapsedMs < 5000, "blocked read or close exceeded drain budget");
            ProcessOutput.Failure failure = checkFailure(invocation, 9, null);
            check(failure.getCause() instanceof IOException, "incomplete output not reported");
            check(failure.getCause().getMessage().contains("did not finish draining"),
                    "incomplete-output diagnostic was lost");
            await(output.closeStarted, "asynchronous close");
            check(output.closeFinished.getCount() == 1, "close was not actually blocked");
            child.assertNotDestroyed();
        } finally {
            output.releaseRead.countDown();
            output.releaseClose.countDown();
            await(output.closeFinished, "blocked close released");
        }
    }

    private static void newlineFreeOutputIsBounded() throws Exception {
        String text = "x".repeat(40_000) + "\n" + "y".repeat(9_000);
        FakeProcess child = new FakeProcess(new ByteArrayInputStream(bytes(text)));
        StringBuffer messages = new StringBuffer();
        AtomicInteger largest = new AtomicInteger();
        Invocation invocation = start(child, message -> {
            largest.accumulateAndGet(message.length(), Math::max);
            messages.append(message);
        });
        child.exit(0);
        invocation.finish();
        check(invocation.failure.get() == null, "long output failed");
        check(messages.toString().equals(text), "chunking changed output");
        check(largest.get() <= 4096, "unbounded reporter message");
        child.assertNotDestroyed();
    }

    private static void stdinCloseFailureDoesNotHideExit() throws Exception {
        FakeProcess child = new FakeProcess(new ByteArrayInputStream(new byte[0]));
        CountDownLatch closed = new CountDownLatch(1);
        child.stdin = new OutputStream() {
            @Override public void write(int value) {}
            @Override public void close() throws IOException {
                closed.countDown();
                throw new IOException("stdin close failed");
            }
        };
        Invocation invocation = start(child, message -> {});
        await(closed, "stdin close attempt");
        child.exit(5);
        invocation.finish();
        check(invocation.failure.get() == null, "stdin close hid native exit");
        check(invocation.exitCode == 5, "stdin close changed native exit");
        child.assertNotDestroyed();
    }

    private static Invocation start(FakeProcess child, IReporter reporter) {
        Invocation invocation = new Invocation(child, reporter);
        invocation.thread.start();
        return invocation;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void await(CountDownLatch latch, String description) throws InterruptedException {
        check(latch.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS), "timed out: " + description);
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    private static ProcessOutput.Failure checkFailure(
            Invocation invocation, int exitCode, Throwable expectedCause) {
        Throwable error = invocation.failure.get();
        check(error instanceof ProcessOutput.Failure, "missing typed process failure");
        ProcessOutput.Failure failure = (ProcessOutput.Failure) error;
        check(failure.exitCode == exitCode, "failure lost observed native exit status");
        check(failure.getMessage().contains("status " + exitCode),
                "failure message omitted native exit status");
        if (expectedCause != null) {
            check(failure.getCause() == expectedCause, "original failure cause was replaced");
        }
        return failure;
    }

    private static final class Invocation {
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread thread;
        volatile int exitCode = Integer.MIN_VALUE;
        volatile boolean interruptedAtReturn;

        Invocation(Process child, IReporter reporter) {
            thread = new Thread(() -> {
                try {
                    exitCode = ProcessOutput.waitFor(child, reporter);
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    interruptedAtReturn = Thread.currentThread().isInterrupted();
                }
            }, "test-process-owner");
            // A broken implementation must fail the test, not hang its JVM.
            thread.setDaemon(true);
        }

        boolean finished() {
            return !thread.isAlive();
        }

        void finish() throws InterruptedException {
            thread.join(TimeUnit.SECONDS.toMillis(TEST_TIMEOUT_SECONDS));
            check(finished(), "process owner did not finish");
        }
    }

    private static final class FakeProcess extends Process {
        final InputStream stdout;
        OutputStream stdin = new ByteArrayOutputStream();
        final CountDownLatch exit = new CountDownLatch(1);
        final CountDownLatch waitStarted = new CountDownLatch(1);
        final CountDownLatch waitInterrupted = new CountDownLatch(1);
        final CountDownLatch waitRetried = new CountDownLatch(1);
        final CountDownLatch waitInterruptedAgain = new CountDownLatch(1);
        final CountDownLatch waitRetriedAgain = new CountDownLatch(1);
        final CountDownLatch exitObserved = new CountDownLatch(1);
        final AtomicInteger waits = new AtomicInteger();
        final AtomicInteger destroys = new AtomicInteger();
        volatile int status;

        FakeProcess(InputStream stdout) {
            this.stdout = stdout;
        }

        void exit(int status) {
            this.status = status;
            exit.countDown();
        }

        @Override public OutputStream getOutputStream() { return stdin; }
        @Override public InputStream getInputStream() { return stdout; }
        @Override public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override public int waitFor() throws InterruptedException {
            int attempt = waits.incrementAndGet();
            if (attempt > 1) waitRetried.countDown();
            if (attempt > 2) waitRetriedAgain.countDown();
            waitStarted.countDown();
            try {
                exit.await();
            } catch (InterruptedException e) {
                waitInterrupted.countDown();
                if (attempt > 1) waitInterruptedAgain.countDown();
                throw e;
            }
            exitObserved.countDown();
            return status;
        }

        @Override public int exitValue() {
            if (exit.getCount() != 0) throw new IllegalThreadStateException("child is alive");
            return status;
        }

        @Override public void destroy() { destroys.incrementAndGet(); }
        @Override public Process destroyForcibly() {
            destroys.incrementAndGet();
            return this;
        }

        void assertNotDestroyed() {
            check(destroys.get() == 0, "child was destroyed");
        }
    }

    private static final class CountingInputStream extends InputStream {
        final ByteArrayInputStream delegate;
        final AtomicInteger count = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        final CountDownLatch eof = new CountDownLatch(1);

        CountingInputStream(byte[] data) { delegate = new ByteArrayInputStream(data); }

        @Override public int read() {
            int result = delegate.read();
            if (result == -1) eof.countDown();
            else count.incrementAndGet();
            return result;
        }

        @Override public void close() { closes.incrementAndGet(); }
    }

    private static final class GatedInputStream extends InputStream {
        final CountDownLatch readStarted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final ByteArrayInputStream delegate;

        GatedInputStream(byte[] data) { delegate = new ByteArrayInputStream(data); }

        @Override public int read() throws IOException {
            readStarted.countDown();
            waitForRelease(release);
            return delegate.read();
        }
    }

    private static final class BlockingCloseInputStream extends InputStream {
        final CountDownLatch readStarted = new CountDownLatch(1);
        final CountDownLatch releaseRead = new CountDownLatch(1);
        final CountDownLatch closeStarted = new CountDownLatch(1);
        final CountDownLatch releaseClose = new CountDownLatch(1);
        final CountDownLatch closeFinished = new CountDownLatch(1);

        @Override public int read() throws IOException {
            readStarted.countDown();
            waitForRelease(releaseRead);
            return -1;
        }

        @Override public void close() throws IOException {
            closeStarted.countDown();
            waitForRelease(releaseClose);
            closeFinished.countDown();
        }
    }

    private static void waitForRelease(CountDownLatch latch) throws IOException {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }
}
