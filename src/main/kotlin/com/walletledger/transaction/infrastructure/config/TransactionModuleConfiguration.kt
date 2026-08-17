package com.walletledger.transaction.infrastructure.config

import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration

@Configuration
@ComponentScan(basePackages = ["com.walletledger.transaction"])
class TransactionModuleConfiguration
