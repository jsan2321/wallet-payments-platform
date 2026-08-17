package com.walletledger.account.infrastructure.config

import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration

@Configuration
@ComponentScan(basePackages = ["com.walletledger.account"])
class AccountModuleConfiguration
