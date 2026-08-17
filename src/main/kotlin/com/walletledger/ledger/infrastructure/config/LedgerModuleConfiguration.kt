package com.walletledger.ledger.infrastructure.config

import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration

@Configuration
@ComponentScan(basePackages = ["com.walletledger.ledger"])
class LedgerModuleConfiguration
