package com.ubuntuterminal

import android.os.Bundle
import android.os.Build
import android.system.Os
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var dlBtn: Button
    private lateinit var prootBtn: Button

    private val supportDir: File get() = File(filesDir, "support")
    private val filesystemDir: File get() = File(filesDir, "ubuntu")
    private var inProot = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        scroll = ScrollView(this)
        output = TextView(this)
        output.textSize = 12f
        output.typeface = android.graphics.Typeface.MONOSPACE
        output.setPadding(16, 16, 16, 16)
        output.setTextIsSelectable(true)
        scroll.addView(output)

        input = EditText(this)
        input.hint = "type command, tap Send"
        input.setSingleLine()
        input.setTextSize(12f)
        input.typeface = android.graphics.Typeface.MONOSPACE
        input.imeOptions = EditorInfo.IME_ACTION_SEND
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT

        val send = Button(this)
        send.text = "Send"
        send.setOnClickListener { runCmd() }

        dlBtn = Button(this)
        dlBtn.text = "DL Rootfs"
        dlBtn.isEnabled = false
        dlBtn.setOnClickListener { downloadAll() }

        prootBtn = Button(this)
        prootBtn.text = "Proot OFF"
        prootBtn.isEnabled = false
        prootBtn.setOnClickListener { toggleProot() }

        val btnRow = LinearLayout(this)
        btnRow.orientation = LinearLayout.HORIZONTAL
        btnRow.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        btnRow.addView(send)
        btnRow.addView(dlBtn)
        btnRow.addView(prootBtn)

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        layout.addView(btnRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(layout)

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { runCmd(); true } else false
        }
        input.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) { runCmd(); true } else false
        }

        doSetup()
    }

    private fun append(text: String) {
        runOnUiThread {
            output.append("$text\n")
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    // === Replicate UlaFiles methods ===

    private fun setupLinks() {
        // Replicate UlaFiles.setupLinks() exactly
        supportDir.mkdirs()
        val libDir = applicationInfo.nativeLibraryDir
        libDir.listFiles()?.forEach { libFile ->
            var libFileName = libFile.name
            if (libFileName.startsWith("lib_proot.") ||
                libFileName.startsWith("lib_libtalloc") ||
                libFileName.startsWith("lib_loader")) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (libFileName.endsWith(".a10.so")) {
                        libFileName = libFileName.replace(".a10.so", ".so")
                    } else {
                        return@forEach
                    }
                } else {
                    if (libFileName.endsWith(".a10.so")) {
                        return@forEach
                    }
                }
            }
            if (!libFileName.startsWith("lib_")) return@forEach
            val name = libFileName.removePrefix("lib_").removeSuffix(".so")
            val linkFile = File(supportDir, name)
            linkFile.delete()
            try {
                Os.symlink(libFile.absolutePath, linkFile.absolutePath)
            } catch (e: Exception) {
                libFile.copyTo(linkFile, overwrite = true)
                makePermissionsUsable(supportDir.absolutePath, name)
            }
        }
    }

    private fun makePermissionsUsable(containingDirectoryPath: String, filename: String) {
        // Replicate UlaFiles.makePermissionsUsable() exactly
        val containingDirectory = File(containingDirectoryPath)
        containingDirectory.mkdirs()
        val pb = ProcessBuilder("chmod", "0777", filename)
        pb.directory(containingDirectory)
        val process = pb.start()
        process.waitFor()
    }

    // === Core logic ===

    private fun doSetup() {
        Thread {
            try {
                val abi = getAbi()
                append("Arch: $abi")
                append("Files dir: ${filesDir.absolutePath}")

                setupLinks()

                val busybox = File(supportDir, "busybox")
                val proot = File(supportDir, "proot")
                val loader = File(supportDir, "loader")
                val execScript = File(supportDir, "execInProot.sh")

                if (busybox.exists() && proot.exists() && loader.exists() && execScript.exists()) {
                    append("Support files: OK")
                    runOnUiThread { dlBtn.isEnabled = true }

                    if (File(filesystemDir, "rootfs/bin/sh").exists()) {
                        append("Rootfs: extracted")
                        runOnUiThread { prootBtn.isEnabled = true }
                    } else {
                        append("Rootfs: not found — tap DL Rootfs")
                    }
                } else {
                    append("Support files: missing — tap DL Rootfs to install")
                    runOnUiThread { dlBtn.isEnabled = true }
                }
            } catch (e: Exception) {
                append("SETUP ERROR: $e")
                Log.e("UbuntuTerminal", "setup", e)
            }
        }.start()
    }

    private fun downloadAll() {
        dlBtn.isEnabled = false
        dlBtn.text = "DL..."
        Thread {
            try {
                val abi = getAbi()
                val supportAbi = abi // arm64-v8a for UserLAnd-Assets-Support
                val distroArch = abi.removeSuffix("-v8a") // arm64 for UserLAnd-Assets-Ubuntu

                // 1. Download support assets from UserLAnd-Assets-Support
                if (!File(supportDir, "busybox").exists()) {
                    append("Downloading support assets...")
                    val url = "https://github.com/CypherpunkArmory/UserLAnd-Assets-Support/releases/download/v1.5.1/$supportAbi-assets.zip"
                    val tmp = File(filesDir, "support-assets.zip")
                    URL(url).openStream().use { inp -> FileOutputStream(tmp).use { inp.copyTo(it) } }
                    append("  downloaded ${tmp.length()} bytes")

                    supportDir.mkdirs()
                    ProcessBuilder("/system/bin/unzip", "-o", tmp.absolutePath, "-d", supportDir.absolutePath)
                        .start().waitFor()
                    tmp.delete()

                    // Fix execInProot.sh shebang for our package
                    fixShebang()

                    // Make binaries executable using UserLAnd's approach
                    supportDir.listFiles()?.forEach { f ->
                        if (f.isFile) makePermissionsUsable(supportDir.absolutePath, f.name)
                    }

                    // Create symlinks from jniLibs (like UlaFiles.setupLinks)
                    setupLinks()

                    append("  extracted support files")
                }

                // 2. Download Ubuntu assets from UserLAnd-Assets-Ubuntu
                if (!File(supportDir, "userland_profile.sh").exists()) {
                    append("Downloading Ubuntu assets...")
                    val url = "https://github.com/CypherpunkArmory/UserLAnd-Assets-Ubuntu/releases/download/v0.0.12/$distroArch-assets.tar.gz"
                    val tmp = File(filesDir, "ubuntu-assets.tar.gz")
                    URL(url).openStream().use { inp -> FileOutputStream(tmp).use { inp.copyTo(it) } }
                    append("  downloaded ${tmp.length()} bytes")

                    ProcessBuilder("/system/bin/tar", "xf", tmp.absolutePath, "-C", supportDir.absolutePath)
                        .start().waitFor()
                    tmp.delete()

                    fixShebang()

                    supportDir.listFiles()?.forEach { f ->
                        if (f.isFile) makePermissionsUsable(supportDir.absolutePath, f.name)
                    }

                    // Copy distro scripts into support/common/ (referenced by extractFilesystem.sh)
                    val commonDir = File(supportDir, "common")
                    commonDir.mkdirs()
                    listOf("addNonRootUser.sh", "busybox_static", "extractFilesystem.sh",
                        "compressFilesystem.sh").forEach { name ->
                        val src = File(supportDir, name)
                        val dst = File(commonDir, name)
                        if (src.exists()) {
                            src.copyTo(dst, overwrite = true)
                            makePermissionsUsable(commonDir.absolutePath, name)
                        }
                    }

                    // Copy support files into filesystemDir/support/ (bind-mounted as /support in proot)
                    val rootfsSupport = File(filesystemDir, "support")
                    rootfsSupport.mkdirs()
                    supportDir.listFiles()?.forEach { f ->
                        if (f.isFile) {
                            f.copyTo(File(rootfsSupport, f.name), overwrite = true)
                            makePermissionsUsable(rootfsSupport.absolutePath, f.name)
                        }
                    }

                    append("  extracted Ubuntu assets")
                }

                // 3. Download rootfs tarball
                filesystemDir.mkdirs()
                val rootfsTarball = File(filesystemDir, "rootfs.tar.gz")
                if (!File(filesystemDir, "rootfs/bin/sh").exists() && !rootfsTarball.exists()) {
                    append("Downloading Ubuntu rootfs...")
                    val url = "https://github.com/CypherpunkArmory/UserLAnd-Assets-Ubuntu/releases/download/v0.0.12/$distroArch-rootfs.tar.gz"
                    append("  $url")
                    URL(url).openStream().use { inp -> FileOutputStream(rootfsTarball).use { inp.copyTo(it) } }
                    append("  downloaded ${rootfsTarball.length()} bytes")
                }

                // 4. Extract rootfs
                if (!File(filesystemDir, "rootfs/bin/sh").exists() && rootfsTarball.exists()) {
                    append("Extracting rootfs...")
                    val rootfsDir = File(filesystemDir, "rootfs")
                    rootfsDir.mkdirs()
                    File(rootfsDir, "tmp").mkdirs()

                    val p = ProcessBuilder("/system/bin/tar", "xf", rootfsTarball.absolutePath,
                        "--exclude", "sys", "--exclude", "dev", "--exclude", "proc",
                        "--exclude", "support", "--exclude", "mnt",
                        "-C", rootfsDir.absolutePath)
                        .start()
                    p.waitFor()

                    val resolv = File(rootfsDir, "etc/resolv.conf")
                    resolv.parentFile?.mkdirs()
                    resolv.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")

                    if (File(rootfsDir, "bin/sh").exists()) {
                        append("  rootfs extracted OK")

                        // Copy support scripts into rootfs/support/ for extraction
                        val rootfsSupport = File(filesystemDir, "rootfs/support")
                        rootfsSupport.mkdirs()
                        supportDir.listFiles()?.forEach { f ->
                            if (f.isFile) {
                                f.copyTo(File(rootfsSupport, f.name), overwrite = true)
                                makePermissionsUsable(rootfsSupport.absolutePath, f.name)
                            }
                        }
                        val rootfsCommon = File(rootfsSupport, "common")
                        File(supportDir, "common").copyRecursively(rootfsCommon, overwrite = true)
                        rootfsCommon.listFiles()?.forEach { f ->
                            if (f.isFile) makePermissionsUsable(rootfsCommon.absolutePath, f.name)
                        }

                        // Create extraction success marker
                        File(rootfsSupport, ".success_filesystem_extraction").createNewFile()
                        rootfsTarball.delete()
                    } else {
                        append("  rootfs extraction failed")
                    }
                }

                append("")
                append("Setup complete!")
                runOnUiThread {
                    prootBtn.isEnabled = true
                    dlBtn.text = "Redownload"
                    dlBtn.isEnabled = true
                }
            } catch (e: Exception) {
                append("DOWNLOAD ERROR: $e")
                Log.e("UbuntuTerminal", "download", e)
                runOnUiThread {
                    dlBtn.text = "DL Rootfs"
                    dlBtn.isEnabled = true
                }
            }
        }.start()
    }

    private fun fixShebang() {
        val execScript = File(supportDir, "execInProot.sh")
        if (execScript.exists()) {
            val content = execScript.readText()
            if (content.contains("tech.ula")) {
                execScript.writeText(content.replace(
                    "#!/data/data/tech.ula/files/support/busybox",
                    "#!${supportDir.absolutePath}/busybox"
                ))
            }
        }
    }

    private fun toggleProot() {
        inProot = !inProot
        prootBtn.text = if (inProot) "Proot ON" else "Proot OFF"
        append(if (inProot) "Proot mode ON — commands run inside Ubuntu" else "Proot mode OFF")
    }

    private fun runCmd() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.text.clear()
        append("$ $text")
        Thread {
            try {
                val pb: ProcessBuilder
                if (inProot && File(filesystemDir, "rootfs/bin/sh").exists()) {
                    val busybox = File(supportDir, "busybox")
                    val execScript = File(supportDir, "execInProot.sh")
                    val cmd = listOf(busybox.absolutePath, "sh", execScript.absolutePath) + text.split(" ")

                    pb = ProcessBuilder(cmd)
                    pb.directory(filesDir)
                    pb.redirectErrorStream(true)

                    val env = pb.environment()
                    env["ROOT_PATH"] = filesDir.absolutePath
                    env["ROOTFS_PATH"] = filesystemDir.absolutePath
                    env["LIB_PATH"] = supportDir.absolutePath
                    env["PROOT_DEBUG_LEVEL"] = "-1"
                    env["LD_LIBRARY_PATH"] = supportDir.absolutePath
                } else {
                    pb = ProcessBuilder("/system/bin/sh", "-c", text)
                    pb.directory(filesDir)
                }
                val p = pb.start()
                val out = p.inputStream.bufferedReader().readText().trim()
                val code = p.waitFor()
                if (out.isNotEmpty()) append(out)
                append("[exit $code]")
            } catch (e: Exception) {
                append("ERROR: $e")
            }
        }.start()
    }

    private fun getAbi(): String {
        val abi = Build.CPU_ABI?.lowercase() ?: Build.SUPPORTED_ABIS.firstOrNull()?.lowercase() ?: "arm64-v8a"
        return when {
            abi.contains("arm64") -> "arm64-v8a"
            abi.contains("armeabi") -> "armeabi-v7a"
            abi.contains("x86_64") -> "x86_64"
            abi.contains("x86") -> "x86"
            else -> "arm64-v8a"
        }
    }
}
