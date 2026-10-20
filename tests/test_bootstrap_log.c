/* Host-only tests: all persistent paths live below mkdtemp. */
#include <android/log.h>
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

static ssize_t log_test_write(int fd, const void *buf, size_t count);
static int log_test_fsync(int fd);
static int log_test_fstat(int fd, struct stat *st);
#define write log_test_write
#define fsync log_test_fsync
#define fstat log_test_fstat
#include "../app/src/main/jni/bootstrap_log.h"
#undef write
#undef fsync
#undef fstat

static char captured[8192];
static int write_mode, write_calls, sync_mode, stat_calls, change_owner_at;

int __android_log_print(int priority, const char *tag, const char *format, ...)
{
    (void)priority;
    (void)tag;
    va_list args;
    va_start(args, format);
    size_t used = strlen(captured);
    vsnprintf(captured + used, sizeof(captured) - used, format, args);
    va_end(args);
    errno = ENOTTY; /* Logging must not clobber its caller's errno. */
    return 0;
}

static ssize_t log_test_write(int fd, const void *buf, size_t count)
{
    ++write_calls;
    if (write_mode == 1 && write_calls == 1) {
        errno = EINTR;
        return -1;
    }
    if (write_mode == 1 && write_calls == 2 && count > 7)
        count = 7;
    if (write_mode == 2) {
        errno = ENOSPC;
        return -1;
    }
    if (write_mode == 3)
        return 0;
    return write(fd, buf, count);
}

static int log_test_fsync(int fd)
{
    if (sync_mode == 1) {
        sync_mode = 0;
        errno = EINTR;
        return -1;
    }
    if (sync_mode == 2) {
        errno = EIO;
        return -1;
    }
    return fsync(fd);
}

static int log_test_fstat(int fd, struct stat *st)
{
    int rc = fstat(fd, st);
    ++stat_calls;
    if (!rc && stat_calls == change_owner_at)
        st->st_uid = change_owner_at == 1 ? 0 : st->st_uid + 1;
    return rc;
}

static void join(char *out, size_t capacity, const char *parent, const char *name)
{
    int n = snprintf(out, capacity, "%s/%s", parent, name);
    assert(n > 0 && (size_t)n < capacity);
}

static void create_private(const char *path)
{
    int fd = open(path, O_WRONLY | O_CREAT | O_EXCL, 0600);
    assert(fd >= 0 && close(fd) == 0);
}

static void close_sink(void)
{
    if (bootstrap_log_fd >= 0)
        assert(close(bootstrap_log_fd) == 0);
    bootstrap_log_fd = -1;
}

int main(void)
{
    assert(geteuid() != 0);
    char root[] = "/tmp/dfroot-log-XXXXXX";
    assert(mkdtemp(root));
    char dirs[3][512], leaf[512], alternative[1024];
    join(dirs[0], sizeof(dirs[0]), root, "files");
    join(dirs[1], sizeof(dirs[1]), dirs[0], "diagnostics");
    join(dirs[2], sizeof(dirs[2]), dirs[1], "current");
    for (size_t i = 0; i < 3; ++i)
        assert(mkdir(dirs[i], 0700) == 0);
    join(leaf, sizeof(leaf), dirs[2], "bootstrap.log");
    errno = E2BIG;
    assert(bootstrap_log_open(root) == -1 && errno == E2BIG);
    assert(access(leaf, F_OK) == -1 && errno == ENOENT); /* Never creates. */
    create_private(leaf);

    errno = E2BIG;
    assert(bootstrap_log_open(root) == 0 && errno == E2BIG);
    assert(fcntl(bootstrap_log_fd, F_GETFD) & FD_CLOEXEC);
    assert(fcntl(bootstrap_log_fd, F_GETFL) & O_NONBLOCK);
    assert(fcntl(bootstrap_log_fd, F_GETFL) & O_APPEND);
    write_mode = 1;
    write_calls = 0;
    bootstrap_log(ANDROID_LOG_INFO, "test", "partial writes\nremain one record");
    assert(errno == E2BIG && write_calls >= 3);
    sync_mode = 1;
    bootstrap_log_sync();
    assert(errno == E2BIG && bootstrap_log_fd >= 0);
    write_mode = 0;
    close_sink();
    int fd = open(leaf, O_RDONLY);
    char contents[2048];
    ssize_t n = read(fd, contents, sizeof(contents) - 1);
    assert(n > 0 && close(fd) == 0);
    contents[n] = 0;
    assert(strstr(contents, "stage=test partial writes remain one record\n"));

    /* Reject root/wrong owners at every pinned component and at the leaf. */
    for (int component = 1; component <= 5; ++component) {
        stat_calls = 0;
        change_owner_at = component;
        assert(bootstrap_log_open(root) == -1 && bootstrap_log_fd == -1);
    }
    change_owner_at = 0;
    for (size_t i = 0; i < 3; ++i) {
        snprintf(alternative, sizeof(alternative), "%s.saved", dirs[i]);
        assert(rename(dirs[i], alternative) == 0);
        assert(symlink(alternative, dirs[i]) == 0);
        assert(bootstrap_log_open(root) == -1);
        assert(unlink(dirs[i]) == 0 && rename(alternative, dirs[i]) == 0);
    }
    snprintf(alternative, sizeof(alternative), "%s.link", root);
    assert(symlink(root, alternative) == 0);
    assert(bootstrap_log_open(alternative) == -1);
    assert(unlink(alternative) == 0);
    assert(chmod(leaf, 0644) == 0);
    assert(bootstrap_log_open(root) == -1);
    assert(chmod(leaf, 0600) == 0);
    join(alternative, sizeof(alternative), dirs[2], "other");
    assert(link(leaf, alternative) == 0);
    assert(bootstrap_log_open(root) == -1);
    assert(unlink(alternative) == 0);
    assert(rename(leaf, alternative) == 0 && symlink(alternative, leaf) == 0);
    assert(bootstrap_log_open(root) == -1);
    assert(unlink(leaf) == 0 && rename(alternative, leaf) == 0);
    assert(unlink(leaf) == 0 && mkfifo(leaf, 0600) == 0);
    assert(bootstrap_log_open(root) == -1);
    int reader = open(leaf, O_RDONLY | O_NONBLOCK);
    assert(reader >= 0 && bootstrap_log_open(root) == -1);
    assert(close(reader) == 0 && unlink(leaf) == 0);
    assert(mkdir(leaf, 0700) == 0 && bootstrap_log_open(root) == -1);
    assert(rmdir(leaf) == 0);
    create_private(leaf);

    /* Reserve a visible truncation notice and retain the fd for final fsync. */
    assert(bootstrap_log_open(root) == 0);
    fd = open(leaf, O_WRONLY);
    off_t initial_size = BOOTSTRAP_LOG_LIMIT - sizeof(BOOTSTRAP_LOG_TRUNCATED);
    assert(fd >= 0 && ftruncate(fd, initial_size) == 0 && close(fd) == 0);
    bootstrap_log(ANDROID_LOG_INFO, "limit", "record would cross the cap");
    assert(bootstrap_log_fd >= 0 && bootstrap_log_capped);
    struct stat st;
    assert(stat(leaf, &st) == 0 && st.st_size == BOOTSTRAP_LOG_LIMIT - 1);
    fd = open(leaf, O_RDONLY);
    assert(fd >= 0 && lseek(fd, initial_size, SEEK_SET) == initial_size);
    n = read(fd, contents, sizeof(contents) - 1);
    assert(n > 0 && close(fd) == 0);
    contents[n] = 0;
    assert(!strcmp(contents, BOOTSTRAP_LOG_TRUNCATED));
    bootstrap_log(ANDROID_LOG_INFO, "limit", "this record remains in logcat");
    assert(stat(leaf, &st) == 0 && st.st_size == BOOTSTRAP_LOG_LIMIT - 1);
    bootstrap_log_sync();
    assert(bootstrap_log_fd >= 0);
    close_sink();
    fd = open(leaf, O_WRONLY);
    assert(fd >= 0 && ftruncate(fd, BOOTSTRAP_LOG_LIMIT) == 0 && close(fd) == 0);
    assert(bootstrap_log_open(root) == -1);
    fd = open(leaf, O_WRONLY | O_TRUNC);
    assert(fd >= 0 && close(fd) == 0);

    for (int mode = 2; mode <= 3; ++mode) {
        assert(bootstrap_log_open(root) == 0);
        write_mode = mode;
        captured[0] = 0;
        errno = EDOM;
        bootstrap_log_error("test", "operation", EACCES);
        assert(errno == EDOM && bootstrap_log_fd == -1);
        assert(strstr(captured, "stage=test") && strstr(captured, "logcat only"));
    }
    write_mode = 0;
    assert(bootstrap_log_open(root) == 0);
    sync_mode = 2;
    errno = EDOM;
    bootstrap_log_sync();
    assert(errno == EDOM && bootstrap_log_fd == -1);
    sync_mode = 0;
    assert(bootstrap_log_open(root) == 0);
    assert(bootstrap_finish(1) == 1 && bootstrap_log_fd == -1);
    assert(unlink(leaf) == 0);
    for (int i = 2; i >= 0; --i)
        assert(rmdir(dirs[i]) == 0);
    assert(rmdir(root) == 0);
    puts("bootstrap persistent logging tests passed");
    return 0;
}
