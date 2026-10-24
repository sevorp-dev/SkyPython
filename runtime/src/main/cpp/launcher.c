// Minimal CPython executable. Built as "libpylauncher.so" so the packager puts it in
// jniLibs and Android extracts it to nativeLibraryDir, the only app location that is
// executable on Android 10+.
#include <Python.h>

int main(int argc, char **argv) {
    return Py_BytesMain(argc, argv);
}
