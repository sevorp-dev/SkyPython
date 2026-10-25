// Minimal CPython executable. Built as "libpylauncher.so" so the packager puts it in
// jniLibs and Android extracts it to nativeLibraryDir, the only app location that is
// executable on Android 10+.
#include <Python.h>
#include <signal.h>

int main(int argc, char **argv) {
    // A child of the app inherits the signal mask of the Java thread that started it: ART
    // blocks SIGQUIT, SIGUSR1 and SIGPIPE (and may block SIGINT), and ignored signals stay
    // ignored across exec. A program expects the defaults: Ctrl+C / Interrupt raises
    // KeyboardInterrupt, and the debugger's pause (SIGUSR1) reaches it.
    sigset_t none;
    sigemptyset(&none);
    sigprocmask(SIG_SETMASK, &none, NULL);
    // Only inherited "ignore" dispositions: handlers present now are this process's own (the
    // crash reporter's, installed by the dynamic linker) and stay.
    for (int sig = 1; sig < 32; ++sig) {
        struct sigaction current;
        if (sig == SIGKILL || sig == SIGSTOP || sigaction(sig, NULL, &current) != 0) continue;
        if (current.sa_handler == SIG_IGN) signal(sig, SIG_DFL);
    }
    return Py_BytesMain(argc, argv);
}
