package com.presence.di

import android.content.Context
import android.content.SharedPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import javax.inject.Singleton

@InstallIn(SingletonComponent::class)
@Module
object AppModule {

    @Provides
    @Singleton
    fun sharedPrefs(@ApplicationContext ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("presence", Context.MODE_PRIVATE)

    @Provides
    @Singleton
    fun httpClient(): HttpClient = HttpClient(Android) {
        engine {
            connectTimeout = 30_000
            socketTimeout = 90_000 // /verify waits for on-chain confirmation; a free host may also be waking from sleep
        }
    }
}
