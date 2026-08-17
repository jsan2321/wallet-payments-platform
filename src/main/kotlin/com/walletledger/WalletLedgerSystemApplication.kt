package com.walletledger

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class WalletLedgerSystemApplication

fun main(args: Array<String>) {
    runApplication<WalletLedgerSystemApplication>(*args)
}
