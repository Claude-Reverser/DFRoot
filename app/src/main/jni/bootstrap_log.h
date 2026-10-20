#ifndef DFROOT_BOOTSTRAP_LOG_H
#define DFROOT_BOOTSTRAP_LOG_H

#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#define BOOTSTRAP_LOG_LIMIT (64 * 1024)
#define BOOTSTRAP_APP_DIR "/data/user_de/0/df.root"
#define BOOTSTRAP_LOG_TRUNCATED \
    "bootstrap stage=diagnostics TRUNCATED: 64 KiB limit; further records in logcat\n"

static int bootstrap_log_fd = -1;
static int bootstrap_log_capped = 0;

/* The app creates this private file. Never create or follow a privileged
 * logging target, and never inherit its descriptor into another executable. */
static int bootstrap_log_open(const char *app_dir)
{
    int saved_errno = errno, error = 0;
    if (bootstrap_log_fd >= 0) {
        close(bootstrap_log_fd);
        bootstrap_log_fd = -1;
    }
    bootstrap_log_capped = 0;
    int directory = open(app_dir, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    int file = -1;
    struct stat st;
    uid_t owner = 0;
    if (directory < 0) {
        error = errno;
        goto done;
    }
    if (fstat(directory, &st) < 0) {
        error = errno;
        goto done;
    }
    owner = st.st_uid;
    if (!S_ISDIR(st.st_mode) || owner == 0) {
        error = EPERM;
        goto done;
    }
    const char *parts[] = { "files", "diagnostics", "current" };
    for (size_t i = 0; i < sizeof(parts) / sizeof(parts[0]); ++i) {
        int next = openat(directory, parts[i],
                O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        if (next < 0) {
            error = errno;
            goto done;
        }
        close(directory);
        directory = next;
        if (fstat(directory, &st) < 0) {
            error = errno;
            goto done;
        }
        if (!S_ISDIR(st.st_mode) || st.st_uid != owner) {
            error = EPERM;
            goto done;
        }
    }
    file = openat(directory, "bootstrap.log",
            O_WRONLY | O_APPEND | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC);
    if (file < 0) {
        error = errno;
        goto done;
    }
    if (fstat(file, &st) < 0) {
        error = errno;
        goto done;
    }
    if (!S_ISREG(st.st_mode) || st.st_uid != owner || st.st_nlink != 1 ||
            (st.st_mode & 07777) != 0600) {
        error = EPERM;
        goto done;
    }
    if (st.st_size < 0 || st.st_size >= BOOTSTRAP_LOG_LIMIT) {
        error = EFBIG;
        goto done;
    }
    bootstrap_log_fd = file;
    file = -1;
done:
    if (directory >= 0)
        close(directory);
    if (file >= 0)
        close(file);
    if (error)
        __android_log_print(ANDROID_LOG_WARN, "dfroot",
                "bootstrap stage=diagnostics op=open errno=%d (%s); logcat only",
                error, strerror(error));
    errno = saved_errno;
    return error ? -1 : 0;
}

static void bootstrap_log_sink_failed(const char *operation, int error)
{
    if (bootstrap_log_fd >= 0) {
        close(bootstrap_log_fd);
        bootstrap_log_fd = -1;
    }
    __android_log_print(ANDROID_LOG_WARN, "dfroot",
            "bootstrap stage=diagnostics op=%s errno=%d (%s); logcat only",
            operation, error, strerror(error));
}

static int bootstrap_log_write(const char *bytes, size_t length)
{
    size_t written = 0;
    while (written < length) {
        ssize_t n = write(bootstrap_log_fd, bytes + written, length - written);
        if (n < 0 && errno == EINTR)
            continue;
        if (n <= 0) {
            bootstrap_log_sink_failed("write", n < 0 ? errno : EIO);
            return -1;
        }
        written += (size_t)n;
    }
    return 0;
}

static void bootstrap_log(int priority, const char *stage, const char *format, ...)
{
    int saved_errno = errno;
    char message[768], line[1024];
    va_list args;
    va_start(args, format);
    vsnprintf(message, sizeof(message), format, args);
    va_end(args);
    /* Keep one bounded record per line, including names supplied by files. */
    for (char *p = message; *p; ++p)
        if ((unsigned char)*p < 32 || (unsigned char)*p == 127)
            *p = ' ';
    struct timespec now = {0};
    clock_gettime(CLOCK_MONOTONIC, &now);
    snprintf(line, sizeof(line), "bootstrap t=%lld.%03ld pid=%ld level=%d stage=%s %s\n",
            (long long)now.tv_sec, now.tv_nsec / 1000000,
            (long)getpid(), priority, stage, message);
    __android_log_print(priority, "dfroot", "%s", line);
    if (bootstrap_log_fd >= 0 && !bootstrap_log_capped) {
        struct stat st;
        size_t length = strlen(line);
        const size_t reserve = sizeof(BOOTSTRAP_LOG_TRUNCATED) - 1;
        if (fstat(bootstrap_log_fd, &st) < 0) {
            bootstrap_log_sink_failed("stat", errno);
        } else if (st.st_size < 0 || st.st_size > BOOTSTRAP_LOG_LIMIT ||
                length + reserve > BOOTSTRAP_LOG_LIMIT - (size_t)st.st_size) {
            bootstrap_log_capped = 1;
            if (st.st_size >= 0 && st.st_size <= BOOTSTRAP_LOG_LIMIT &&
                    reserve <= BOOTSTRAP_LOG_LIMIT - (size_t)st.st_size)
                bootstrap_log_write(BOOTSTRAP_LOG_TRUNCATED, reserve);
            __android_log_print(ANDROID_LOG_WARN, "dfroot",
                    "bootstrap stage=diagnostics limit reached; logcat only");
            /* Keep the descriptor so the last records can still be fsynced. */
        } else {
            bootstrap_log_write(line, length);
        }
    }
    errno = saved_errno;
}

static void bootstrap_log_error(const char *stage, const char *operation, int error)
{
    int saved_errno = errno;
    bootstrap_log(ANDROID_LOG_ERROR, stage, "op=%s errno=%d (%s)",
            operation, error, strerror(error));
    errno = saved_errno;
}

static void bootstrap_log_sync(void)
{
    int saved_errno = errno;
    if (bootstrap_log_fd >= 0) {
        int rc;
        do {
            rc = fsync(bootstrap_log_fd);
        } while (rc < 0 && errno == EINTR);
        if (rc < 0)
            bootstrap_log_sink_failed("sync", errno);
    }
    errno = saved_errno;
}

static int bootstrap_finish(int result)
{
    int saved_errno = errno;
    bootstrap_log(result ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO,
            "finish", "exit=%d", result);
    bootstrap_log_sync();
    if (bootstrap_log_fd >= 0) {
        int fd = bootstrap_log_fd;
        bootstrap_log_fd = -1;
        if (close(fd) < 0)
            bootstrap_log_error("diagnostics", "close", errno);
    }
    errno = saved_errno;
    return result;
}

#endif
