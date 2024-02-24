package me.magnum.melonds

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import io.reactivex.disposables.Disposable
import me.magnum.melonds.common.UriFileHandler
import me.magnum.melonds.common.uridelegates.UriHandler
import me.magnum.melonds.domain.repositories.SettingsRepository
import me.magnum.melonds.migrations.Migrator
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject

@HiltAndroidApp
class MelonDSApplication : Application(), Configuration.Provider {
    companion object {
        const val NOTIFICATION_CHANNEL_ID_BACKGROUND_TASKS = "channel_cheat_importing"

        init {
            System.loadLibrary("melonDS-android-frontend")
        }
    }

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var migrator: Migrator
    @Inject lateinit var uriHandler: UriHandler

    private var themeObserverDisposable: Disposable? = null

    var dldibinPath = arrayOf(
        //"res/project/DEMOS_CC_BY_3_0/lgpt_ryba_-_life_as_a_game/lgptsav.dat"
        "dldi.bin"
      , "notominous-a19.xm"
        ,"atomtwist-cybernitro.xm"
        ,"101puls1.wav"
    )

    fun initAssetFile(fileList: Array<String>) {
        var resHomePath : String = getApplicationContext().getFilesDir().getAbsolutePath();

        Log.i("koi Log.i", resHomePath + "/" + fileList[0]);

        val file = File("$resHomePath/${fileList[0]}")
        //val fileRes = File("$resHomePath/res")
        //fileの分のmkdirはいらないらしい
        //fileRes.mkdirs()

        //ファイルが無いときだけコピーする
        if (!file.exists()){
            for(resFile in fileList){
                var istr: InputStream? = null
                var fostr: FileOutputStream? = null
                try{
                    istr = applicationContext.resources.assets.open(resFile)
                    //var is:InputStream = getApplicationContext().getResources().getAssets().open(resFile);
                    fostr = FileOutputStream("$resHomePath/$resFile")

                    val buffer: ByteArray = ByteArray(8192)
                    var count: Int;
                    while (istr.read(buffer).also { count = it } > 0) {
                        fostr.write(buffer, 0, count)
                    }

                }catch (e: Exception){
                    e.printStackTrace();
                }finally {
                    try {
                        if(fostr != null){
                            fostr?.close();
                        }
                        if(istr != null){
                            istr?.close();
                        }
                    } catch (e : IOException) {
                        e.printStackTrace();
                    }

                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        applyTheme()
        performMigrations()
        initAssetFile(dldibinPath)
        MelonDSAndroidInterface.setup(UriFileHandler(this, uriHandler))
    }

    private fun createNotificationChannels() {
        val defaultChannel = NotificationChannelCompat.Builder(NOTIFICATION_CHANNEL_ID_BACKGROUND_TASKS, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.notification_channel_background_tasks))
            .build()

        val notificationManager = NotificationManagerCompat.from(this)
        notificationManager.createNotificationChannel(defaultChannel)
    }

    private fun applyTheme() {
        val theme = settingsRepository.getTheme()

        AppCompatDelegate.setDefaultNightMode(theme.nightMode)
        themeObserverDisposable = settingsRepository.observeTheme().subscribe { AppCompatDelegate.setDefaultNightMode(it.nightMode) }
    }

    private fun performMigrations() {
        migrator.performMigrations()
    }

    override fun onTerminate() {
        super.onTerminate()
        themeObserverDisposable?.dispose()
        MelonDSAndroidInterface.cleanup()
    }

    override val workManagerConfiguration: Configuration get() {
        return Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
    }
}