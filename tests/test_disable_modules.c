/* Compile the real bootstrap helper with an Android log stub. The bootstrap
 * entry point is renamed and never invoked; all filesystem work uses mkdtemp. */
#define main bootstrap_main
#include "../app/src/main/jni/bootstrap.c"
#undef main

#include <assert.h>
#include <stdarg.h>

static char last_error[1024];

int __android_log_print(int priority, const char *tag, const char *format, ...)
{
    (void)tag;
    va_list args;
    va_start(args, format);
    if (priority == ANDROID_LOG_ERROR)
        vsnprintf(last_error, sizeof(last_error), format, args);
    va_end(args);
    return 0;
}

static void path_join(char *out, size_t size, const char *directory, const char *name)
{
    int length = snprintf(out, size, "%s/%s", directory, name);
    assert(length >= 0 && (size_t)length < size);
}

int main(void)
{
    assert(geteuid() != 0); /* Permission-denial checks require an ordinary user. */
    char temporary[] = "/tmp/dfroot-modules-XXXXXX";
    char *root = mkdtemp(temporary);
    assert(root);
    char modules[512], module[512], marker[512], other[512];
    path_join(modules, sizeof(modules), root, "modules");

    /* A fresh installation has no module directory and must not create one. */
    assert(disable_modules(modules) == 0);
    assert(access(modules, F_OK) < 0 && errno == ENOENT);
    assert(mkdir(modules, 0700) == 0);
    assert(disable_modules(modules) == 0);

    /* Real module directories get markers; unrelated files are ignored. */
    path_join(module, sizeof(module), modules, "example");
    path_join(marker, sizeof(marker), module, "disable");
    path_join(other, sizeof(other), modules, "metadata");
    assert(mkdir(module, 0700) == 0);
    int fd = open(other, O_CREAT | O_WRONLY, 0600);
    assert(fd >= 0 && close(fd) == 0);
    assert(disable_modules(modules) == 0);
    struct stat st;
    assert(stat(marker, &st) == 0 && S_ISREG(st.st_mode));
    assert(disable_modules(modules) == 0); /* Existing markers are accepted. */
    assert(unlink(marker) == 0);

    /* Failed marker creation must stop startup and retain the specific error. */
    assert(chmod(module, 0500) == 0);
    last_error[0] = '\0';
    assert(disable_modules(modules) == -1);
    assert(strstr(last_error, "cannot disable") && strstr(last_error, strerror(EACCES)));
    assert(chmod(module, 0700) == 0);
    assert(rmdir(module) == 0);
    assert(unlink(other) == 0);

    /* A legal maximum-length module name must not truncate its marker path. */
    char name[NAME_MAX + 1];
    memset(name, 'a', NAME_MAX);
    name[NAME_MAX] = '\0';
    path_join(module, sizeof(module), modules, name);
    path_join(marker, sizeof(marker), module, "disable");
    assert(mkdir(module, 0700) == 0);
    assert(disable_modules(modules) == 0);
    assert(stat(marker, &st) == 0 && S_ISREG(st.st_mode));
    assert(unlink(marker) == 0);
    assert(rmdir(module) == 0);

    /* Directory-open failures are distinct from an absent directory. */
    assert(chmod(modules, 0000) == 0);
    last_error[0] = '\0';
    assert(disable_modules(modules) == -1);
    assert(strstr(last_error, "cannot open") && strstr(last_error, strerror(EACCES)));
    assert(chmod(modules, 0700) == 0);
    assert(rmdir(modules) == 0);
    fd = open(modules, O_CREAT | O_WRONLY, 0600);
    assert(fd >= 0 && close(fd) == 0);
    last_error[0] = '\0';
    assert(disable_modules(modules) == -1);
    assert(strstr(last_error, strerror(ENOTDIR)));
    assert(unlink(modules) == 0);
    assert(rmdir(root) == 0);
    puts("disable_modules filesystem tests passed");
    return 0;
}
