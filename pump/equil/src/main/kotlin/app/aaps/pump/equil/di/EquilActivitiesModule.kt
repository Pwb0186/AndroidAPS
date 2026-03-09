package app.aaps.pump.equil.di

import app.aaps.pump.equil.ui.EquilHistoryRecordActivity
import dagger.Module
import dagger.android.ContributesAndroidInjector
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
@Suppress("unused")
abstract class EquilActivitiesModule {

    @ContributesAndroidInjector abstract fun contributesEquilHistoryRecordActivity(): EquilHistoryRecordActivity
}
