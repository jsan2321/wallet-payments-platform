package com.walletledger.ledger.infrastructure.adapter.output.event

import com.walletledger.ledger.application.port.output.LedgerEventPublisherPort
import com.walletledger.ledger.domain.event.JournalEntryRecordedDomainEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

@Component
class SpringEventLedgerEventPublisherAdapter(
    private val applicationEventPublisher: ApplicationEventPublisher
) : LedgerEventPublisherPort {

    override fun publish(event: JournalEntryRecordedDomainEvent) {
        applicationEventPublisher.publishEvent(event)
    }
}
