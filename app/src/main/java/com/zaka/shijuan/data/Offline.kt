package com.zaka.shijuan.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 在线 / 离线状态。
 *
 * 没网的时候整个 App 进离线模式：只展示书架，书架上只留本地有缓存的书，
 * 搜索、书源、榜单这些要联网的入口全部藏起来。
 *
 * 用系统网络回调监听，插拔网线、开关飞行模式立刻跟着变，不用轮询。
 */
object Offline {

    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online

    @Volatile
    private var started = false

    fun init(ctx: Context) {
        if (started) return
        started = true
        val cm = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return
        _online.value = probe(cm)
        runCatching {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        _online.value = true
                    }

                    override fun onLost(network: Network) {
                        _online.value = probe(cm)
                    }

                    override fun onUnavailable() {
                        _online.value = false
                    }
                },
            )
        }
    }

    /** 现在能不能上网：要有默认网络，而且要声明了 INTERNET 能力 */
    fun probe(cm: ConnectivityManager): Boolean {
        val net = cm.activeNetwork ?: return false
        val cap = runCatching { cm.getNetworkCapabilities(net) }.getOrNull() ?: return false
        return cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun isOnline(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        return probe(cm)
    }
}
