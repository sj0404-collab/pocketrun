// The PocketRun native Node launcher.
//
// nodejs-mobile ships libnode.so, not a `node` executable. This is the smallest
// possible executable on top of it: hand argv straight to Node's embedder entry
// point and let it run one script to completion, exactly like `node main.js`.
// The Kotlin side execs this binary from nativeLibraryDir, so every run is a
// real, isolated OS process: a crash cannot take the app down, output is a
// normal pipe/file, and cancel() can actually kill it (unlike the in-process
// Rhino and Python runtimes).
#include <android/log.h>
#include "node.h"

extern "C" int main(int argc, char** argv) {
    int code = node::Start(argc, argv);
    return code;
}
