package com.melox.player


import android.os.Build               // <--- 补充导入
import android.os.Environment         // <--- 补充导入
import android.provider.Settings      // <--- 补充导入
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import com.melox.player.ui.MeloxApp
import com.melox.player.ui.viewmodel.MeloxViewModel

/** Hosts the single Compose hierarchy for the player. */
class MainActivity : AppCompatActivity() {
    private val viewModel: MeloxViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
		// 新增：检查并请求所有文件访问权限
        checkAndRequestAllFilesPermission(this)
        val appViewModel = viewModel
        setContent {
            MeloxApp(viewModel = appViewModel)
        }
        if (savedInstanceState == null) {
            playAudioFrom(intent)
        }
    }
	
	// 新增：权限引导函数
    private fun checkAndRequestAllFilesPermission(context: android.content.Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    context.startActivity(intent)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        playAudioFrom(intent)
    }

    private fun playAudioFrom(intent: Intent) {
        intent.externalAudioUri()?.let { uri ->
            if (
                intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0 &&
                intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0
            ) {
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
            viewModel.playExternalAudio(uri)
        }
    }
}

private fun Intent.externalAudioUri(): Uri? = when (action) {
    Intent.ACTION_VIEW -> data
    Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(
        this,
        Intent.EXTRA_STREAM,
        Uri::class.java,
    ) ?: clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri

    else -> null
}
