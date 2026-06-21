package com.gijimemo.ui.processing

import android.util.Log
import java.io.File

/**
 * v0.7.2: システムメトリクス取得 (CPU/メモリ/GPU)。
 * ProcessingScreen で表示するため、/proc ファイルシステムから
 * 軽量に読み取る。
 */
data class SystemMetrics(
    /** アプリ全体の CPU 使用率 (0.0-1.0) */
    val cpuUsage: Float = 0f,
    /** 使用中のメモリ RSS (MB) */
    val rssMb: Long = 0L,
    /** 端末の合計メモリ (MB) */
    val totalMemMb: Long = 0L,
    /** Java ヒープ使用量 (MB) — 文字起こし中のメモリ変動を追跡するため */
    val heapUsedMb: Long = 0L,
    /** Java ヒープ最大容量 (MB) */
    val heapMaxMb: Long = 0L,
    /** OpenCL が端末で利用可能か (Adreno / Mali GPU に到達できるか) */
    val openClAvailable: Boolean = false,
    /** GPU 使用率 (0.0-1.0)。取得不可なら -1f。 */
    val gpuUsage: Float = -1f,
    /** GPU 統計取得元 ("SurfaceFlinger" / "unavailable") */
    val gpuSource: String = "unavailable"
) {
    val memUsageRatio: Float get() = if (totalMemMb > 0L) rssMb.toFloat() / totalMemMb else 0f
}

object SystemMetricsReader {
    private const val TAG = "SystemMetrics"

    private var lastCpuTotal: Long = 0
    private var lastMyCpu: Long = 0
    private var initialized: Boolean = false
    // true = /proc/stat モード, false = getElapsedCpuTime フォールバックモード
    private var usingProcStat: Boolean = true
    // OpenCL ライブラリの dlopen 結果をキャッシュ (失敗時の繰り返し試行を防ぐ)
    @Volatile private var openClCached: Boolean? = null

    /**
     * 1秒間隔で呼ぶと CPU 使用率 (差分) を返す。
     * 初回呼び出しは -1f を返し、内部状態を初期化する。
     * Android 11+ で /proc/stat が読めない場合は -1f を返す（表示側で N/A に）。
     */
    fun readCpuUsage(): Float {
        val myPid = android.os.Process.myPid()
        val statPaths = listOf("/proc/self/stat", "/proc/$myPid/stat")
        var myStat: String? = null
        var usedPath = ""
        for (path in statPaths) {
            try {
                val content = File(path).readText()
                if (content.isNotEmpty()) {
                    myStat = content
                    usedPath = path
                    break
                }
            } catch (e: Exception) {
                Log.w(TAG, "readCpuUsage: $path read failed: ${e.message}")
            }
        }
        if (myStat == null) {
            Log.w(TAG, "readCpuUsage: all /proc stat paths failed")
            return -1f
        }
        val closeParen = myStat.lastIndexOf(')')
        if (closeParen < 0 || closeParen + 2 >= myStat.length) {
            Log.w(TAG, "readCpuUsage: invalid format: ${myStat.take(80)}")
            return -1f
        }
        val statFields = myStat.substring(closeParen + 2).split(Regex("\\s+"))
        if (statFields.size < 13) {
            Log.w(TAG, "readCpuUsage: not enough statFields (${statFields.size}) from $usedPath")
            return -1f
        }
        val utime = statFields[11].toLongOrNull() ?: 0L
        val stime = statFields[12].toLongOrNull() ?: 0L
        val myCpuTime = utime + stime

        // 全コア total を /proc/stat から取得
        // Android 11+ では EACCES になることがあるため、失敗時は -1f
        val allStat = try {
            File("/proc/stat").readText().lineSequence().firstOrNull { it.startsWith("cpu ") }
        } catch (e: Exception) {
            Log.w(TAG, "readCpuUsage: /proc/stat read failed: ${e.message}")
            null
        }
        if (allStat == null) {
            // /proc/stat が読めない → 割合計算不可。getElapsedCpuTime() (ms) の差分で代替表示
            val now = android.os.Process.getElapsedCpuTime()
            // 前回 stat モードだった場合は再初期化（異なる単位を混在させない）
            if (!initialized || usingProcStat) {
                lastMyCpu = now
                usingProcStat = false
                initialized = true
                return -1f
            }
            val diffMs = (now - lastMyCpu).coerceIn(0L, 1000L)
            lastMyCpu = now
            val usage = (diffMs / 1000f).coerceIn(0f, 1f)
            Log.i(TAG, "readCpuUsage: fallback diffMs=$diffMs usage=$usage (no /proc/stat)")
            return usage
        }
        val allParts = allStat.split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (allParts.size < 4) return -1f
        val allTotal = allParts.sum()

        if (!initialized) {
            lastCpuTotal = allTotal
            lastMyCpu = myCpuTime
            initialized = true
            usingProcStat = true
            Log.i(TAG, "readCpuUsage: init from $usedPath myCpu=$myCpuTime allTotal=$allTotal")
            return -1f
        }
        val totalDiff = allTotal - lastCpuTotal
        val myDiff = myCpuTime - lastMyCpu
        lastCpuTotal = allTotal
        lastMyCpu = myCpuTime
        if (totalDiff <= 0L) {
            Log.w(TAG, "readCpuUsage: totalDiff<=0 ($totalDiff) from $usedPath")
            return -1f
        }
        val usage = myDiff.toFloat() / totalDiff.toFloat()
        Log.i(TAG, "readCpuUsage: myDiff=$myDiff totalDiff=$totalDiff usage=$usage from $usedPath")
        return usage.coerceIn(0f, 1f)
    }

    /** /proc/self/status から VmRSS を取得 (KB 単位) */
    fun readRssKb(): Long {
        return try {
            File("/proc/self/status").useLines { lines ->
                lines.firstOrNull { it.startsWith("VmRSS:") }
                    ?.split(Regex("\\s+"))?.getOrNull(1)
                    ?.toLongOrNull() ?: 0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    /** /proc/meminfo から MemTotal を取得 (KB 単位) */
    fun readTotalMemKb(): Long {
        return try {
            File("/proc/meminfo").useLines { lines ->
                lines.firstOrNull { it.startsWith("MemTotal:") }
                    ?.split(Regex("\\s+"))?.getOrNull(1)
                    ?.toLongOrNull() ?: 0L
            }
        } catch (e: Exception) {
            Log.w(TAG, "readTotalMemKb: /proc/meminfo failed: ${e.message}")
            // Android 11+ で /proc/meminfo が読めない場合、ActivityManager は
            // Context が必要なのでここでは 0 を返し、表示側で "N/A" とする
            0L
        }
    }

    /**
     * libOpenCL.so が dlopen 可能かチェック (= Adreno/Mali GPU に到達できるか)
     * 結果をキャッシュして 2回目以降は高速に返す。
     */
    fun isOpenClAvailable(): Boolean {
        openClCached?.let { return it }
        val result = try {
            System.loadLibrary("OpenCL")  // OpenCL 1.x
            true
        } catch (e1: UnsatisfiedLinkError) {
            try {
                System.loadLibrary("OpenCL-1.1")  // 一部 Xiaomi
                true
            } catch (e2: UnsatisfiedLinkError) {
                false
            }
        }
        openClCached = result
        return result
    }

    /**
     * GPU 使用率を取得。Adreno / Mali の freqinfo や SurfaceFlinger
     * から取得。取得不可なら -1f を返す。
     */
    fun readGpuUsage(): Pair<Float, String> {
        // 試行 1: /sys/class/devfreq/<gpu>/cur_freq
        return try {
            val devfreqDir = File("/sys/class/devfreq")
            if (devfreqDir.exists()) {
                val gpuPath = devfreqDir.listFiles()?.firstOrNull { f ->
                    val name = f.name.lowercase()
                    name.contains("gpu") || name.contains("adreno") || name.contains("mali")
                }
                if (gpuPath != null) {
                    val cur = File(gpuPath, "cur_freq").readText().trim().toLongOrNull() ?: 0L
                    val max = File(gpuPath, "max_freq").readText().trim().toLongOrNull() ?: 1L
                    if (max > 0L) {
                        val usage = cur.toFloat() / max.toFloat()
                        return usage.coerceIn(0f, 1f) to "devfreq (${gpuPath.name})"
                    }
                }
            }
            -1f to "no-devfreq"
        } catch (e: Exception) {
            -1f to "error: ${e.message?.take(40)}"
        }
    }

    /** すべてのメトリクスを一度に取得 */
    fun readAll(): SystemMetrics {
        val rt = Runtime.getRuntime()
        return SystemMetrics(
            cpuUsage = readCpuUsage(),
            rssMb = readRssKb() / 1024,
            totalMemMb = readTotalMemKb() / 1024,
            heapUsedMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L),
            heapMaxMb = rt.maxMemory() / (1024L * 1024L),
            openClAvailable = isOpenClAvailable(),
            gpuUsage = 0f,  // 別途 readGpuUsage() で上書き
            gpuSource = ""
        ).let { m ->
            val (gpu, src) = readGpuUsage()
            m.copy(gpuUsage = gpu, gpuSource = src)
        }
    }
}