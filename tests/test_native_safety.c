#include "../app/src/main/jni/native_safety.h"

#include <assert.h>

static int open_fails, read_fails, close_status;
static int open_calls, read_calls, close_calls;
static const char *vendor_output;
static FILE stream_token;

static FILE *mock_open(const char *command, const char *mode)
{
    assert(strcmp(command, "getprop ro.vendor.build.version.release") == 0);
    assert(strcmp(mode, "r") == 0);
    open_calls++;
    return open_fails ? NULL : &stream_token;
}

static char *mock_read(char *buffer, int capacity, FILE *stream)
{
    assert(stream == &stream_token);
    read_calls++;
    if (read_fails) return NULL;
    snprintf(buffer, (size_t)capacity, "%s", vendor_output);
    return buffer;
}

static int mock_close(FILE *stream)
{
    assert(stream == &stream_token);
    close_calls++;
    return close_status;
}

static void reset_stream(void)
{
    open_fails = read_fails = close_status = 0;
    open_calls = read_calls = close_calls = 0;
    vendor_output = "14\n";
}

static int android_version(const char *release, int *version)
{
    return native_android_version(release, version, mock_open, mock_read, mock_close);
}

static void test_versions(void)
{
    int major = -1, minor = -1, version = -1;
    assert(native_kernel_version("5.15.123-android13-4", &major, &minor) == 0);
    assert(major == 5 && minor == 15);
    assert(native_kernel_version("6.0-custom", &major, &minor) == 0);
    assert(major == 6 && minor == 0);
    assert(native_kernel_version("invalid", &major, &minor) == -1);
    assert(native_kernel_version("6.", &major, &minor) == -1);
    assert(native_kernel_version("0.15", &major, &minor) == -1);
    assert(native_kernel_version("2147483648.15", &major, &minor) == -1);
    assert(native_kernel_version("999999999999999999999.15", &major, &minor) == -1);
    assert(native_kernel_version("6.999999999999999999999", &major, &minor) == -1);

    reset_stream();
    assert(android_version("5.15.123-android13-4", &version) == 0 && version == 13);
    assert(open_calls == 0);
    assert(android_version("5.15-android", &version) == -1);
    assert(android_version("5.15-android0", &version) == -1);
    assert(android_version("5.15-android999999999999999999999", &version) == -1);
    assert(open_calls == 0);

    reset_stream();
    assert(android_version("5.15-vendor", &version) == 0 && version == 14);
    assert(open_calls == 1 && read_calls == 1 && close_calls == 1);
    vendor_output = "12.1\n";
    assert(android_version("5.10-vendor", &version) == 0 && version == 12);
    vendor_output = "";
    assert(android_version("5.15-vendor", &version) == -1);
    vendor_output = "0";
    assert(android_version("5.15-vendor", &version) == -1);
    vendor_output = "-14";
    assert(android_version("5.15-vendor", &version) == -1);
    vendor_output = "unavailable";
    assert(android_version("5.15-vendor", &version) == -1);
    vendor_output = "999999999999999999999";
    assert(android_version("5.15-vendor", &version) == -1);
    vendor_output = "2147483648";
    assert(android_version("5.15-vendor", &version) == -1);

    reset_stream();
    open_fails = 1;
    assert(android_version("5.15-vendor", &version) == -1);
    assert(read_calls == 0 && close_calls == 0);
    reset_stream();
    read_fails = 1;
    assert(android_version("5.15-vendor", &version) == -1);
    assert(close_calls == 1);
    reset_stream();
    close_status = 1 << 8;
    assert(android_version("5.15-vendor", &version) == -1);
    reset_stream();
    close_status = -1;
    assert(android_version("5.15-vendor", &version) == -1);
    reset_stream();
    close_status = 9;
    assert(android_version("5.15-vendor", &version) == -1);
}

static int wait_interruptions, wait_error, wait_calls, child_status;

static pid_t mock_wait(pid_t pid, int *status, int options)
{
    assert(pid == 42 && options == 0);
    wait_calls++;
    if (wait_interruptions > 0) {
        wait_interruptions--;
        errno = EINTR;
        return -1;
    }
    if (wait_error) {
        errno = wait_error;
        return -1;
    }
    *status = child_status;
    return pid;
}

static void test_waiting(void)
{
    child_status = 0;
    wait_interruptions = 2;
    assert(native_wait_status(42, mock_wait) == 0);
    assert(wait_calls == 3);
    child_status = 7 << 8;
    int result = native_wait_status(42, mock_wait);
    assert(WIFEXITED(result) && WEXITSTATUS(result) == 7);
    child_status = 9;
    result = native_wait_status(42, mock_wait);
    assert(WIFSIGNALED(result));
    wait_error = ECHILD;
    assert(native_wait_status(42, mock_wait) == -1 && errno == ECHILD);
    wait_interruptions = 1;
    wait_error = EIO;
    assert(native_wait_status(42, mock_wait) == -1 && errno == EIO);
}

struct restore_mock {
    int first_result, second_result;
    int first_calls, second_calls;
};

static int mock_restore_first(void *context)
{
    struct restore_mock *state = context;
    assert(state->second_calls == 0);
    state->first_calls++;
    return state->first_result;
}

static int mock_restore_second(void *context)
{
    struct restore_mock *state = context;
    assert(state->first_calls == 1);
    state->second_calls++;
    return state->second_result;
}

static void test_restoration(void)
{
    struct restore_mock state = {0};
    assert(native_restore_in_order(mock_restore_first, mock_restore_second, &state)
           == NATIVE_RESTORE_OK);
    assert(state.first_calls == 1 && state.second_calls == 1);
    state = (struct restore_mock){ .first_result = -1 };
    assert(native_restore_in_order(mock_restore_first, mock_restore_second, &state)
           == NATIVE_RESTORE_FIRST_FAILED);
    assert(state.first_calls == 1 && state.second_calls == 0);
    state = (struct restore_mock){ .second_result = -1 };
    assert(native_restore_in_order(mock_restore_first, mock_restore_second, &state)
           == NATIVE_RESTORE_SECOND_FAILED);
    assert(state.first_calls == 1 && state.second_calls == 1);
    for (int operation = 0; operation <= 3; operation++) {
        assert(native_exit_status(operation, NATIVE_RESTORE_OK) == operation);
        assert(native_exit_status(operation, NATIVE_RESTORE_FIRST_FAILED) == 4);
        assert(native_exit_status(operation, NATIVE_RESTORE_SECOND_FAILED) == 4);
    }
}

int main(void)
{
    test_versions();
    test_waiting();
    test_restoration();
    puts("native safety tests passed (mock callbacks; no patching or privileged syscalls)");
    return 0;
}
