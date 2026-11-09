package fastgithub.android

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import fastgithub.android.ui.AppRoot
import fastgithub.android.ui.FastGithubTheme
import fastgithub.android.vpn.DnsVpnService

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Config.init(this)
        AppState.syncWithService(DnsVpnService.isActive)

        if (Build.VERSION.SDK_INT >= 33) {
            runCatching { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100) }
        }

        setContent {
            FastGithubTheme {
                val vpnLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    if (result.resultCode == Activity.RESULT_OK) {
                        startVpn()
                    } else {
                        AppState.log("未授予 VPN 权限，加速未启动")
                        AppState.markError("未授予 VPN 权限：系统弹窗里点「确定 / 允许」后才能开始加速")
                    }
                }

                AppRoot(
                    onToggle = { wantOn ->
                        if (wantOn) {
                            val prep = VpnService.prepare(this@MainActivity)
                            if (prep != null) vpnLauncher.launch(prep) else startVpn()
                        } else {
                            stopVpn()
                        }
                    },
                    onReprobe = { reprobe() },
                    onOpenVpnSettings = { openVpnSettings() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从通知栏/系统设置里关掉 VPN 再回来时，界面状态要跟服务真实状态对齐
        AppState.syncWithService(DnsVpnService.isActive)
    }

    private fun startVpn() {
        val i = Intent(this, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i)
        } else {
            startService(i)
        }
    }

    private fun stopVpn() {
        if (DnsVpnService.isActive) {
            val i = Intent(this, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_STOP)
            startService(i)
        } else {
            // 服务已经不在时不要再 startService 拉起一个空转实例
            stopService(Intent(this, DnsVpnService::class.java))
            AppState.syncWithService(false)
        }
    }

    private fun reprobe() {
        if (!DnsVpnService.isActive) {
            AppState.log("加速未开启，忽略重新测速")
            return
        }
        val i = Intent(this, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_PROBE)
        startService(i)
    }

    private fun openVpnSettings() {
        val ok = runCatching {
            startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
            true
        }.getOrDefault(false)
        if (!ok) {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }
}
