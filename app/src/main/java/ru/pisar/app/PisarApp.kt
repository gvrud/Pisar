package ru.pisar.app

import android.app.Application
import ru.pisar.app.model.JobStore
import ru.pisar.app.util.Notifications

class PisarApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        JobStore.init(this)
        Notifications.ensureChannels(this)
    }

    companion object {
        lateinit var instance: PisarApp
            private set
    }
}
