package com.ubuntuterminal

import android.os.Bundle
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText

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

        val inputRow = LinearLayout(this)
        inputRow.orientation = LinearLayout.HORIZONTAL
        inputRow.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(send, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        layout.addView(inputRow, LinearLayout.LayoutParams(
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

                // Try native lib dir first (Android ≤11 extracts .so files here)
                val libDir = applicationInfo.nativeLibraryDir
                var prootFile = File(libDir, "libproot.so")
                var loaderFile = File(libDir, "libproot-loader.so")
                append("Native lib dir: $libDir")
                append("proot: ${prootFile.length()} bytes, exists=${prootFile.exists()}")

                if (!prootFile.exists()) {
                    // Native libs not extracted — use assets fallback + linker64
                    append("jniLibs not extracted on this Android version — falling back to assets")

                    prootFile = File(filesDir, "proot")
                    loaderFile = File(filesDir, "loader")

                    if (!prootFile.exists()) {
                        assets.open("proot_$abi").use { i -> FileOutputStream(prootFile).use { i.copyTo(it) } }
                        prootFile.setExecutable(true)
                    }
                    if (!loaderFile.exists()) {
                        assets.open("loader_$abi").use { i -> FileOutputStream(loaderFile).use { i.copyTo(it) } }
                        loaderFile.setExecutable(true)
                    }

                    // Use linker64 to bypass noexec on /data/data
                    append("Running proot via linker64 (bypasses noexec)")
                    runTest("/system/bin/linker64", prootFile.absolutePath, "--version")
                    runTest(mapOf("PROOT_LOADER" to loaderFile.absolutePath),
                        "/system/bin/linker64", prootFile.absolutePath, "--version")
                } else {
                    // Native libs are present — direct exec should work
                    append("loader: ${loaderFile.length()} bytes, exists=${loaderFile.exists()}")
                    runTest(prootFile.absolutePath, "--version")
                    runTest(mapOf("PROOT_LOADER" to loaderFile.absolutePath),
                        prootFile.absolutePath, "--version")
                    runTest("/system/bin/linker64", prootFile.absolutePath, "--version")
                }

                append("")
                runTest("/system/bin/sh", "-c", "echo shell works")
                runTest("/system/bin/tar", "--version")

                append("")
                runTest("/system/bin/cat", "/proc/self/mountinfo")

                append("")
                append("Ready. Type a command to run it.")
            } catch (e: Exception) {
                append("SETUP ERROR: $e")
                Log.e("UbuntuTerminal", "setup", e)
            }
        }.start()
    }

    private fun runTest(vararg cmd: String) {
        runTest(emptyMap(), *cmd)
    }

    private fun runTest(env: Map<String, String>, vararg cmd: String) {
        try {
            val start = System.currentTimeMillis()
            val pb = ProcessBuilder(*cmd)
            pb.environment().putAll(env)
            pb.directory(filesDir)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText().trim()
            val err = p.errorStream.bufferedReader().readText().trim()
            val code = p.waitFor()
            val elapsed = System.currentTimeMillis() - start
            append("> ${cmd.joinToString(" ")}")
            if (out.isNotEmpty()) append("  out: $out")
            if (err.isNotEmpty()) append("  err: $err")
            append("  exit=$code (${elapsed}ms)")
        } catch (e: Exception) {
            append("> ${cmd.joinToString(" ")}")
            append("  FAILED: $e")
        }
    }

    private fun runCmd() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.text.clear()
        append("$ $text")
        Thread {
            try {
                val pb = ProcessBuilder("/system/bin/sh", "-c", text)
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
