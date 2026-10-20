#include <android/log.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

#include "bootstrap_log.h"

#define BLKROSET   0x125d
#define KSUD_STAGE "/data/user_de/0/df.root/ksud"
#define KSUD       "/data/adb/ksud"
#define PREFS_PATH "/data/user_de/0/df.root/shared_prefs/dfroot.xml"
#define MODULES_DIR "/data/adb/modules"

static int pref_true(const char *buf, const char *key)
{
    char needle[64];
    snprintf(needle, sizeof(needle), "name=\"%s\"", key);
    char *p = strstr(buf, needle);
    if (!p) return 0;
    char *tag_end = strchr(p, '>');
    char *v = strstr(p, "value=\"true\"");
    return v && tag_end && v < tag_end;
}

/* Read through EOF, including a probe beyond the buffer limit. A successful
 * short read does not establish that a file is complete. */
static int read_bounded(int fd, char *buf, size_t capacity, size_t *length)
{
    size_t used = 0;
    for (;;) {
        char extra;
        ssize_t n = read(fd, used < capacity - 1 ? buf + used : &extra,
                used < capacity - 1 ? capacity - 1 - used : 1);
        if (n < 0 && errno == EINTR)
            continue;
        if (n < 0)
            return -1;
        if (!n)
            break;
        if (used == capacity - 1) {
            errno = EOVERFLOW;
            return -1;
        }
        used += (size_t)n;
    }
    buf[used] = '\0';
    *length = used;
    return 0;
}

static int read_prefs(const char *path, char *su_manager, size_t su_manager_size, int *soft_reboot,
                      int *disable_modules)
{
    int fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
    if (fd < 0) {
        bootstrap_log_error("prefs", "open", errno);
        return -1;
    }

    char buf[4096];
    size_t n = 0;
    struct stat st;
    int error = 0;
    if (fstat(fd, &st) < 0)
        error = errno;
    else if (!S_ISREG(st.st_mode))
        error = EINVAL;
    else if (read_bounded(fd, buf, sizeof(buf), &n) < 0)
        error = errno;
    if (close(fd) < 0 && !error)
        error = errno;
    if (error) {
        bootstrap_log_error("prefs", "read_complete_file", error);
        return -1;
    }
    if (!n || memchr(buf, '\0', n) || !strstr(buf, "</map>")) {
        bootstrap_log(ANDROID_LOG_ERROR, "prefs", "invalid or incomplete preferences");
        return -1;
    }

    char *p = strstr(buf, "name=\"su_manager\">");
    if (!p) {
        bootstrap_log(ANDROID_LOG_ERROR, "prefs", "missing su_manager");
        return -1;
    }
    p += strlen("name=\"su_manager\">");
    char *end = strchr(p, '<');
    size_t len = end ? (size_t)(end - p) : 0;
    if (!end || len == 0 || len >= su_manager_size) {
        bootstrap_log(ANDROID_LOG_ERROR, "prefs", "invalid su_manager length");
        return -1;
    }
    memcpy(su_manager, p, len);
    su_manager[len] = '\0';

    *soft_reboot = pref_true(buf, "soft_reboot");
    *disable_modules = pref_true(buf, "disable_modules");

    bootstrap_log(ANDROID_LOG_INFO, "prefs", "complete soft_reboot=%d disable_modules=%d",
            *soft_reboot, *disable_modules);
    return 0;
}

static int adopt_zygote_env(void)
{
    FILE *f = popen("pidof zygote64 zygote", "r");
    if (!f) {
        bootstrap_log_error("environment", "popen", errno);
        return -1;
    }
    int pid = 0;
    errno = 0;
    int parsed = fscanf(f, "%d", &pid);
    int stream_error = ferror(f) ? (errno ? errno : EIO) : 0;
    int status = pclose(f);
    if (status < 0) {
        bootstrap_log_error("environment", "pclose", errno);
        return -1;
    }
    if (stream_error) {
        bootstrap_log_error("environment", "read_pid", stream_error);
        return -1;
    }
    if (!WIFEXITED(status) || WEXITSTATUS(status) != 0) {
        bootstrap_log(ANDROID_LOG_WARN, "environment", "pidof exit=%d signal=%d",
                WIFEXITED(status) ? WEXITSTATUS(status) : -1,
                WIFSIGNALED(status) ? WTERMSIG(status) : 0);
        return -1;
    }
    if (parsed != 1 || pid <= 0) {
        bootstrap_log(ANDROID_LOG_WARN, "environment", "pidof returned no valid pid");
        return -1;
    }

    char path[32];
    snprintf(path, sizeof(path), "/proc/%d/environ", pid);
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        bootstrap_log_error("environment", "open_environ", errno);
        return -1;
    }
    static char buf[16384];
    size_t n = 0;
    int error = read_bounded(fd, buf, sizeof(buf), &n) < 0 ? errno : 0;
    if (close(fd) < 0 && !error)
        error = errno;
    if (error) {
        bootstrap_log_error("environment", "read_environ", error);
        return -1;
    }
    if (!n || buf[n - 1] != '\0') {
        bootstrap_log(ANDROID_LOG_WARN, "environment", "empty or incomplete environment");
        return -1;
    }
    for (char *p = buf, *end = buf + n; p < end; p += strlen(p) + 1)
        if (*p && putenv(p) < 0) {
            bootstrap_log_error("environment", "putenv", errno);
            return -1;
        }
    bootstrap_log(ANDROID_LOG_INFO, "environment", "complete");
    return 0;
}

static int should_ro(const char *name)
{
    size_t len = strlen(name);
    if (!strcmp(name, "super"))  return 1;
    if (!strcmp(name, "misc"))   return 1;
    if (!strcmp(name, "steady")) return 1;
    if (len >= 2 && name[len - 2] == '_' &&
        (name[len - 1] == 'a' || name[len - 1] == 'b'))
        return 1;
    return 0;
}

static int set_partitions_ro(const char *directory)
{
    DIR *dir = opendir(directory);
    if (!dir) {
        bootstrap_log_error("partitions", "opendir", errno);
        return -1;
    }

    struct dirent *ent;
    unsigned attempted = 0, succeeded = 0, failed = 0;
    for (;;) {
        errno = 0;
        ent = readdir(dir);
        if (!ent) {
            if (errno) {
                bootstrap_log_error("partitions", "readdir", errno);
                ++failed;
            }
            break;
        }
        if (!should_ro(ent->d_name))
            continue;
        ++attempted;
        /* Entries in by-name are intentionally symlinks to block devices. */
        int fd = openat(dirfd(dir), ent->d_name, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        int error = fd < 0 ? errno : 0;
        struct stat st;
        const char *operation = "open";
        if (fd >= 0) {
            operation = "fstat";
            if (fstat(fd, &st) < 0)
                error = errno;
            else if (!S_ISBLK(st.st_mode))
                error = ENOTBLK;
            else {
                int on = 1;
                operation = "ioctl";
                if (ioctl(fd, BLKROSET, &on) < 0)
                    error = errno;
            }
            if (close(fd) < 0 && !error) {
                error = errno;
                operation = "close";
            }
        }
        if (error) {
            ++failed;
            bootstrap_log(ANDROID_LOG_WARN, "partitions", "entry=%s op=%s errno=%d (%s)",
                    ent->d_name, operation, error, strerror(error));
        } else
            ++succeeded;
    }
    if (closedir(dir) < 0) {
        bootstrap_log_error("partitions", "closedir", errno);
        ++failed;
    }
    bootstrap_log(failed ? ANDROID_LOG_WARN : ANDROID_LOG_INFO, "partitions",
            "complete attempted=%u succeeded=%u failed=%u", attempted, succeeded, failed);
    return failed ? -1 : 0;
}

/* Return -1 for an incomplete scan; it must not be mistaken for completion. */
static int late_load_running_at(const char *proc_dir)
{
    DIR *d = opendir(proc_dir);
    if (!d) {
        bootstrap_log_error("late_load_wait", "opendir", errno);
        return -1;
    }
    struct dirent *e;
    char buf[4096];
    int found = 0, error = 0;
    for (;;) {
        errno = 0;
        e = readdir(d);
        if (!e) {
            if (errno)
                error = errno;
            break;
        }
        char *end;
        strtol(e->d_name, &end, 10);
        if (end == e->d_name || *end)
            continue;
        int process = openat(dirfd(d), e->d_name,
                O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        if (process < 0) {
            if (errno != ENOENT && errno != ESRCH)
                error = errno;
            continue;
        }
        int fd = openat(process, "cmdline", O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC);
        int open_error = fd < 0 ? errno : 0;
        if (close(process) < 0)
            error = errno;
        if (fd < 0) {
            if (open_error != ENOENT && open_error != ESRCH)
                error = open_error;
            continue;
        }
        size_t n = 0;
        int read_error = read_bounded(fd, buf, sizeof(buf), &n) < 0 ? errno : 0;
        if (close(fd) < 0)
            error = errno;
        if (read_error) {
            if (read_error != ENOENT && read_error != ESRCH)
                error = read_error;
            continue;
        }
        for (size_t i = 0; i < n; i++)
            if (!buf[i]) buf[i] = ' ';
        if (strstr(buf, "ksud") && strstr(buf, "late-load")) {
            found = 1;
            break;
        }
    }
    if (closedir(d) < 0)
        error = errno;
    if (error)
        bootstrap_log_error("late_load_wait", "scan_incomplete", error);
    return found ? 1 : error ? -1 : 0;
}

static int late_load_running(void)
{
    return late_load_running_at("/proc");
}

static int wait_for_late_load(int (*scan)(void))
{
    struct timespec start, now;
    if (clock_gettime(CLOCK_MONOTONIC, &start) < 0) {
        bootstrap_log_error("late_load_wait", "clock", errno);
        return -1;
    }
    bootstrap_log(ANDROID_LOG_INFO, "late_load_wait", "begin timeout_seconds=60");
    for (;;) {
        int running = scan();
        if (clock_gettime(CLOCK_MONOTONIC, &now) < 0) {
            bootstrap_log_error("late_load_wait", "clock", errno);
            return -1;
        }
        if (now.tv_sec - start.tv_sec >= 60) {
            bootstrap_log(ANDROID_LOG_WARN, "late_load_wait",
                    "timeout; soft reboot skipped");
            return -1;
        }
        if (running <= 0) {
            bootstrap_log(running ? ANDROID_LOG_WARN : ANDROID_LOG_INFO,
                    "late_load_wait", running ? "unknown; soft reboot skipped" : "complete");
            return running;
        }
        struct timespec delay = { .tv_sec = 1 };
        /* An interruption returns to the monotonic deadline check. */
        if (nanosleep(&delay, NULL) < 0 && errno != EINTR) {
            bootstrap_log_error("late_load_wait", "sleep", errno);
            return -1;
        }
    }
}

static int run_ctx(const char *stage, const char *ctx, char *const argv[])
{
    bootstrap_log(ANDROID_LOG_INFO, stage, "launch executable=%s", argv[0]);
    bootstrap_log_sync();
    pid_t pid = fork();
    if (pid < 0) {
        bootstrap_log_error(stage, "fork", errno);
        return -1;
    }
    if (pid == 0) {
        if (ctx) {
            int fd = open("/proc/self/attr/exec", O_WRONLY | O_CLOEXEC);
            if (fd < 0) {
                bootstrap_log_error(stage, "context_open", errno);
                _exit(126);
            }
            ssize_t n;
            size_t length = strlen(ctx);
            do {
                n = write(fd, ctx, length);
            } while (n < 0 && errno == EINTR);
            int error = n < 0 ? errno : (size_t)n != length ? EIO : 0;
            if (close(fd) < 0 && !error)
                error = errno;
            if (error) {
                bootstrap_log_error(stage, "context_write_or_close", error);
                _exit(126);
            }
        }
        execv(argv[0], argv);
        bootstrap_log_error(stage, "exec", errno);
        _exit(127);
    }
    int status = 0;
    pid_t waited;
    do {
        waited = waitpid(pid, &status, 0);
    } while (waited < 0 && errno == EINTR);
    if (waited < 0) {
        bootstrap_log_error(stage, "waitpid", errno);
        return -1;
    }
    if (WIFEXITED(status)) {
        int result = WEXITSTATUS(status);
        bootstrap_log(result ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO,
                stage, "child=%ld launcher_exit=%d", (long)pid, result);
        return result;
    }
    if (WIFSIGNALED(status)) {
        bootstrap_log(ANDROID_LOG_ERROR, stage, "child=%ld signal=%d",
                (long)pid, WTERMSIG(status));
        return 128 + WTERMSIG(status);
    }
    bootstrap_log(ANDROID_LOG_ERROR, stage, "child=%ld unexpected_status=%d",
            (long)pid, status);
    return -1;
}

static void touch(const char *path)
{
    bootstrap_log_sync();
    int fd = open(path, O_CREAT | O_WRONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC, 0666);
    if (fd < 0)
        bootstrap_log_error("marker", path, errno);
    else if (close(fd) < 0)
        bootstrap_log_error("marker", "close", errno);
}

/* Mark every installed module disabled before ksud runs. A broken module
 * otherwise loads on the next boot and bootloops the device. */
static int disable_modules(const char *modules_dir)
{
    int root = open(modules_dir, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (root < 0) {
        int error = errno;
        if (error == ENOENT) {
            bootstrap_log(ANDROID_LOG_INFO, "modules", "directory absent; no modules to disable");
            return 0;
        }
        bootstrap_log_error("modules", "cannot open directory", error);
        return -1;
    }
    DIR *dir = fdopendir(root);
    if (!dir) {
        int error = errno;
        close(root);
        bootstrap_log_error("modules", "fdopendir", error);
        return -1;
    }

    struct dirent *ent;
    int result = 0;
    unsigned disabled = 0;
    for (;;) {
        errno = 0;
        ent = readdir(dir);
        if (!ent) {
            int error = errno;
            if (error) {
                bootstrap_log_error("modules", "readdir", error);
                result = -1;
            }
            break;
        }
        if (ent->d_name[0] == '.')
            continue;

        struct stat st;
        if (fstatat(dirfd(dir), ent->d_name, &st, AT_SYMLINK_NOFOLLOW) < 0) {
            int error = errno;
            bootstrap_log_error("modules", "inspect directory entry", error);
            result = -1;
            break;
        }
        if (!S_ISDIR(st.st_mode))
            continue;

        int module = openat(dirfd(dir), ent->d_name,
                O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        if (module < 0) {
            bootstrap_log_error("modules", "open module directory", errno);
            result = -1;
            break;
        }
        int fd = openat(module, "disable",
                O_CREAT | O_WRONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC, 0666);
        int error = fd < 0 ? errno : 0;
        if (fd >= 0) {
            if (fstat(fd, &st) < 0)
                error = errno;
            else if (!S_ISREG(st.st_mode) || st.st_nlink != 1)
                error = EINVAL;
            if (close(fd) < 0 && !error)
                error = errno;
        }
        if (close(module) < 0 && !error)
            error = errno;
        if (error) {
            bootstrap_log(ANDROID_LOG_ERROR, "modules",
                    "cannot disable entry=%s errno=%d (%s)",
                    ent->d_name, error, strerror(error));
            result = -1;
            break;
        }
        ++disabled;
    }
    if (closedir(dir) < 0) {
        bootstrap_log_error("modules", "closedir", errno);
        result = -1;
    }
    bootstrap_log(result ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO, "modules",
            "complete disabled=%u result=%d", disabled, result);
    return result;
}

int main(void)
{
    bootstrap_log_open(BOOTSTRAP_APP_DIR);
    bootstrap_log(ANDROID_LOG_INFO, "start", "begin");
    bootstrap_log(ANDROID_LOG_INFO, "prefs", "begin");
    touch("/dev/dfm1");
    char su_manager[256];
    int soft_reboot, disable_mods;
    if (read_prefs(PREFS_PATH, su_manager, sizeof(su_manager), &soft_reboot, &disable_mods) != 0) {
        touch("/dev/dfme0");
        return bootstrap_finish(1);
    }

    bootstrap_log(ANDROID_LOG_INFO, "environment", "begin");
    touch("/dev/dfm2");
    if (adopt_zygote_env())
        touch("/dev/dfmw0");

    bootstrap_log(ANDROID_LOG_INFO, "partitions", "begin");
    touch("/dev/dfm3");
    if (set_partitions_ro("/dev/block/by-name"))
        touch("/dev/dfmw1");

    if (disable_mods) {
        bootstrap_log(ANDROID_LOG_INFO, "modules", "begin");
        touch("/dev/dfm4");
        if (disable_modules(MODULES_DIR)) {
            touch("/dev/dfme1");
            return bootstrap_finish(1);
        }
    } else
        bootstrap_log(ANDROID_LOG_INFO, "modules", "skipped");

    touch("/dev/dfm5");
    if (run_ctx("late_load", NULL,
            (char *[]){ KSUD_STAGE, "late-load", "--package-name", su_manager, NULL }) != 0) {
        touch("/dev/dfme2");
        return bootstrap_finish(1);
    }
    /* Legacy marker reports launcher exit only, not daemon/root health. */
    bootstrap_log(ANDROID_LOG_INFO, "late_load", "launcher completed; daemon health unverified");
    touch("/dev/dfm6");
    if (soft_reboot) {
        if (wait_for_late_load(late_load_running) != 0)
            return bootstrap_finish(1);
        bootstrap_log(ANDROID_LOG_INFO, "soft_reboot", "requesting reboot; outcome unverified");
        bootstrap_log_sync();
        if (run_ctx("soft_reboot", "u:r:ksu:s0", (char *[]){ KSUD, "soft-reboot", NULL }) != 0)
            return bootstrap_finish(1);
    } else
        bootstrap_log(ANDROID_LOG_INFO, "soft_reboot", "skipped");

    return bootstrap_finish(0);
}
