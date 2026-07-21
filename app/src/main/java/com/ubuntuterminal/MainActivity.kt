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

    private fun doSetup() {
        Thread {
            try {
                val abi = getAbi()
                append("Arch: $abi")
                append("Files dir: ${filesDir.absolutePath}")

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

                // 1. Download support assets (proot, loader, busybox, scripts) from UserLAnd-Assets-Support
                if (!File(supportDir, "busybox").exists()) {
                    append("Downloading support assets...")
                    val supportUrl = "https://github.com/CypherpunkArmory/UserLAnd-Assets-Support/releases/download/v1.5.1/$abi-assets.zip"
                    val supportTmp = File(filesDir, "support-assets.zip")
                    URL(supportUrl).openStream().use { input -> FileOutputStream(supportTmp).use { input.copyTo(it) } }
                    append("  downloaded ${supportTmp.length()} bytes")

                    supportDir.mkdirs()
                    val p = ProcessBuilder("/system/bin/unzip", "-o", supportTmp.absolutePath, "-d", supportDir.absolutePath)
                        .start()
                    p.waitFor()
                    supportTmp.delete()

                    // Fix execInProot.sh shebang for our package
                    val execScript = File(supportDir, "execInProot.sh")
                    if (execScript.exists()) {
                        val content = execScript.readText()
                        execScript.writeText(content.replace(
                            "#!/data/data/tech.ula/files/support/busybox",
                            "#!${supportDir.absolutePath}/busybox"
                        ))
                    }

                    // Make all support files executable
                    ProcessBuilder("chmod", "-R", "755", supportDir.absolutePath)
                        .start().waitFor()

                    append("  extracted support files")
                }

                // 2. Download Ubuntu assets (distro-specific scripts) from UserLAnd-Assets-Ubuntu
                val ubuntuScripts = File(supportDir, "userland_profile.sh")
                if (!ubuntuScripts.exists()) {
                    append("Downloading Ubuntu assets...")
                    val ubuntuUrl = "https://github.com/CypherpunkArmory/UserLAnd-Assets-Ubuntu/releases/download/v0.0.12/$abi-assets.tar.gz"
                    val ubuntuTmp = File(filesDir, "ubuntu-assets.tar.gz")
                    URL(ubuntuUrl).openStream().use { input -> FileOutputStream(ubuntuTmp).use { input.copyTo(it) } }
                    append("  downloaded ${ubuntuTmp.length()} bytes")

                    val p = ProcessBuilder("/system/bin/tar", "xf", ubuntuTmp.absolutePath, "-C", supportDir.absolutePath)
                        .start()
                    p.waitFor()
                    ubuntuTmp.delete()

                    // Re-fix shebang after overlay
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

                    ProcessBuilder("chmod", "-R", "755", supportDir.absolutePath)
                        .start().waitFor()
                    append("  extracted Ubuntu assets")
                }

                // 3. Setup directory structure matching UserLAnd layout
                // UlaFiles copies assets into filesystemDir/support/ (inside rootfs dir)
                val rootfsSupport = File(filesystemDir, "support")
                rootfsSupport.mkdirs()
                val commonDir = File(supportDir, "common")
                commonDir.mkdirs()

                // Copy scripts that extractFilesystem.sh references into support/common/
                listOf("addNonRootUser.sh", "busybox_static", "extractFilesystem.sh",
                    "compressFilesystem.sh", "deleteFilesystem.sh").forEach { name ->
                    val src = File(supportDir, name)
                    val dst = File(commonDir, name)
                    if (src.exists()) {
                        src.copyTo(dst, overwrite = true)
                        dst.setExecutable(true)
                    }
                }

                // Copy support scripts into rootfs/support/ (bind-mounted as /support inside proot)
                supportDir.listFiles()?.forEach { f ->
                    if (f.isFile) {
                        f.copyTo(File(rootfsSupport, f.name), overwrite = true)
                    }
                }
                // Also copy common/ subdirectory
                val rootfsCommon = File(rootfsSupport, "common")
                commonDir.copyRecursively(rootfsCommon, overwrite = true)
                rootfsCommon.listFiles()?.forEach { it.setExecutable(true) }

                // 4. Download rootfs tarball
                filesystemDir.mkdirs()
                val rootfsTarball = File(filesystemDir, "rootfs.tar.gz")
                if (!File(filesystemDir, "rootfs/bin/sh").exists() && !rootfsTarball.exists()) {
                    append("Downloading Ubuntu rootfs...")
                    val rootfsUrl = "https://github.com/CypherpunkArmory/UserLAnd-Assets-Ubuntu/releases/download/v0.0.12/$abi-rootfs.tar.gz"
                    append("  $rootfsUrl")
                    URL(rootfsUrl).openStream().use { input -> FileOutputStream(rootfsTarball).use { input.copyTo(it) } }
                    append("  downloaded ${rootfsTarball.length()} bytes")
                }

                // 5. Extract rootfs on host (Android tar handles it, hardlinks will be skipped)
                if (!File(filesystemDir, "rootfs/bin/sh").exists() && rootfsTarball.exists()) {
                    append("Extracting rootfs...")
                    filesystemDir.mkdirs()
                    val rootfsDir = File(filesystemDir, "rootfs")
                    rootfsDir.mkdirs()

                    val p = ProcessBuilder("/system/bin/tar", "xf", rootfsTarball.absolutePath,
                        "--exclude", "sys", "--exclude", "dev", "--exclude", "proc",
                        "--exclude", "support", "--exclude", "mnt",
                        "-C", rootfsDir.absolutePath)
                        .start()
                    val code = p.waitFor()
                    val err = p.errorStream.bufferedReader().readText().trim()

                    // Create resolv.conf
                    val resolv = File(rootfsDir, "etc/resolv.conf")
                    resolv.parentFile?.mkdirs()
                    resolv.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")

                    // Create tmp dir for proot
                    File(rootfsDir, "tmp").mkdirs()

                    if (File(rootfsDir, "bin/sh").exists()) {
                        append("  rootfs extracted OK")
                        // Run post-extraction setup inside proot (addNonRootUser, etc.)
                        rootfsSupport.mkdirs()
                        File(rootfsSupport, ".success_filesystem_extraction").createNewFile()
                        rootfsTarball.delete()
                    } else if (code != 0) {
                        append("  extract FAILED: $err")
                        runOnUiThread { dlBtn.isEnabled = true; dlBtn.text = "DL Rootfs" }
                        return@Thread
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

                    val cmd = listOf(
                        busybox.absolutePath,
                        "sh",
                        execScript.absolutePath
                    ) + text.split(" ")

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
