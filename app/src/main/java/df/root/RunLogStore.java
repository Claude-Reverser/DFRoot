package df.root;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Two private diagnostic generations. Construction does no filesystem work.
 * Call read/export on a background thread; live Java output remains authoritative
 * even when a disk write fails or an older file is shorter.
 */
public final class RunLogStore {
    static final int JAVA_LIMIT = 128 * 1024;
    static final int NATIVE_LIMIT = 64 * 1024;
    private static final int TERMINAL_RESERVE = 4096;
    private static final int FLUSH_BYTES = 8192;
    private static final long FLUSH_NANOS = 1_000_000_000L;
    private static final String TRUNCATED = "[Earlier Java output omitted: 128 KiB limit]\n";
    private static final String COMPLETION = "[DFRoot completion] ";
    static final String UNFINISHED =
            "Previous run has no completion record; outcome unknown";

    private final Path root;
    private boolean loaded;
    private boolean writable;
    private boolean currentBelongsToRun;
    private boolean truncated;
    private long pendingBytes;
    private long lastFlush;
    private String header = "";
    private String body = "";
    private String diagnostic = "";
    private String terminal = "";
    private String status = "No run recorded";

    public RunLogStore(File filesDir) {
        root = filesDir.toPath().resolve("diagnostics");
    }

    static final class View {
        final String text;
        final String status;

        View(String text, String status) {
            this.text = text;
            this.status = status;
        }
    }

    /**
     * Called only for an admitted run, on its worker. A non-null diagnostic means
     * preparation failed: the caller must not launch native work using the fixed
     * bootstrap.log path, which may still belong to the previous disk generation.
     */
    synchronized String begin(String origin) {
        loaded = true;
        writable = false;
        currentBelongsToRun = false;
        header = "[DFRoot run]\nOrigin: " + singleLine(origin, 256)
                + "\nStarted: " + Instant.now() + "\n";
        body = "";
        diagnostic = "";
        terminal = "";
        truncated = false;
        pendingBytes = 0;
        status = "Running; outcome not yet known";
        try {
            prepareGeneration();
            writable = true;
            currentBelongsToRun = true;
            lastFlush = System.nanoTime();
            return null;
        } catch (Exception e) {
            return noteFailure("create diagnostic logs", e);
        }
    }

    synchronized String append(String message) {
        if (message == null || message.isEmpty()) return null;
        int limit = JAVA_LIMIT - TERMINAL_RESERVE - utf8Length(header) - utf8Length(TRUNCATED);
        String boundedMessage = tailUtf8(message, JAVA_LIMIT);
        String combined = body + boundedMessage;
        truncated |= !boundedMessage.equals(message) || utf8Length(combined) > limit;
        body = tailUtf8(combined, limit);
        pendingBytes += utf8Length(boundedMessage);
        boolean checkpoint = message.startsWith("* launching native process")
                || message.startsWith("=== ") || message.contains(": ERROR");
        // Every actual write is durable. Ordinary progress is batched; buffered
        // output after the last checkpoint may be lost on an abrupt reset.
        if (!checkpoint && pendingBytes < FLUSH_BYTES
                && System.nanoTime() - lastFlush < FLUSH_NANOS) return null;
        return persist();
    }

    synchronized String finish(String result) {
        status = singleLine(result, 1800);
        terminal = "\n" + COMPLETION + status + "\n";
        return persist();
    }

    synchronized View read() {
        restoreOnce();
        String nativeText = currentBelongsToRun
                ? readText(root.resolve("current/bootstrap.log"), NATIVE_LIMIT)
                : "(No native log is available for this run.)\n";
        String javaSection = currentBelongsToRun
                ? section("Current Java", "current/run.log", javaText())
                : "\n=== Current attempt Java log (memory; not saved) ===\n" + javaText();
        return new View(javaSection
                + section("Current native bootstrap", "current/bootstrap.log", nativeText),
                status);
    }

    public synchronized String exportText() {
        View current = read();
        String retainedCurrent = !currentBelongsToRun
                ? section("Retained disk current Java", "current/run.log",
                        readText(root.resolve("current/run.log"), JAVA_LIMIT))
                    + section("Retained disk current native bootstrap", "current/bootstrap.log",
                        readText(root.resolve("current/bootstrap.log"), NATIVE_LIMIT))
                : "";
        return current.text + retainedCurrent
                + section("Previous Java", "previous/run.log",
                        readText(root.resolve("previous/run.log"), JAVA_LIMIT))
                + section("Previous native bootstrap", "previous/bootstrap.log",
                        readText(root.resolve("previous/bootstrap.log"), NATIVE_LIMIT));
    }

    private void restoreOnce() {
        if (loaded) return;
        loaded = true;
        Path currentLog = root.resolve("current/run.log");
        try {
            if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) recoverGenerations();
        } catch (Exception recoveryFailure) {
            noteFailure("recover retained logs", recoveryFailure);
        }
        try {
            if (!Files.exists(currentLog, LinkOption.NOFOLLOW_LINKS)) return;
            body = readBounded(currentLog, JAVA_LIMIT);
            currentBelongsToRun = true;
            status = completionStatus(body);
        } catch (Exception e) {
            status = UNFINISHED;
            noteFailure("read existing Java log", e);
            currentBelongsToRun = true;
        }
    }

    private static String completionStatus(String text) {
        if (!text.endsWith("\n")) return UNFINISHED;
        int index = text.lastIndexOf("\n" + COMPLETION);
        if (index < 0 && text.startsWith(COMPLETION)) index = -1;
        else if (index < 0) return UNFINISHED;
        int start = index + 1 + COMPLETION.length();
        String result = text.substring(start).trim();
        return result.isEmpty() || result.indexOf('\n') >= 0 ? UNFINISHED : result;
    }

    private String javaText() {
        String notice = truncated ? TRUNCATED : "";
        int budget = JAVA_LIMIT - utf8Length(header)
                - utf8Length(diagnostic) - utf8Length(terminal) - utf8Length(notice);
        return header + notice + tailUtf8(body, Math.max(0, budget)) + diagnostic + terminal;
    }

    private String persist() {
        if (!writable) return null;
        Path temporary = null;
        try {
            Path directory = root.resolve("current");
            temporary = privateTempFile(directory, ".run-", ".tmp");
            durableWrite(temporary, javaText());
            move(temporary, directory.resolve("run.log"), true);
            syncDirectory(directory);
            lastFlush = System.nanoTime();
            pendingBytes = 0;
            return null;
        } catch (Exception e) {
            writable = false;
            return noteFailure("save Java log", e);
        } finally {
            deleteQuietly(temporary);
        }
    }

    private String noteFailure(String operation, Exception failure) {
        if (!diagnostic.isEmpty()) return null;
        String message = "[Diagnostic logging failed: " + operation + ": "
                + singleLine(failure.toString(), 1400) + "]\n";
        // One visible warning remains available even if every subsequent write fails.
        diagnostic = "\n" + message;
        return message;
    }

    private void prepareGeneration() throws IOException {
        Files.createDirectories(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Expected a private directory: " + root);
        privateDirectory(root);
        syncDirectory(root.getParent());
        recoverGenerations();
        Path current = root.resolve("current");
        Path previous = root.resolve("previous");
        validateDirectory(current);
        validateDirectory(previous);

        Path staged = privateTempDirectory(root, ".next-");
        Path savedCurrent = root.resolve(".current-" + UUID.randomUUID());
        Path savedPrevious = root.resolve(".previous-" + UUID.randomUUID());
        boolean movedCurrent = false;
        boolean installed = false;
        boolean movedPrevious = false;
        boolean archived = false;
        try {
            // Finish creating both files before changing either retained generation.
            privateFile(staged.resolve("run.log"), javaText());
            privateFile(staged.resolve("bootstrap.log"), "");
            syncDirectory(staged);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                move(current, savedCurrent, false);
                movedCurrent = true;
                syncDirectory(root);
            }
            move(staged, current, false);
            installed = true;
            syncDirectory(root);
            if (movedCurrent) {
                if (Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) {
                    move(previous, savedPrevious, false);
                    movedPrevious = true;
                    syncDirectory(root);
                }
                move(savedCurrent, previous, false);
                archived = true;
                syncDirectory(root);
            }
        } catch (IOException | RuntimeException failure) {
            // Roll back in reverse order; never delete the only retained copy.
            try {
                if (archived) move(previous, savedCurrent, false);
                if (movedPrevious) move(savedPrevious, previous, false);
                if (installed) move(current, staged, false);
                if (movedCurrent) move(savedCurrent, current, false);
                syncDirectory(root);
            } catch (IOException | RuntimeException rollback) {
                failure.addSuppressed(rollback);
            }
            throw failure;
        } finally {
            deleteQuietly(staged);
        }
        deleteQuietly(savedPrevious);
        syncDirectory(root);
    }

    /**
     * Complete an interrupted rename sequence without discarding its newest
     * retained evidence. Staging directories contain no launched native work.
     * Once current/previous are installed, older backups and unused stages can
     * be removed; a failed recovery leaves the remaining copies in place.
     */
    private void recoverGenerations() throws IOException {
        Path current = root.resolve("current");
        Path previous = root.resolve("previous");
        validateDirectory(current);
        validateDirectory(previous);
        List<Path> backups = new ArrayList<>();
        List<Path> stages = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path path : entries) {
                String name = path.getFileName().toString();
                if (name.startsWith(".current-") || name.startsWith(".previous-")) backups.add(path);
                else if (name.startsWith(".next-")) stages.add(path);
            }
        }
        // Rename roles encode transaction order even if the wall clock moves
        // backwards: saved current is newer than existing/saved previous.
        Comparator<Path> newest = Comparator.comparingInt(RunLogStore::recoveryPriority)
                .thenComparing(Comparator.comparingLong(RunLogStore::evidenceTime).reversed())
                .thenComparing(path -> path.getFileName().toString());
        backups.sort(newest);
        stages.sort(newest);
        if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            List<Path> candidates = new ArrayList<>(backups);
            if (Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) candidates.add(previous);
            candidates.sort(newest);
            if (candidates.isEmpty()) candidates.addAll(stages);
            Path best = firstEvidence(candidates);
            if (best != null) {
                move(best, current, false);
                syncDirectory(root);
                backups.remove(best);
                stages.remove(best);
            }
        }
        if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) return;
        List<Path> historical = new ArrayList<>(backups);
        if (Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) historical.add(previous);
        historical.sort(newest);
        Path best = firstEvidence(historical);
        if (best != null && !best.equals(previous)) {
            Path displaced = root.resolve(".previous-" + UUID.randomUUID());
            if (Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) {
                move(previous, displaced, false);
                syncDirectory(root);
                backups.add(displaced);
            }
            move(best, previous, false);
            syncDirectory(root);
            backups.remove(best);
        }
        for (Path path : backups) deleteTree(path);
        for (Path path : stages) deleteTree(path);
        // An interrupted atomic log rewrite is not a separate run generation.
        pruneWriteTemps(current);
        pruneWriteTemps(previous);
        syncDirectory(root);
    }

    private static Path firstEvidence(List<Path> candidates) {
        for (Path path : candidates) {
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isRegularFile(path.resolve("run.log"), LinkOption.NOFOLLOW_LINKS)
                    || Files.isRegularFile(path.resolve("bootstrap.log"), LinkOption.NOFOLLOW_LINKS)))
                return path;
        }
        return null;
    }

    private static long evidenceTime(Path path) {
        try {
            return Math.max(Files.exists(path.resolve("run.log"))
                            ? Files.getLastModifiedTime(path.resolve("run.log")).toMillis() : 0,
                    Files.exists(path.resolve("bootstrap.log"))
                            ? Files.getLastModifiedTime(path.resolve("bootstrap.log")).toMillis() : 0);
        } catch (IOException | RuntimeException ignored) {
            return 0;
        }
    }

    private static int recoveryPriority(Path path) {
        String name = path.getFileName().toString();
        if (name.startsWith(".current-")) return 0;
        if (name.equals("previous")) return 1;
        if (name.startsWith(".previous-")) return 2;
        return 3;
    }

    private static void pruneWriteTemps(Path directory) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, ".run-*.tmp")) {
            for (Path path : entries) deleteTree(path);
        }
    }

    private static void validateDirectory(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Expected a private directory: " + path);
        privateDirectory(path);
    }

    private static Path privateTempDirectory(Path parent, String prefix) throws IOException {
        Path path;
        try {
            path = Files.createTempDirectory(parent, prefix,
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException unsupported) {
            path = Files.createTempDirectory(parent, prefix);
        }
        privateDirectory(path);
        return path;
    }

    private static Path privateTempFile(Path parent, String prefix, String suffix)
            throws IOException {
        Path path;
        try {
            path = Files.createTempFile(parent, prefix, suffix,
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException unsupported) {
            path = Files.createTempFile(parent, prefix, suffix);
        }
        privatePermissions(path, false);
        return path;
    }

    private static void privateFile(Path path, String text) throws IOException {
        try {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException unsupported) {
            Files.createFile(path);
        }
        privatePermissions(path, false);
        durableWrite(path, text);
    }

    private static void durableWrite(Path path, String text) throws IOException {
        try (FileOutputStream output = new FileOutputStream(path.toFile())) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }

    private static void syncDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static void privateDirectory(Path path) throws IOException {
        privatePermissions(path, true);
    }

    private static void privatePermissions(Path path, boolean directory) throws IOException {
        try {
            Files.setPosixFilePermissions(path,
                    PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
        } catch (UnsupportedOperationException unsupported) {
            File file = path.toFile();
            if (!file.setReadable(false, false) || !file.setWritable(false, false)
                    || !file.setExecutable(false, false)
                    || !file.setReadable(true, true) || !file.setWritable(true, true)
                    || (directory && !file.setExecutable(true, true)))
                throw new IOException("Cannot make diagnostic path private: " + path);
        }
    }

    private static void move(Path from, Path to, boolean replace) throws IOException {
        try {
            if (replace) Files.move(from, to, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            else Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            if (replace) Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(from, to);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            deleteTree(path);
        } catch (IOException | RuntimeException ignored) {
            // Failed cleanup must not hide a result or destroy retained logs.
        }
    }

    private static void deleteTree(Path path) throws IOException {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
                for (Path entry : entries) deleteTree(entry);
            }
        }
        Files.deleteIfExists(path);
    }

    private static String section(String label, String path, String text) {
        return "\n=== " + label + " log: diagnostics/" + path + " ===\n"
                + text + (text.endsWith("\n") ? "" : "\n");
    }

    private static String readText(Path path, int limit) {
        try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS))
                return "(No log recorded.)\n";
            return readBounded(path, limit);
        } catch (Exception e) {
            return "[Diagnostic logging failed: read " + path.getFileName() + ": "
                    + singleLine(e.toString(), 1400) + "]\n";
        }
    }

    private static String readBounded(Path path, int limit) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Expected a regular diagnostic file: " + path);
        try (InputStream input = Files.newInputStream(path);
             ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(limit, 8192))) {
            byte[] block = new byte[4096];
            while (bytes.size() < limit) {
                int count = input.read(block, 0, Math.min(block.length, limit - bytes.size()));
                if (count < 0) break;
                bytes.write(block, 0, count);
            }
            byte[] value = bytes.toByteArray();
            int end = value.length;
            // A concurrent native append may leave the final UTF-8 sequence incomplete.
            if (end > 0) {
                int lead = end - 1;
                while (lead >= 0 && (value[lead] & 0xc0) == 0x80) lead--;
                if (lead >= 0) {
                    int first = value[lead] & 0xff;
                    int expected = first < 0x80 ? 1 : first < 0xe0 ? 2 : first < 0xf0 ? 3 : 4;
                    if (end - lead < expected) end = lead;
                }
            }
            return new String(value, 0, end, StandardCharsets.UTF_8);
        }
    }

    static String tailUtf8(String value, int limit) {
        if (limit <= 0) return "";
        if (value.length() > limit) {
            int start = value.length() - limit;
            if (Character.isLowSurrogate(value.charAt(start))) start++;
            value = value.substring(start);
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= limit) return value;
        int start = bytes.length - limit;
        while (start < bytes.length && (bytes[start] & 0xc0) == 0x80) start++;
        return new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8);
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String singleLine(String value, int limit) {
        if (value == null) return "unknown";
        return tailUtf8(value.replace('\n', ' ').replace('\r', ' '), limit);
    }
}
