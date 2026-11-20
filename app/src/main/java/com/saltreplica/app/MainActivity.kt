package com.saltreplica.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        applyAccentTheme()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, LibraryFragment(), "library")
                .commit()
        }

        // 延后连接播放服务，避免首帧阶段与服务初始化竞争
        root.post {
            runCatching { PlaybackConnection.connect(this) }
            showCrashIfAny()
        }

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001) }
        }
    }

    /** 主题 2/3/4 是同一套深色底 + 不同强调色，用主题 overlay 实现 */
    private fun applyAccentTheme() {
        val overlay = when (Prefs.themeMode) {
            2 -> R.style.Theme_SaltReplica_Neon
            3 -> R.style.Theme_SaltReplica_Ember
            4 -> R.style.Theme_SaltReplica_Lemon
            else -> 0
        }
        if (overlay != 0) runCatching { theme.applyStyle(overlay, true) }
    }

    private fun showCrashIfAny() {
        val file = File(filesDir, "last_crash.txt")
        if (!file.exists()) return
        val text = runCatching { file.readText() }.getOrNull()
        runCatching { file.delete() }
        if (text.isNullOrBlank()) return
        runCatching {
            AppDialog.message(this, "上次启动出现异常", text.take(1800))
        }
    }

    /** 供音乐库打开播放页 */
    fun openPlayer() {
        val current = supportFragmentManager.findFragmentById(R.id.container)
        if (current is PlayerFragment) return
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, PlayerFragment(), "player")
            .addToBackStack("player")
            .commit()
    }
}
