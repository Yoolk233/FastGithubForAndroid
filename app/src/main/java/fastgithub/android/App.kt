package fastgithub.android

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Config.init(this)
    }
}
