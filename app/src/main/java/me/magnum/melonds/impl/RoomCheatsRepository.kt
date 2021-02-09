package me.magnum.melonds.impl

import android.content.Context
import android.net.Uri
import androidx.lifecycle.Observer
import androidx.work.*
import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import me.magnum.melonds.database.MelonDatabase
import me.magnum.melonds.database.entities.CheatEntity
import me.magnum.melonds.database.entities.CheatFolderEntity
import me.magnum.melonds.database.entities.CheatStatusUpdate
import me.magnum.melonds.database.entities.GameEntity
import me.magnum.melonds.domain.model.*
import me.magnum.melonds.domain.repositories.CheatsRepository

class RoomCheatsRepository(private val context: Context, private val database: MelonDatabase) : CheatsRepository {
    companion object {
        private const val IMPORT_WORKER_NAME = "cheat_import_worker"
    }

    override fun getAllRomCheats(romInfo: RomInfo): Maybe<List<Game>> {
        return database.gameDao().findGameWithCheats(romInfo.gameCode).map {
            it.map { game ->
                Game(
                        game.game.id,
                        game.game.name,
                        game.game.gameCode,
                        game.cheatFolders.map { category ->
                            CheatFolder(
                                    category.cheatFolder.id,
                                    category.cheatFolder.name,
                                    category.cheats.map { cheat ->
                                        Cheat(
                                                cheat.id,
                                                cheat.name,
                                                cheat.description,
                                                cheat.code,
                                                cheat.enabled
                                        )
                                    }
                            )
                        }
                )
            }
        }.subscribeOn(Schedulers.io())
    }

    override fun getRomEnabledCheats(romInfo: RomInfo): Single<List<Cheat>> {
        return database.cheatDao().getEnabledRomCheats(romInfo.gameCode).map {
            it.map { cheat ->
                Cheat(
                        cheat.id,
                        cheat.name,
                        cheat.description,
                        cheat.code,
                        cheat.enabled
                )
            }
        }.subscribeOn(Schedulers.io())
    }

    override fun updateCheatsStatus(cheats: List<Cheat>): Completable {
        val cheatEntities = cheats.map {
            CheatStatusUpdate(it.id!!, it.enabled)
        }

        return Completable.create {
            database.cheatDao().updateCheatsStatus(cheatEntities)
            it.onComplete()
        }.subscribeOn(Schedulers.io())
    }

    override fun addGameCheats(game: Game) {
        val gameEntity = GameEntity(
                null,
                game.name,
                game.gameCode
        )

        val gameId = database.gameDao().insertGame(gameEntity)
        val categoryEntities = game.cheats.map { category ->
            CheatFolderEntity(
                    null,
                    gameId,
                    category.name
            )
        }
        val categoryIds = database.cheatFolderDao().insertCheatFolders(categoryEntities)

        val cheatEntities = game.cheats.zip(categoryIds).flatMap { pair ->
            pair.first.cheats.map {
                CheatEntity(
                        null,
                        pair.second,
                        it.name,
                        it.description,
                        it.code,
                        false
                )
            }
        }
        database.cheatDao().insertCheats(cheatEntities)
    }

    override fun deleteAllCheats() {
        database.gameDao().deleteAll()
    }

    override fun importCheats(uri: Uri) {
        val workRequest = OneTimeWorkRequestBuilder<CheatImportWorker>()
                .setInputData(workDataOf(CheatImportWorker.KEY_URI to uri.toString()))
                .build()

        WorkManager.getInstance(context).enqueueUniqueWork(IMPORT_WORKER_NAME, ExistingWorkPolicy.KEEP, workRequest)
    }

    override fun isCheatImportOngoing(): Boolean {
        val workManager = WorkManager.getInstance(context)
        val statuses = workManager.getWorkInfosForUniqueWork(IMPORT_WORKER_NAME)
        val infos = statuses.get()

        return infos.any { !it.state.isFinished }
    }

    override fun getCheatImportProgress(): Observable<CheatImportProgress> {
        return Observable.create<CheatImportProgress> { emitter ->
            val workManager = WorkManager.getInstance(context)
            val statuses = workManager.getWorkInfosForUniqueWork(IMPORT_WORKER_NAME)
            val infos = statuses.get()

            if (infos.all { it.state.isFinished }) {
                emitter.onNext(CheatImportProgress(CheatImportProgress.CheatImportStatus.NOT_IMPORTING, 0f, null))
                emitter.onComplete()
            } else {
                val observer = Observer<MutableList<WorkInfo>> {
                    val workInfo = it.firstOrNull()
                    if (workInfo != null) {
                        when (workInfo.state) {
                            WorkInfo.State.ENQUEUED -> CheatImportProgress(CheatImportProgress.CheatImportStatus.STARTING, 0f, null)
                            WorkInfo.State.RUNNING -> {
                                val relativeProgress = workInfo.progress.getFloat(CheatImportWorker.KEY_PROGRESS_RELATIVE, 0f)
                                val itemName = workInfo.progress.getString(CheatImportWorker.KEY_PROGRESS_ITEM)
                                CheatImportProgress(CheatImportProgress.CheatImportStatus.ONGOING, relativeProgress, itemName)
                            }
                            WorkInfo.State.SUCCEEDED -> CheatImportProgress(CheatImportProgress.CheatImportStatus.FINISHED, 1f, null)
                            WorkInfo.State.CANCELLED,
                            WorkInfo.State.FAILED -> CheatImportProgress(CheatImportProgress.CheatImportStatus.FAILED, 0f, null)
                            else -> null
                        }?.let { progress ->
                            emitter.onNext(progress)
                            if (progress.status == CheatImportProgress.CheatImportStatus.FAILED || progress.status == CheatImportProgress.CheatImportStatus.FINISHED) {
                                emitter.onComplete()
                            }
                        }
                    }
                }

                val workInfosLiveData = workManager.getWorkInfosForUniqueWorkLiveData(IMPORT_WORKER_NAME)
                workInfosLiveData.observeForever(observer)

                emitter.setCancellable {
                    workInfosLiveData.removeObserver(observer)
                }
            }
        }
    }
}