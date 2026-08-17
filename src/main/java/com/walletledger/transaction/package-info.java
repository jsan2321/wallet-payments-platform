@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"common", "ledger::input", "ledger::model", "account::input", "account::model"}
)
package com.walletledger.transaction;
