/* MIT. Atomic no-replace publication for Android, where app hardlinks are denied. */
#include <node_api.h>
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

static napi_value publish(napi_env env, napi_callback_info info) {
    napi_value args[2], result;
    size_t count = 2, lengths[2];
    char *paths[2] = {NULL, NULL};
    napi_get_cb_info(env, info, &count, args, NULL, NULL);
    if (count != 2) { napi_throw_type_error(env, NULL, "Expected source and destination"); return NULL; }
    for (int i = 0; i < 2; i++) {
        if (napi_get_value_string_utf8(env, args[i], NULL, 0, &lengths[i]) != napi_ok || lengths[i] > 4095) {
            free(paths[0]); napi_throw_type_error(env, NULL, "Invalid publication path"); return NULL;
        }
        paths[i] = malloc(lengths[i] + 1);
        if (!paths[i]) { free(paths[0]); napi_throw_error(env, NULL, "Out of memory"); return NULL; }
        napi_get_value_string_utf8(env, args[i], paths[i], lengths[i] + 1, &lengths[i]);
        if (strlen(paths[i]) != lengths[i]) { free(paths[0]); free(paths[1]); napi_throw_type_error(env, NULL, "NUL in path"); return NULL; }
    }
    int status = (int)syscall(SYS_renameat2, AT_FDCWD, paths[0], AT_FDCWD, paths[1], 1 /* RENAME_NOREPLACE */);
    int saved = errno;
    free(paths[0]); free(paths[1]);
    if (status != 0) {
        const char *code = saved == EEXIST ? "EEXIST" : saved == ENOSPC ? "ENOSPC" : saved == EACCES ? "EACCES" : saved == ENOENT ? "ENOENT" : saved == EXDEV ? "EXDEV" : "EIO";
        napi_throw_error(env, code, strerror(saved)); return NULL;
    }
    napi_get_undefined(env, &result); return result;
}
static napi_value initialize(napi_env env, napi_value exports) {
    napi_value fn; napi_create_function(env, "publishExclusive", NAPI_AUTO_LENGTH, publish, NULL, &fn);
    napi_set_named_property(env, exports, "publishExclusive", fn); return exports;
}
NAPI_MODULE(NODE_GYP_MODULE_NAME, initialize)
