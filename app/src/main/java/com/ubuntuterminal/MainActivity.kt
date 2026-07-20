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
                append("Ready. Type a command, or tap DL Rootfs to download Ubuntu.")

                runOnUiThread { dlBtn.isEnabled = true }
            } catch (e: Exception) {
                append("SETUP ERROR: $e")
                Log.e("UbuntuTerminal", "setup", e)
            }
        }.start()
    }

    private fun runTestSimple(vararg cmd: String): String {
        return try {
            val pb = ProcessBuilder(*cmd)
                .directory(filesDir)
            pb.environment()["LD_LIBRARY_PATH"] = applicationInfo.nativeLibraryDir
            val p = pb.start()
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
        return if (rootfsDir.exists()) {
            listOf("/system/bin/linker64", pf,
                "--link2symlink",
                "-0",
                "-r", rootfsDir.absolutePath,
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                "-b", "/tmp",
                "-b", "/system",
                "-b", "/data",
                "-w", "/root",
                "/usr/bin/env", "-i",
                "HOME=/root",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "TERM=xterm-256color",
                "/bin/bash", "-c")
        } else {
            listOf("/system/bin/linker64", pf, "/system/bin/sh", "-c")
        }
    }

    private fun prootEnv(): Map<String, String> {
        val libDir = applicationInfo.nativeLibraryDir
        val m = mutableMapOf(
            "PROOT_LOADER" to (loaderFile?.absolutePath ?: ""),
            "PROOT_LOADER_32" to (loaderFile?.absolutePath ?: ""),
            "PROOT_NO_SECCOMP" to "1",
            "LD_LIBRARY_PATH" to libDir
        )
        if (rootfsDir.exists()) {
            val tmpDir = File(filesDir, "tmp")
            tmpDir.mkdirs()
            m["PROOT_TMP_DIR"] = tmpDir.absolutePath
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
                    "arm64" -> "arm64"
                    "arm" -> "armhf"
                    "x86_64" -> "amd64"
                    "x86" -> "i386"
                    else -> "arm64"
                }

                append("Finding latest Ubuntu rootfs...")
                val indexUrl = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/"
                val indexHtml = URL(indexUrl).readText()

                val pattern = Regex("ubuntu-base-24\\.04\\.(\\d+)-base-$arch\\.tar\\.gz")
                val versions = pattern.findAll(indexHtml).map { it.groupValues[1].toInt() }.distinct().sorted()
                if (versions.isEmpty()) throw Exception("No Ubuntu rootfs found for $arch at $indexUrl")

                val latest = versions.last()
                val url = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.$latest-base-$arch.tar.gz"
                append("Downloading Ubuntu 24.04.$latest rootfs...")
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

                val resolv = File(rdir, "etc/resolv.conf")
                resolv.parentFile?.mkdirs()
                resolv.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
                append("  created /etc/resolv.conf")

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
        append(if (inProot) "Proot mode ON — commands run inside Ubuntu rootfs" else "Proot mode OFF")
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
