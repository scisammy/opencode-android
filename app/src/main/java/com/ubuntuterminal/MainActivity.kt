package com.ubuntuterminal

import android.os.Bundle
import android.os.Build
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
    private val rootfsDir: File get() = File(filesDir, "rootfs")
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

                    if (rootfsDir.exists() && File(rootfsDir, "bin/sh").exists()) {
                        append("Rootfs: OK")
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
                val baseUrl = "https://github.com/CypherpunkArmory/UserLAnd-Assets-Ubuntu/releases/download/v0.0.12"

                // Download assets (proot, loader, busybox, scripts)
                if (!File(supportDir, "busybox").exists()) {
                    append("Downloading support assets...")
                    val assetsUrl = "$baseUrl/$abi-assets.tar.gz"
                    append("  $assetsUrl")
                    val assetsTmp = File(filesDir, "assets.tar.gz")
                    URL(assetsUrl).openStream().use { input -> FileOutputStream(assetsTmp).use { input.copyTo(it) } }
                    append("  downloaded ${assetsTmp.length()} bytes")

                    supportDir.mkdirs()
                    val p = ProcessBuilder("/system/bin/tar", "xf", assetsTmp.absolutePath, "-C", supportDir.absolutePath)
                        .start()
                    p.waitFor()
                    assetsTmp.delete()

                    // Make all files executable recursively
                    ProcessBuilder("/system/bin/find", supportDir.absolutePath, "-type", "f", "-exec", "chmod", "755", "{}", "+")
                        .start().waitFor()
                    append("  extracted support files")
                }

                // Download rootfs
                if (!File(rootfsDir, "bin/sh").exists()) {
                    append("Downloading Ubuntu rootfs...")
                    val rootfsUrl = "$baseUrl/$abi-rootfs.tar.gz"
                    append("  $rootfsUrl")
                    val rootfsTmp = File(filesDir, "rootfs.tar.gz")
                    URL(rootfsUrl).openStream().use { input -> FileOutputStream(rootfsTmp).use { input.copyTo(it) } }
                    append("  downloaded ${rootfsTmp.length()} bytes")

                    append("  extracting rootfs (this may take a while)...")
                    rootfsDir.mkdirs()
                    val p = ProcessBuilder("/system/bin/tar", "xf", rootfsTmp.absolutePath, "-C", rootfsDir.absolutePath)
                        .start()
                    val code = p.waitFor()
                    val err = p.errorStream.bufferedReader().readText().trim()
                    rootfsTmp.delete()

                    if (File(rootfsDir, "bin/sh").exists()) {
                        append("  rootfs extracted OK")
                    } else if (code != 0) {
                        append("  extract FAILED: $err")
                        runOnUiThread { dlBtn.isEnabled = true; dlBtn.text = "DL Rootfs" }
                        return@Thread
                    }
                }

                // Create resolv.conf
                val resolv = File(rootfsDir, "etc/resolv.conf")
                resolv.parentFile?.mkdirs()
                resolv.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")

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
                if (inProot && rootfsDir.exists() && File(rootfsDir, "bin/sh").exists()) {
                    val busybox = File(supportDir, "busybox")
                    val execScript = File(supportDir, "execInProot.sh")
                    val cmd = listOf(busybox.absolutePath, "sh", execScript.absolutePath) + text.split(" ")
                    pb = ProcessBuilder(cmd)
                    pb.directory(filesDir)
                    val env = pb.environment()
                    env["ROOT_PATH"] = filesDir.absolutePath
                    env["ROOTFS_PATH"] = rootfsDir.absolutePath
                    env["LIB_PATH"] = supportDir.absolutePath
                    env["PROOT_DEBUG_LEVEL"] = "-1"
                    env["LD_LIBRARY_PATH"] = applicationInfo.nativeLibraryDir
                } else {
                    pb = ProcessBuilder("/system/bin/sh", "-c", text)
                    pb.directory(filesDir)
                }
                val p = pb.start()
                val out = p.inputStream.bufferedReader().readText().trim()
                val err = p.errorStream.bufferedReader().readText().trim()
                val code = p.waitFor()
                if (out.isNotEmpty()) append(out)
                if (err.isNotEmpty()) append(err)
                append("[exit $code]")
            } catch (e: Exception) {
                append("ERROR: $e")
            }
        }.start()
    }

    private fun getAbi(): String {
        val abi = Build.CPU_ABI?.lowercase() ?: Build.SUPPORTED_ABIS.firstOrNull()?.lowercase() ?: "arm64-v8a"
        return when {
            abi.contains("arm64") -> "arm64"
            abi.contains("armeabi") -> "arm"
            abi.contains("x86_64") -> "x86_64"
            abi.contains("x86") -> "x86"
            else -> "arm64"
        }
    }
}
