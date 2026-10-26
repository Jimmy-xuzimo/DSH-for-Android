/* Android install-directory launcher for bundled JavaScript package managers. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char **argv) {
    const char *native = getenv("DSH_ANDROID_NATIVE_DIR");
    const char *runtime = getenv("DSH_ANDROID_RUNTIME");
    if (!native || !runtime) { fputs("Missing Android runtime environment\n", stderr); return 126; }
    const char *script = strstr(argv[0], "pnpm") ? "node_modules/pnpm/bin/pnpm.cjs" :
        strstr(argv[0], "npx") ? "npm/bin/npx-cli.js" : "npm/bin/npm-cli.js";
    char node[4096], entry[4096];
    if (snprintf(node, sizeof(node), "%s/libdsh_node.so", native) >= (int)sizeof(node) ||
        snprintf(entry, sizeof(entry), "%s/%s", runtime, script) >= (int)sizeof(entry)) return 126;
    char **arguments = calloc((size_t)argc + 2, sizeof(char *));
    if (!arguments) return 126;
    arguments[0] = node;
    arguments[1] = entry;
    for (int i = 1; i < argc; ++i) arguments[i + 1] = argv[i];
    execv(node, arguments);
    perror("Cannot launch bundled package manager");
    free(arguments);
    return 126;
}
