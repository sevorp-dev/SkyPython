// Pseudo-terminal subprocesses for the Terminal and Interpreter screens (libskypty.so).
// Kotlin side: dev.skyloft.skypython.runtime.Pty. The approach follows the Apache-2.0
// terminal-emulator library (see NOTICE): open a PTY master, fork, make the slave the
// child's controlling terminal and stdio, exec.
#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#define UNUSED __attribute__((__unused__))

static void throw_io(JNIEnv *env, const char *what, int err) {
    char msg[256];
    snprintf(msg, sizeof msg, "%s: %s", what, strerror(err));
    jclass cls = (*env)->FindClass(env, "java/io/IOException");
    if (cls != NULL) (*env)->ThrowNew(env, cls, msg);
}

static void free_strings(char **v) {
    if (v == NULL) return;
    for (char **p = v; *p != NULL; ++p) free(*p);
    free(v);
}

/** A NULL-terminated copy of a Java String[]; NULL (with an exception pending) on failure. */
static char **to_strings(JNIEnv *env, jobjectArray array) {
    jsize n = array == NULL ? 0 : (*env)->GetArrayLength(env, array);
    char **v = calloc((size_t) n + 1, sizeof(char *));
    if (v == NULL) {
        throw_io(env, "calloc", ENOMEM);
        return NULL;
    }
    for (jsize i = 0; i < n; ++i) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, array, i);
        const char *utf = s == NULL ? NULL : (*env)->GetStringUTFChars(env, s, NULL);
        if (utf == NULL) {
            free_strings(v);
            if (!(*env)->ExceptionCheck(env)) throw_io(env, "GetStringUTFChars", ENOMEM);
            return NULL;
        }
        v[i] = strdup(utf);
        (*env)->ReleaseStringUTFChars(env, s, utf);
        (*env)->DeleteLocalRef(env, s);
        if (v[i] == NULL) {
            free_strings(v);
            throw_io(env, "strdup", ENOMEM);
            return NULL;
        }
    }
    return v;
}

static void set_size(int fd, jint rows, jint cols, jint cell_w, jint cell_h) {
    struct winsize sz = {
        .ws_row = (unsigned short) rows,
        .ws_col = (unsigned short) cols,
        .ws_xpixel = (unsigned short) (cols * cell_w),
        .ws_ypixel = (unsigned short) (rows * cell_h),
    };
    ioctl(fd, TIOCSWINSZ, &sz);
}

/** Closes every descriptor above 2 (the app's binder, sockets, files: not for the child). */
static void close_other_fds(void) {
#ifdef __NR_close_range
    if (syscall(__NR_close_range, 3, ~0U, 0) == 0) return; // Linux 5.9+
#endif
    DIR *dir = opendir("/proc/self/fd");
    if (dir == NULL) return;
    int dir_fd = dirfd(dir);
    struct dirent *e;
    while ((e = readdir(dir)) != NULL) {
        int fd = atoi(e->d_name);
        if (fd > 2 && fd != dir_fd) close(fd);
    }
    closedir(dir);
}

/** In the child: never returns. Errors before exec go to the terminal, then exit 127. */
static void exec_child(const char *pts_name, const char *cmd, const char *cwd, char **argv, char **envp) {
    // The app process blocks and ignores signals (ART ignores SIGPIPE, for one); a terminal
    // program expects the defaults. Handlers are reset by exec, ignored signals are not.
    sigset_t none;
    sigemptyset(&none);
    sigprocmask(SIG_SETMASK, &none, NULL);
    for (int sig = 1; sig < NSIG; ++sig) {
        if (sig != SIGKILL && sig != SIGSTOP) signal(sig, SIG_DFL);
    }

    setsid(); // new session and process group, no controlling terminal yet
    int pts = open(pts_name, O_RDWR);
    if (pts < 0) _exit(127);
    ioctl(pts, TIOCSCTTY, 0); // usually implied by the open above; explicit is portable
    dup2(pts, 0);
    dup2(pts, 1);
    dup2(pts, 2);
    if (pts > 2) close(pts);
    close_other_fds();

    if (chdir(cwd) != 0) {
        dprintf(2, "cd %s: %s\r\n", cwd, strerror(errno));
    }
    execve(cmd, argv, envp);
    dprintf(2, "Cannot run %s: %s\r\n", cmd, strerror(errno));
    _exit(127);
}

JNIEXPORT jint JNICALL Java_dev_skyloft_skypython_runtime_Pty_createSubprocess(
        JNIEnv *env, jclass UNUSED clazz, jstring jcmd, jstring jcwd, jobjectArray jargs,
        jobjectArray jenv, jintArray jpid, jint rows, jint cols, jint cell_w, jint cell_h) {
    if (jcmd == NULL || jcwd == NULL || jpid == NULL || (*env)->GetArrayLength(env, jpid) < 1) {
        throw_io(env, "createSubprocess", EINVAL);
        return -1;
    }

    // Everything the child needs is prepared here: after fork() only async-signal-safe calls.
    char **argv = to_strings(env, jargs);
    if (argv == NULL) return -1;
    char **envp = to_strings(env, jenv);
    if (envp == NULL) {
        free_strings(argv);
        return -1;
    }
    const char *cmd_utf = (*env)->GetStringUTFChars(env, jcmd, NULL);
    const char *cwd_utf = (*env)->GetStringUTFChars(env, jcwd, NULL);
    char *cmd = cmd_utf ? strdup(cmd_utf) : NULL;
    char *cwd = cwd_utf ? strdup(cwd_utf) : NULL;
    if (cmd_utf) (*env)->ReleaseStringUTFChars(env, jcmd, cmd_utf);
    if (cwd_utf) (*env)->ReleaseStringUTFChars(env, jcwd, cwd_utf);
    if (argv[0] == NULL && cmd != NULL) {
        // execve needs argv[0]; default to the command itself.
        free(argv);
        argv = calloc(2, sizeof(char *));
        if (argv != NULL) argv[0] = strdup(cmd);
    }

    int ptm = -1;
    pid_t pid = -1;
    char pts_name[64];
    int err = 0;
    const char *what = NULL;

    if (cmd == NULL || cwd == NULL || argv == NULL || argv[0] == NULL) {
        err = ENOMEM;
        what = "createSubprocess";
        goto done;
    }

    ptm = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (ptm < 0) {
        err = errno;
        what = "Cannot open /dev/ptmx";
        goto done;
    }
    if (grantpt(ptm) != 0 || unlockpt(ptm) != 0 || ptsname_r(ptm, pts_name, sizeof pts_name) != 0) {
        err = errno;
        what = "Cannot set up the pseudo-terminal";
        goto done;
    }

    // UTF-8 input (correct backspace over multi-byte characters); no XON/XOFF, so Ctrl+S
    // doesn't freeze the screen.
    struct termios tios;
    if (tcgetattr(ptm, &tios) == 0) {
        tios.c_iflag |= IUTF8;
        tios.c_iflag &= ~(IXON | IXOFF);
        tcsetattr(ptm, TCSANOW, &tios);
    }
    set_size(ptm, rows, cols, cell_w, cell_h);

    pid = fork();
    if (pid < 0) {
        err = errno;
        what = "fork";
        goto done;
    }
    if (pid == 0) exec_child(pts_name, cmd, cwd, argv, envp);

    jint out = (jint) pid;
    (*env)->SetIntArrayRegion(env, jpid, 0, 1, &out);

done:
    free(cmd);
    free(cwd);
    free_strings(argv);
    free_strings(envp);
    if (err != 0) {
        if (ptm >= 0) close(ptm);
        throw_io(env, what, err);
        return -1;
    }
    return ptm;
}

JNIEXPORT void JNICALL Java_dev_skyloft_skypython_runtime_Pty_setWindowSize(
        JNIEnv UNUSED *env, jclass UNUSED clazz, jint fd, jint rows, jint cols, jint cell_w, jint cell_h) {
    set_size(fd, rows, cols, cell_w, cell_h);
}

JNIEXPORT jint JNICALL Java_dev_skyloft_skypython_runtime_Pty_waitFor(
        JNIEnv UNUSED *env, jclass UNUSED clazz, jint pid) {
    int status;
    while (waitpid(pid, &status, 0) < 0) {
        if (errno != EINTR) return -1;
    }
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return -WTERMSIG(status);
    return 0;
}

JNIEXPORT jint JNICALL Java_dev_skyloft_skypython_runtime_Pty_sendSignal(
        JNIEnv UNUSED *env, jclass UNUSED clazz, jint pid, jint sig) {
    return kill(pid, sig) == 0 ? 0 : errno;
}

JNIEXPORT jint JNICALL Java_dev_skyloft_skypython_runtime_Pty_foregroundProcessGroup(
        JNIEnv UNUSED *env, jclass UNUSED clazz, jint fd) {
    return tcgetpgrp(fd);
}

JNIEXPORT void JNICALL Java_dev_skyloft_skypython_runtime_Pty_close(
        JNIEnv UNUSED *env, jclass UNUSED clazz, jint fd) {
    close(fd);
}
