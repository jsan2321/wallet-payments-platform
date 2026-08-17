package com.walletledger.account.infrastructure.adapter.output.event

import com.walletledger.account.application.port.output.WalletEventPublisherPort
import com.walletledger.account.domain.event.HoldPlacedDomainEvent
import com.walletledger.account.domain.event.HoldReleasedDomainEvent
import com.walletledger.account.domain.event.WalletAccountAddedDomainEvent
import com.walletledger.account.domain.event.WalletCreatedDomainEvent
import com.walletledger.account.domain.event.WalletStatusChangedDomainEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

@Component
class SpringEventWalletPublisherAdapter(
    private val applicationEventPublisher: ApplicationEventPublisher
) : WalletEventPublisherPort {

    override fun publish(event: WalletCreatedDomainEvent) {
        applicationEventPublisher.publishEvent(event)
    }

    override fun publish(event: WalletAccountAddedDomainEvent) {
        applicationEventPublisher.publishEvent(event)
    }

    override fun publish(event: HoldPlacedDomainEvent) {
        applicationEventPublisher.publishEvent(event)
    }

    override fun publish(event: HoldReleasedDomainEvent) {
        applicationEventPublisher.publishEvent(event)
    }

    override fun publish(event: WalletStatusChangedDomainEvent) {
        applicationEventPublisher.publishEvent(event)
    }
}
