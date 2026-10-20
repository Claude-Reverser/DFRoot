#ifndef DFROOT_NATIVE_SAFETY_H
#define DFROOT_NATIVE_SAFETY_H

#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <sys/wait.h>

/* These helpers are independent of patching and accept callbacks for host tests. */
static inline int native_decimal(const char *text, const char **end, int *value)
{
    if (*text < '0' || *text > '9') return -1;
    errno = 0;
    char *tail;
    long parsed = strtol(text, &tail, 10);
    if (errno == ERANGE || parsed > INT_MAX) return -1;
    *end = tail;
    *value = (int)parsed;
    return 0;
}

static inline int native_kernel_version(const char *release, int *major, int *minor)
{
    const char *end;
    int parsed_major, parsed_minor;
    if (native_decimal(release, &end, &parsed_major) || *end != '.' ||
        native_decimal(end + 1, &end, &parsed_minor) || parsed_major == 0)
        return -1;
    *major = parsed_major;
    *minor = parsed_minor;
    return 0;
}

static inline int native_android_version(
        const char *release, int *android_version,
        FILE *(*open_command)(const char *, const char *),
        char *(*read_line)(char *, int, FILE *),
        int (*close_command)(FILE *))
{
    const char *version = strstr(release, "android");
    char vendor_version[64];
    if (version) {
        version += strlen("android");
    } else {
        FILE *stream = open_command("getprop ro.vendor.build.version.release", "r");
        if (!stream) return -1;
        char *read_result = read_line(vendor_version, sizeof(vendor_version), stream);
        int close_result = close_command(stream);
        if (!read_result || close_result != 0) return -1;
        version = vendor_version;
    }
    const char *end;
    int parsed;
    if (native_decimal(version, &end, &parsed) || parsed == 0) return -1;
    *android_version = parsed;
    return 0;
}

/* Return a real child status only after the requested child has been reaped. */
static inline int native_wait_status(
        pid_t pid, pid_t (*wait_child)(pid_t, int *, int))
{
    int status;
    pid_t result;
    do {
        result = wait_child(pid, &status, 0);
    } while (result < 0 && errno == EINTR);
    if (result < 0) return -1;
    if (result != pid) {
        errno = ECHILD;
        return -1;
    }
    return status;
}

enum native_restore_result {
    NATIVE_RESTORE_OK = 0,
    NATIVE_RESTORE_FIRST_FAILED,
    NATIVE_RESTORE_SECOND_FAILED
};

/* The second restoration is safe only after the first has succeeded. */
static inline int native_restore_in_order(
        int (*restore_first)(void *), int (*restore_second)(void *), void *context)
{
    if (restore_first(context)) return NATIVE_RESTORE_FIRST_FAILED;
    if (restore_second(context)) return NATIVE_RESTORE_SECOND_FAILED;
    return NATIVE_RESTORE_OK;
}

static inline int native_exit_status(int operation_status, int restore_status)
{
    return restore_status == NATIVE_RESTORE_OK ? operation_status : 4;
}

#endif
