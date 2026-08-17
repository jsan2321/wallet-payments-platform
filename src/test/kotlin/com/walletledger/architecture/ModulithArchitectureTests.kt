package com.walletledger.architecture

import com.walletledger.WalletLedgerSystemApplication
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules
import org.springframework.modulith.docs.Documenter

class ModulithArchitectureTests {

    private val modules = ApplicationModules.of(WalletLedgerSystemApplication::class.java)

    @Test
    fun `verify spring modulith module encapsulation and rules`() {
        modules.verify()
    }

    @Test
    fun `generate spring modulith documentation`() {
        Documenter(modules)
            .writeDocumentation()
            .writeModulesAsPlantUml()
    }
}
