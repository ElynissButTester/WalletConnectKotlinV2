package com.walletconnect.android.impl.di

import com.walletconnect.android.relay.RelayConnectionInterface
import org.koin.dsl.module

fun networkModule(relay: RelayConnectionInterface) = module {

    single { relay }
}