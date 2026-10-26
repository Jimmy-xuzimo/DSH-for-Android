package com.dshmobile.shell

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NativeRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun binary(name: String) = File(context.applicationInfo.nativeLibraryDir, "libdsh_$name.so")

    @Test fun androidKeystoreProtectsThePersistentRuntimeKey() {
        val first = KeyVault(context).runtimeKey()
        assertEquals(first, KeyVault(context).runtimeKey())
        assertEquals(32, android.util.Base64.decode(first, android.util.Base64.DEFAULT).size)
        val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertNull(store.getKey("dsh-runtime-v1", null).encoded)
        val wrapped = File(context.noBackupFilesDir, "runtime-key.wrapped").readBytes()
        assertFalse(String(wrapped, Charsets.ISO_8859_1).contains(first))
    }

    @Test fun installedBashExecutesAndWritableElfIsRejected() {
        val bash = binary("bash")
        assertTrue(bash.canExecute())
        val process = ProcessBuilder(bash.path, "--noprofile", "--norc", "-c", "printf DSH_BASH_OK")
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        assertEquals(output, 0, process.exitValue())
        assertEquals("DSH_BASH_OK", output)
        val writable = File(context.filesDir, "probe-elf")
        try {
            bash.copyTo(writable, overwrite = true)
            assertTrue(writable.setExecutable(true, true))
            assertThrows(java.io.IOException::class.java) {
                ProcessBuilder(writable.path, "-c", "exit 0").start()
            }
        } finally { writable.delete() }
    }

    @Test fun installedNodeRunsAndroidAndSpawnsBash() {
        val script = "const p=require('node:child_process').spawnSync(process.argv[1],['-c','printf DSH_CHILD_OK'],{encoding:'utf8'});console.log(JSON.stringify({platform:process.platform,arch:process.arch,node:process.version,status:p.status,out:p.stdout}));if(p.status!==0)process.exit(1)"
        val child = ProcessBuilder(binary("node").path, "-e", script, binary("bash").path).redirectErrorStream(true)
        child.environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
        val process = child.start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(20, TimeUnit.SECONDS))
        assertEquals(output, 0, process.exitValue())
        assertTrue(output, output.contains("\"platform\":\"android\""))
        assertTrue(output, output.contains("DSH_CHILD_OK"))
    }

    @Test fun bundledGitSearchAndPackageManagersUseTheSameWorkspace() {
        val runtime = RuntimeInstaller(context).install()
        val project = File(context.cacheDir, "native-tool-test").apply { mkdirs() }
        val script = """
            import {spawnSync} from 'node:child_process';
            import {writeFileSync,readFileSync,existsSync} from 'node:fs';
            import {createRequire} from 'node:module';
            const require=createRequire(process.env.DSH_ANDROID_RUNTIME+'/package.json');
            const publication=require(process.env.DSH_ANDROID_RUNTIME+'/android/android-posix.node');
            writeFileSync('publish-source','original');publication.publishExclusive('publish-source','publish-target');
            if(existsSync('publish-source')||readFileSync('publish-target','utf8')!=='original')throw Error('publication failed');
            writeFileSync('publish-source','replacement');let collision=false;
            try{publication.publishExclusive('publish-source','publish-target')}catch(error){collision=error.code==='EEXIST'}
            if(!collision||readFileSync('publish-target','utf8')!=='original'||!existsSync('publish-source'))throw Error('publication clobbered an existing target');
            function run(command,args){const p=spawnSync(command,args,{encoding:'utf8'});if(p.status!==0)throw Error(command+': status='+p.status+' signal='+p.signal+' '+p.stderr);return p.stdout;}
            run('git',['init']);run('git',['config','user.email','test@example.invalid']);run('git',['config','user.name','Runtime test']);
            writeFileSync('hello.txt','你好 DSH_SEARCH_OK\n');run('git',['add','hello.txt']);run('git',['commit','-m','runtime test']);
            if(!run('rg',['--json','DSH_SEARCH_OK','.']).includes('DSH_SEARCH_OK'))throw Error('search failed');
            run('git',['config','--unset','user.name']);run('git',['config','--unset','user.email']);
            run('git',['clone','--no-hardlinks','--','.','clone-without-identity']);
            if(readFileSync('clone-without-identity/hello.txt','utf8')!==readFileSync('hello.txt','utf8'))throw Error('clone contents differ');
            if(run('git',['-C','clone-without-identity','log','-1','--format=%an']).trim()!=='Runtime test')throw Error('clone changed commit authorship');
            console.log(run('git',['status','--porcelain']));console.log('NPM',run('npm',['--version']).trim());console.log('PNPM',run('pnpm',['--version']).trim());
        """.trimIndent()
        val builder = ProcessBuilder(binary("node").path, "--import", File(runtime, "android/native-loader.mjs").path, "--input-type=module", "-e", script)
            .directory(project).redirectErrorStream(true)
        builder.environment().apply {
            put("DSH_ANDROID_NATIVE_DIR", context.applicationInfo.nativeLibraryDir)
            put("DSH_ANDROID_RUNTIME", runtime.path)
            put("HOME", project.path); put("TMPDIR", context.cacheDir.path)
            put("PATH", File(runtime, "bin").path + ":/system/bin")
            put("SHELL", binary("bash").path); put("LD_LIBRARY_PATH", context.applicationInfo.nativeLibraryDir)
            put("GIT_EXEC_PATH", File(runtime, "bin").path)
        }
        try {
            val process = builder.start()
            val output = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor(40, TimeUnit.SECONDS))
            assertEquals(output, 0, process.exitValue())
            assertTrue(output, output.contains("PNPM 11.28.5"))
        } finally { project.deleteRecursively() }
    }

    @Test fun packagedPtyHandlesChineseInputAndResize() {
        val runtime = RuntimeInstaller(context).install()
        val script = """
            import {createRequire} from 'node:module';
            const require=createRequire(import.meta.resolve('@deepseek-ai/dsh-subprocess-local'));
            const pty=require('node-pty');
            const child=pty.spawn(process.env.SHELL,['--noprofile','--norc','-c','printf PTY_READY; read -r line; printf "GOT:%s" "${'$'}line"'],{name:'xterm-256color',cols:80,rows:24,cwd:process.cwd(),env:process.env});
            let data='',sent=false;
            child.onData(chunk=>{data+=chunk;if(!sent&&data.includes('PTY_READY')){sent=true;child.resize(100,30);child.write(String.fromCodePoint(0x4f60,0x597d,10));}});
            child.onExit(({exitCode})=>{console.log(data);process.exit(exitCode===0&&data.includes('GOT:你好')?0:1)});
            setTimeout(()=>{child.kill();process.exit(2)},10000).unref();
        """.trimIndent()
        val builder = ProcessBuilder(binary("node").path, "--import", File(runtime, "android/preload.mjs").path, "--input-type=module", "-e", script)
            .directory(runtime).redirectErrorStream(true)
        builder.environment().apply {
            put("DSH_ANDROID_NATIVE_DIR", context.applicationInfo.nativeLibraryDir)
            put("DSH_ANDROID_RUNTIME", runtime.path)
            put("DSH_ANDROID_TOKEN", "a".repeat(64))
            put("DSH_ANDROID_COMMANDS", "1")
            put("SHELL", binary("bash").path)
            put("LANG", "C.UTF-8"); put("LC_ALL", "C.UTF-8")
            put("LD_LIBRARY_PATH", context.applicationInfo.nativeLibraryDir)
            put("HOME", context.filesDir.path)
            put("PATH", File(runtime, "bin").path + ":/system/bin")
        }
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(20, TimeUnit.SECONDS))
        assertEquals(output, 0, process.exitValue())
    }
}
