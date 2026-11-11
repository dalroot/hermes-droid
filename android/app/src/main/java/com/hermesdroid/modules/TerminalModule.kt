package com.hermesdroid.modules

import android.content.Context
import com.hermesdroid.model.ShellExecutionResult
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class TerminalModule(private val context: Context) {

    fun executeShell(command: String, timeoutSeconds: Long = 15): ShellExecutionResult {
        val startTime = System.currentTimeMillis()
        val stdoutBuilder = StringBuilder()
        val stderrBuilder = StringBuilder()
        var exitCode = -1

        try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))

            val stdoutReader = BufferedReader(InputStreamReader(process.inputStream))
            val stderrReader = BufferedReader(InputStreamReader(process.errorStream))

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (finished) {
                exitCode = process.exitValue()
                var line: String?
                while (stdoutReader.readLine().also { line = it } != null) {
                    stdoutBuilder.append(line).append("\n")
                }
                while (stderrReader.readLine().also { line = it } != null) {
                    stderrBuilder.append(line).append("\n")
                }
            } else {
                process.destroyForcibly()
                stderrBuilder.append("Command timed out after $timeoutSeconds seconds.\n")
            }
        } catch (e: Exception) {
            stderrBuilder.append("Execution error: ${e.message}\n")
        }

        val duration = System.currentTimeMillis() - startTime
        return ShellExecutionResult(
            stdout = stdoutBuilder.toString().trimEnd(),
            stderr = stderrBuilder.toString().trimEnd(),
            exitCode = exitCode,
            durationMs = duration
        )
    }

    fun getRecentLogcat(maxLines: Int = 50): String {
        return executeShell("logcat -d -t $maxLines").stdout
    }

    fun getInstalledPackages(): List<String> {
        val pm = context.packageManager
        return try {
            pm.getInstalledApplications(0).map { it.packageName }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
