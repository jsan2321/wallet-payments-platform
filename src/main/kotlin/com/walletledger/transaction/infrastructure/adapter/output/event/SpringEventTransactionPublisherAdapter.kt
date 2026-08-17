package com.walletledger.transaction.infrastructure.adapter.output.event

import com.walletledger.transaction.application.port.output.TransactionEventPublisherPort
import com.walletledger.transaction.domain.event.HoldCapturedDomainEvent
import com.walletledger.transaction.domain.event.HoldVoidedDomainEvent
import com.walletledger.transaction.domain.event.TransactionCompletedDomainEvent
import com.walletledger.transaction.domain.event.TransactionFailedDomainEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

@Component
class SpringEventTransactionPublisherAdapter(
    private val applicationEventPublisher: ApplicationEventPublisher
) : TransactionEventPublisherPort {

    override fun publish(event: TransactionCompletedDomainEvent) {
        applicationEventPublisher.publishEvent(event)
    }

    override fun publish(event: TransactionFailedDomainEvent) {
        applicationEventPublisher.publishEvent(event)
    }

    override fun publish(event: HoldCapturedDomainEvent) {
        applicationEventPublisher.publishEvent(event)
    }

    override fun publish(event: HoldVoidedDomainEvent) {
        applicationEventPublisher.publishEvent(event)
    }
}
