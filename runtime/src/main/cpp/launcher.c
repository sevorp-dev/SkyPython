// Minimal CPython executable. Built as "libpylauncher.so" so the packager puts it in
// jniLibs and Android extracts it to nativeLibraryDir, the only app location that is
// executable on Android 10+.
#include <Python.h>
#include <signal.h>

int main(int argc, char **argv) {
    // A child of the app may inherit a blocked SIGINT from the Java thread that started it;
    // unblocked, Ctrl+C / Interrupt raises KeyboardInterrupt as on a desktop.
    sigset_t sigint;
    sigemptyset(&sigint);
    sigaddset(&sigint, SIGINT);
    sigprocmask(SIG_UNBLOCK, &sigint, NULL);
    return Py_BytesMain(argc, argv);
}
