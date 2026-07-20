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

    private var prootFile: File? = null
    private var loaderFile: File? = null
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
        dlBtn.setOnClickListener { downloadRootfs() }

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
            scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }

    private fun doSetup() {
        Thread {
            try {
                val abi = getAbi()
                append("Arch: $abi")
                append("Files dir: ${filesDir.absolutePath}")

                val libDir = applicationInfo.nativeLibraryDir
                var pFile = File(libDir, "libproot.so")
                var lFile = File(libDir, "libproot-loader.so")

                if (!pFile.exists()) {
                    append("jniLibs not extracted — falling back to assets")

                    pFile = File(filesDir, "proot")
                    lFile = File(filesDir, "loader")

                    if (!pFile.exists()) {
                        assets.open("proot_$abi").use { i -> FileOutputStream(pFile).use { i.copyTo(it) } }
                        pFile.setExecutable(true)
                    }
                    if (!lFile.exists()) {
                        assets.open("loader_$abi").use { i -> FileOutputStream(lFile).use { i.copyTo(it) } }
                        lFile.setExecutable(true)
                    }

                    append("proot via linker64: " + runTestSimple("/system/bin/linker64", pFile.absolutePath, "--version"))
                } else {
                    append("proot via direct exec: " + runTestSimple(pFile.absolutePath, "--version"))
                }

                prootFile = pFile
                loaderFile = lFile

                append("")
                append("Ready. Type a command, or tap DL Rootfs to download Alpine.")

                runOnUiThread { dlBtn.isEnabled = true }
            } catch (e: Exception) {
                append("SETUP ERROR: $e")
                Log.e("UbuntuTerminal", "setup", e)
            }
        }.start()
    }

    private fun runTestSimple(vararg cmd: String): String {
        return try {
            val p = ProcessBuilder(*cmd)
                .directory(filesDir)
                .start()
            val out = p.inputStream.bufferedReader().readText().trim()
            val err = p.errorStream.bufferedReader().readText().trim()
            val code = p.waitFor()
            "exit=$code out=${out.take(80)} err=${err.take(80)}"
        } catch (e: Exception) {
            "FAILED: ${e.message}"
        }
    }

    private fun prootCmd(): List<String> {
        val p = prootFile ?: error("proot not set up")
        val pf = p.absolutePath
        val lf = loaderFile?.absolutePath ?: ""
        return if (rootfsDir.exists()) {
            listOf("/system/bin/linker64", pf,
                "-r", rootfsDir.absolutePath,
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                "-b", "/system",
                "-b", "/data",
                "-w", "/root",
                "/system/bin/sh", "-c")
        } else {
            listOf("/system/bin/linker64", pf, "/system/bin/sh", "-c")
        }
    }

    private fun prootEnv(): Map<String, String> {
        val m = mutableMapOf("PROOT_LOADER" to (loaderFile?.absolutePath ?: ""))
        if (rootfsDir.exists()) {
            m["HOME"] = "/root"
            m["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            m["TERM"] = "xterm-256color"
        }
        return m
    }

    private fun downloadRootfs() {
        dlBtn.isEnabled = false
        dlBtn.text = "DL..."
        Thread {
            try {
                val abi = getAbi()
                val arch = when (abi) {
                    "arm64" -> "aarch64"
                    "arm" -> "armv7"
                    "x86_64" -> "x86_64"
                    "x86" -> "x86"
                    else -> "aarch64"
                }
                val url = "https://dl-cdn.alpinelinux.org/alpine/v3.19/releases/$arch/alpine-minirootfs-3.19.1-$arch.tar.gz"
                append("Downloading Alpine rootfs...")
                append("  $url")

                val tmp = File(filesDir, "rootfs.tar.gz")
                URL(url).openStream().use { input -> FileOutputStream(tmp).use { input.copyTo(it) } }
                append("  downloaded ${tmp.length()} bytes")

                val rdir = rootfsDir
                if (rdir.exists()) rdir.deleteRecursively()
                rdir.mkdir()

                append("  extracting...")
                val p = ProcessBuilder("/system/bin/tar", "xf", tmp.absolutePath, "-C", rdir.absolutePath)
                    .directory(filesDir)
                    .start()
                val code = p.waitFor()
                tmp.delete()

                if (code != 0) {
                    val err = p.errorStream.bufferedReader().readText().trim()
                    append("  extract FAILED: $err")
                    return@Thread
                }

                append("  extracted OK — $(ls ${rdir.absolutePath} | head -5)")
                append("Rootfs ready at ${rdir.absolutePath}")

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
        append(if (inProot) "Proot mode ON — commands run inside Alpine rootfs" else "Proot mode OFF")
    }

    private fun runCmd() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.text.clear()
        append("$ $text")
        Thread {
            try {
                val pb: ProcessBuilder
                if (inProot && rootfsDir.exists()) {
                    val parts = prootCmd() + text
                    pb = ProcessBuilder(parts)
                    pb.environment().putAll(prootEnv())
                } else {
                    pb = ProcessBuilder("/system/bin/sh", "-c", text)
                }
                pb.directory(filesDir)
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
