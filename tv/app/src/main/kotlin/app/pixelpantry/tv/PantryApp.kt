package app.pixelpantry.tv

import android.app.Application
import app.pixelpantry.tv.di.AppContainer

class PantryApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
