package com.hoshino.wenku8reader

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.hoshino.wenku8reader.di.AppContainer
import kotlinx.coroutines.launch

class Wenku8Application : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // 放在 container 之后：静态引用一旦非空，container 必然已可用
        instance = this
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) { currentActivity = activity }
            override fun onActivityStarted(activity: Activity) { currentActivity = activity }
            override fun onActivityResumed(activity: Activity) { currentActivity = activity }
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) { if (currentActivity == activity) currentActivity = null }
        })
        silentLogin()
    }

    /**
     * 进程结束时释放网络资源（Cronet 引擎）。
     *
     * 注意：`onTerminate` 在真机上**不会被调用**（只有在模拟器等调试环境才会），
     * 所以这只是"把释放动作接在该接的地方"——真机上 Cronet 随进程回收，无需额外处理。
     * 这样 [com.hoshino.wenku8reader.data.Wenku8Client.close] 不再是无人调用的死代码。
     */
    override fun onTerminate() {
        container.client.close()
        super.onTerminate()
    }

    /**
     * 静默预热/登录：
     * 小说浏览与阅读完全公开，无需登录；仅云端书架需要登录态。
     * 此处延后执行，避免应用冷启动时与首屏页面争抢网络连接或因未获得 Cloudflare 令牌直接被 403 拦截。
     */
    private fun silentLogin() {
        container.launchIo {
            kotlinx.coroutines.delay(1000)
            runCatching { container.client.ensureLoggedIn() }
        }
    }

    companion object {
        /**
         * 应用级静态引用。
         *
         * 唯一用途：[MainActivity.attachBaseContext] 里做应用内语言切换——那个时点
         * `activity.application` 尚未赋值，只能经这里取 [container] 里的设置。
         * 设置值由 `ReaderSettings` 在 [onCreate] 中同步载入，因此读到的不是默认值。
         */
        @Volatile
        internal var instance: Wenku8Application? = null
            private set

        @Volatile
        internal var currentActivity: Activity? = null
            private set
    }
}
