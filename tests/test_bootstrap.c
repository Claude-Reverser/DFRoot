/* Host-only: main is renamed and never called. Process/context/ioctl actions
 * are injected; filesystem operations use only a private temporary directory. */
#include <android/log.h>
#include <assert.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <setjmp.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static pid_t test_fork(void);
static pid_t test_waitpid(pid_t pid, int *status, int options);
static int test_execv(const char *path, char *const argv[]);
static _Noreturn void test_exit(int code);
static int test_open(const char *path, int flags, ...);
static int test_close(int fd);
static ssize_t test_write(int fd, const void *buffer, size_t count);
static ssize_t test_read(int fd, void *buffer, size_t count);
static int test_fstat(int fd, struct stat *st);
static int test_fstatat(int fd, const char *path, struct stat *st, int flags);
static struct dirent *test_readdir(DIR *directory);
static int test_ioctl(int fd, unsigned long request, ...);
static int test_clock_gettime(clockid_t clock, struct timespec *time);
static int test_nanosleep(const struct timespec *delay, struct timespec *remaining);
#define fork test_fork
#define waitpid test_waitpid
#define execv test_execv
#define _exit test_exit
#define open test_open
#define close test_close
#define write test_write
#define read test_read
#define fstat test_fstat
#define fstatat test_fstatat
#define readdir test_readdir
#define ioctl test_ioctl
#define clock_gettime test_clock_gettime
#define nanosleep test_nanosleep
#define main bootstrap_main
#include "../app/src/main/jni/bootstrap.c"
#undef main
#undef fork
#undef waitpid
#undef execv
#undef _exit
#undef open
#undef close
#undef write
#undef read
#undef fstat
#undef fstatat
#undef readdir
#undef ioctl
#undef clock_gettime
#undef nanosleep

static char logs[32768];
static int fork_result = 42, wait_mode, wait_calls, child_exit = 7;
static int context_open_error, context_write_mode, context_write_calls, context_close_error;
static int exec_calls, intercepted_exit, read_mode, read_calls, close_error;
static int fake_block, ioctl_error, ioctl_calls, fake_clock, clock_error, sleep_calls;
static int statat_error, readdir_error;
static time_t fake_seconds;
static jmp_buf child_jump;
#define CONTEXT_FD 9000

int __android_log_print(int priority, const char *tag, const char *format, ...)
{
    (void)priority;
    (void)tag;
    size_t used = strlen(logs);
    va_list args;
    va_start(args, format);
    vsnprintf(logs + used, sizeof(logs) - used, format, args);
    va_end(args);
    return 0;
}

static pid_t test_fork(void)
{
    if (fork_result < 0)
        errno = EAGAIN;
    return fork_result;
}

static pid_t test_waitpid(pid_t pid, int *status, int options)
{
    assert(pid == 42 && options == 0);
    ++wait_calls;
    if (wait_mode == 1 && wait_calls == 1) {
        errno = EINTR;
        return -1;
    }
    if (wait_mode == 2) {
        errno = ECHILD;
        return -1;
    }
    *status = wait_mode == 3 ? SIGTERM : child_exit << 8;
    return pid;
}

static int test_execv(const char *path, char *const argv[])
{
    assert(path == argv[0]);
    ++exec_calls;
    errno = ENOENT;
    return -1;
}

static _Noreturn void test_exit(int code)
{
    intercepted_exit = code;
    longjmp(child_jump, 1);
}

static int test_open(const char *path, int flags, ...)
{
    if (!strcmp(path, "/proc/self/attr/exec")) {
        if (context_open_error) {
            errno = context_open_error;
            return -1;
        }
        assert(flags & O_CLOEXEC);
        return CONTEXT_FD;
    }
    if (flags & O_CREAT) {
        va_list args;
        va_start(args, flags);
        mode_t mode = va_arg(args, int);
        va_end(args);
        return open(path, flags, mode);
    }
    return open(path, flags);
}

static int test_close(int fd)
{
    if (fd == CONTEXT_FD) {
        if (context_close_error) {
            errno = context_close_error;
            return -1;
        }
        return 0;
    }
    int rc = close(fd);
    if (close_error) {
        errno = close_error;
        close_error = 0;
        return -1;
    }
    return rc;
}

static ssize_t test_write(int fd, const void *buffer, size_t count)
{
    if (fd == CONTEXT_FD) {
        ++context_write_calls;
        if (context_write_mode == 1 && context_write_calls == 1) {
            errno = EINTR;
            return -1;
        }
        if (context_write_mode == 2)
            return (ssize_t)count - 1;
        return (ssize_t)count;
    }
    return write(fd, buffer, count);
}

static ssize_t test_read(int fd, void *buffer, size_t count)
{
    ++read_calls;
    if (read_mode == 1 && read_calls == 1) {
        errno = EINTR;
        return -1;
    }
    if (read_mode == 1 && count > 3)
        count = 3;
    if (read_mode == 2) {
        errno = EIO;
        return -1;
    }
    return read(fd, buffer, count);
}

static int test_fstat(int fd, struct stat *st)
{
    int rc = fstat(fd, st);
    if (!rc && fake_block)
        st->st_mode = (st->st_mode & 07777) | S_IFBLK;
    return rc;
}

static int test_fstatat(int fd, const char *path, struct stat *st, int flags)
{
    if (statat_error) {
        errno = statat_error;
        return -1;
    }
    return fstatat(fd, path, st, flags);
}

static struct dirent *test_readdir(DIR *directory)
{
    if (readdir_error) {
        errno = readdir_error;
        return NULL;
    }
    return readdir(directory);
}

static int test_ioctl(int fd, unsigned long request, ...)
{
    (void)fd;
    assert(request == BLKROSET && fake_block);
    ++ioctl_calls;
    if (ioctl_error) {
        errno = ioctl_error;
        return -1;
    }
    return 0;
}

static int test_clock_gettime(clockid_t clock, struct timespec *time)
{
    if (clock_error) {
        errno = EIO;
        return -1;
    }
    if (fake_clock) {
        time->tv_sec = fake_seconds;
        time->tv_nsec = 0;
        return 0;
    }
    return clock_gettime(clock, time);
}

static int test_nanosleep(const struct timespec *delay, struct timespec *remaining)
{
    (void)remaining;
    assert(fake_clock && delay->tv_sec == 1);
    ++sleep_calls;
    fake_seconds += 20;
    errno = EINTR; /* Even repeated interruptions cannot defeat the deadline. */
    return -1;
}

static void write_file(const char *path, const void *data, size_t size)
{
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    assert(fd >= 0);
    assert(write(fd, data, size) == (ssize_t)size);
    assert(close(fd) == 0);
}

static void test_processes(void)
{
    char *const argv[] = { "/nonexistent-test-executable", NULL };
    logs[0] = 0;
    assert(run_ctx("test", NULL, argv) == 7 && strstr(logs, "launcher_exit=7"));
    wait_mode = 1;
    wait_calls = 0;
    child_exit = 0;
    assert(run_ctx("test", NULL, argv) == 0 && wait_calls == 2);
    wait_mode = 2;
    logs[0] = 0;
    assert(run_ctx("test", NULL, argv) == -1 && strstr(logs, "op=waitpid"));
    assert(!strstr(logs, "launcher_exit="));
    wait_mode = 3;
    logs[0] = 0;
    assert(run_ctx("test", NULL, argv) == 128 + SIGTERM && strstr(logs, "signal="));
    fork_result = -1;
    logs[0] = 0;
    assert(run_ctx("test", NULL, argv) == -1 && strstr(logs, "op=fork"));

    fork_result = 0;
    logs[0] = 0;
    if (setjmp(child_jump) == 0) {
        run_ctx("test", NULL, argv);
        assert(0);
    }
    assert(intercepted_exit == 127 && exec_calls == 1 && strstr(logs, "op=exec"));
    context_open_error = EACCES;
    logs[0] = 0;
    if (setjmp(child_jump) == 0) {
        run_ctx("test", "test-context", argv);
        assert(0);
    }
    assert(intercepted_exit == 126 && exec_calls == 1 && strstr(logs, "context_open"));
    context_open_error = 0;
    context_write_mode = 2;
    logs[0] = 0;
    if (setjmp(child_jump) == 0) {
        run_ctx("test", "test-context", argv);
        assert(0);
    }
    assert(intercepted_exit == 126 && exec_calls == 1 && strstr(logs, "context_write"));
    context_write_mode = 1;
    context_write_calls = 0;
    if (setjmp(child_jump) == 0) {
        run_ctx("test", "test-context", argv);
        assert(0);
    }
    assert(intercepted_exit == 127 && exec_calls == 2 && context_write_calls == 2);
    context_write_mode = 0;
    context_close_error = EIO;
    if (setjmp(child_jump) == 0) {
        run_ctx("test", "test-context", argv);
        assert(0);
    }
    assert(intercepted_exit == 126 && exec_calls == 2);
    context_close_error = 0;
}

static void test_preferences(const char *root)
{
    char path[512], manager[256];
    int reboot = -1, modules = -1;
    snprintf(path, sizeof(path), "%s/prefs", root);
    const char valid[] = "<map><string name=\"su_manager\">example.manager</string>"
            "<boolean name=\"soft_reboot\" value=\"true\" />"
            "<boolean name=\"disable_modules\" value=\"true\" /></map>";
    write_file(path, valid, strlen(valid));
    read_mode = 1;
    read_calls = 0;
    assert(read_prefs(path, manager, sizeof(manager), &reboot, &modules) == 0);
    assert(!strcmp(manager, "example.manager") && reboot == 1 && modules == 1 && read_calls > 2);
    read_mode = 2;
    close_error = EPIPE;
    logs[0] = 0;
    assert(read_prefs(path, manager, sizeof(manager), &reboot, &modules) == -1);
    char expected[32];
    snprintf(expected, sizeof(expected), "errno=%d ", EIO);
    assert(strstr(logs, expected)); /* close failure must not replace read errno. */
    read_mode = 0;
    char large[4096];
    memset(large, ' ', sizeof(large));
    memcpy(large, valid, strlen(valid));
    write_file(path, large, sizeof(large) - 1);
    assert(read_prefs(path, manager, sizeof(manager), &reboot, &modules) == 0);
    write_file(path, large, sizeof(large));
    assert(read_prefs(path, manager, sizeof(manager), &reboot, &modules) == -1);
    write_file(path, valid, strlen(valid) - 6);
    assert(read_prefs(path, manager, sizeof(manager), &reboot, &modules) == -1);
    write_file(path, valid, 0);
    assert(read_prefs(path, manager, sizeof(manager), &reboot, &modules) == -1);
    write_file(path, valid, sizeof(valid)); /* Embedded NUL is not XML. */
    assert(read_prefs(path, manager, sizeof(manager), &reboot, &modules) == -1);
    assert(unlink(path) == 0 && mkfifo(path, 0600) == 0);
    assert(read_prefs(path, manager, sizeof(manager), &reboot, &modules) == -1);
    assert(unlink(path) == 0);
    assert(read_prefs(path, manager, sizeof(manager), &reboot, &modules) == -1);
}

static void test_partitions(const char *root)
{
    char path[512];
    snprintf(path, sizeof(path), "%s/super", root);
    write_file(path, "", 0);
    logs[0] = 0;
    assert(set_partitions_ro(root) == -1); /* Ordinary files cannot be block devices. */
    assert(strstr(logs, "attempted=1 succeeded=0 failed=1") && ioctl_calls == 0);
    fake_block = 1;
    logs[0] = 0;
    assert(set_partitions_ro(root) == 0 && ioctl_calls == 1);
    assert(strstr(logs, "attempted=1 succeeded=1 failed=0"));
    ioctl_error = EACCES;
    logs[0] = 0;
    assert(set_partitions_ro(root) == -1 && strstr(logs, "op=ioctl"));
    ioctl_error = 0;
    fake_block = 0;
    assert(unlink(path) == 0);
    assert(symlink("missing-device", path) == 0);
    logs[0] = 0;
    assert(set_partitions_ro(root) == -1 && strstr(logs, "op=open"));
    assert(unlink(path) == 0);
}

static void test_module_errors(const char *root)
{
    char module[512], marker[1024];
    snprintf(module, sizeof(module), "%s/module", root);
    snprintf(marker, sizeof(marker), "%s/disable", module);
    assert(mkdir(module, 0700) == 0);
    readdir_error = EIO;
    assert(disable_modules(root) == -1);
    readdir_error = 0;
    statat_error = EIO;
    assert(disable_modules(root) == -1);
    statat_error = 0;
    assert(access(marker, F_OK) == -1 && errno == ENOENT);
    close_error = EIO;
    assert(disable_modules(root) == -1); /* A failed marker close is not success. */
    assert(unlink(marker) == 0 && rmdir(module) == 0);
}

static int scan_result, scan_calls, scan_advance;
static int injected_scan(void)
{
    ++scan_calls;
    fake_seconds += scan_advance;
    return scan_result;
}

static void test_process_scan(const char *root)
{
    char process[512], cmdline[1024];
    snprintf(process, sizeof(process), "%s/123", root);
    snprintf(cmdline, sizeof(cmdline), "%s/cmdline", process);
    assert(mkdir(process, 0700) == 0);
    const char running[] = "/data/adb/ksud\0late-load\0";
    write_file(cmdline, running, sizeof(running));
    assert(late_load_running_at(root) == 1);
    write_file(cmdline, "ordinary-process", 16);
    assert(late_load_running_at(root) == 0);
    assert(chmod(cmdline, 0000) == 0);
    assert(late_load_running_at(root) == -1);
    assert(chmod(cmdline, 0600) == 0 && unlink(cmdline) == 0);
    assert(late_load_running_at(root) == 0); /* A vanished process is expected. */
    assert(rmdir(process) == 0);
    assert(late_load_running_at(process) == -1);

    fake_clock = 1;
    fake_seconds = 0;
    scan_result = 1;
    scan_calls = sleep_calls = 0;
    logs[0] = 0;
    assert(wait_for_late_load(injected_scan) == -1);
    assert(fake_seconds == 60 && sleep_calls == 3 && scan_calls == 4);
    assert(strstr(logs, "timeout; soft reboot skipped"));
    scan_result = -1;
    scan_calls = sleep_calls = 0;
    logs[0] = 0;
    assert(wait_for_late_load(injected_scan) == -1 && scan_calls == 1 && sleep_calls == 0);
    assert(strstr(logs, "unknown; soft reboot skipped"));
    scan_result = 0;
    assert(wait_for_late_load(injected_scan) == 0);
    scan_advance = 61;
    assert(wait_for_late_load(injected_scan) == -1); /* Late scan success is expired. */
    scan_advance = 0;
    clock_error = 1;
    assert(wait_for_late_load(injected_scan) == -1);
    clock_error = fake_clock = 0;
}

int main(void)
{
    assert(geteuid() != 0);
    char root[] = "/tmp/dfroot-bootstrap-XXXXXX";
    assert(mkdtemp(root));
    test_processes();
    test_preferences(root);
    test_partitions(root);
    test_module_errors(root);
    test_process_scan(root);
    assert(rmdir(root) == 0);
    puts("bootstrap stage and process tests passed");
    return 0;
}
