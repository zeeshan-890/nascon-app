package com.nascon.league

import android.app.Application
import com.nascon.league.di.AppContainer

class App : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
