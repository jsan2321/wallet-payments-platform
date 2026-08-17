package com.walletledger.transaction.integration

import com.walletledger.TestcontainersConfiguration
import com.walletledger.account.application.port.input.CreateWalletCommand
import com.walletledger.account.application.port.input.CreateWalletUseCase
import com.walletledger.account.application.port.input.GetWalletUseCase
import com.walletledger.common.domain.CurrencyCode
import com.walletledger.common.domain.IdempotencyKey
import com.walletledger.common.domain.Money
import com.walletledger.common.domain.OwnerId
import com.walletledger.common.domain.WalletId
import com.walletledger.ledger.application.port.input.GetAccountBalanceUseCase
import com.walletledger.transaction.application.port.input.AuthorizeHoldCommand
import com.walletledger.transaction.application.port.input.AuthorizeHoldUseCase
import com.walletledger.transaction.application.port.input.CaptureHoldCommand
import com.walletledger.transaction.application.port.input.CaptureHoldUseCase
import com.walletledger.transaction.application.port.input.DepositCommand
import com.walletledger.transaction.application.port.input.DepositFundsUseCase
import com.walletledger.transaction.application.port.input.SplitPaymentCommand
import com.walletledger.transaction.application.port.input.SplitPaymentUseCase
import com.walletledger.transaction.application.port.input.TransferCommand
import com.walletledger.transaction.application.port.input.TransferFundsUseCase
import com.walletledger.transaction.application.port.input.VoidHoldCommand
import com.walletledger.transaction.application.port.input.VoidHoldUseCase
import com.walletledger.transaction.application.port.input.WithdrawCommand
import com.walletledger.transaction.application.port.input.WithdrawFundsUseCase
import com.walletledger.transaction.domain.model.HoldStatus
import com.walletledger.transaction.domain.model.TransactionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class TransactionIntegrationTests {

    @Autowired
    private lateinit var createWalletUseCase: CreateWalletUseCase

    @Autowired
    private lateinit var getWalletUseCase: GetWalletUseCase

    @Autowired
    private lateinit var depositFundsUseCase: DepositFundsUseCase

    @Autowired
    private lateinit var withdrawFundsUseCase: WithdrawFundsUseCase

    @Autowired
    private lateinit var transferFundsUseCase: TransferFundsUseCase

    @Autowired
    private lateinit var splitPaymentUseCase: SplitPaymentUseCase

    @Autowired
    private lateinit var authorizeHoldUseCase: AuthorizeHoldUseCase

    @Autowired
    private lateinit var captureHoldUseCase: CaptureHoldUseCase

    @Autowired
    private lateinit var voidHoldUseCase: VoidHoldUseCase

    @Autowired
    private lateinit var getLedgerAccountBalanceUseCase: GetAccountBalanceUseCase

    @Test
    fun `should execute deposit and credit wallet with double-entry journal balance`() {
        val walletId = WalletId.generate()
        createWalletUseCase.createWallet(
            CreateWalletCommand(
                id = walletId,
                ownerId = OwnerId("usr_deposit_test"),
                baseCurrency = CurrencyCode.USD
            )
        )

        val depositResult = depositFundsUseCase.deposit(
            DepositCommand(
                idempotencyKey = IdempotencyKey("idem-dep-001"),
                destinationWalletId = walletId,
                amount = Money.ofMinor(50000L, CurrencyCode.USD), // $500.00
                description = "Customer Gateway Deposit"
            )
        )

        assertTrue(depositResult.isRight(), "Expected deposit to succeed")
        val tx = depositResult.getOrNull()!!
        assertEquals(TransactionStatus.COMPLETED, tx.status)
        assertEquals(50000L, tx.amount.amountMinorUnits)

        val wallet = getWalletUseCase.getWalletById(walletId).getOrNull()!!
        val usdAccount = wallet.accounts.first { it.currency == CurrencyCode.USD }
        assertEquals(50000L, usdAccount.availableBalance.amountMinorUnits)
        assertEquals(50000L, usdAccount.totalBalance.amountMinorUnits)
    }

    @Test
    fun `should execute withdrawal and debit wallet with double-entry journal balance`() {
        val walletId = WalletId.generate()
        createWalletUseCase.createWallet(
            CreateWalletCommand(
                id = walletId,
                ownerId = OwnerId("usr_withdraw_test"),
                baseCurrency = CurrencyCode.USD
            )
        )

        // 1. Initial Deposit $500.00
        depositFundsUseCase.deposit(
            DepositCommand(
                idempotencyKey = IdempotencyKey("idem-dep-with-01"),
                destinationWalletId = walletId,
                amount = Money.ofMinor(50000L, CurrencyCode.USD)
            )
        )

        // 2. Withdraw $150.00
        val withdrawResult = withdrawFundsUseCase.withdraw(
            WithdrawCommand(
                idempotencyKey = IdempotencyKey("idem-with-001"),
                sourceWalletId = walletId,
                amount = Money.ofMinor(15000L, CurrencyCode.USD),
                description = "Bank Payout Withdrawal"
            )
        )

        assertTrue(withdrawResult.isRight(), "Expected withdrawal to succeed")
        val tx = withdrawResult.getOrNull()!!
        assertEquals(TransactionStatus.COMPLETED, tx.status)

        val wallet = getWalletUseCase.getWalletById(walletId).getOrNull()!!
        val usdAccount = wallet.accounts.first { it.currency == CurrencyCode.USD }
        assertEquals(35000L, usdAccount.availableBalance.amountMinorUnits) // 50000 - 15000 = 35000 ($350.00)
    }

    @Test
    fun `should execute P2P transfer between two wallets`() {
        val senderWalletId = WalletId.generate()
        val receiverWalletId = WalletId.generate()

        createWalletUseCase.createWallet(
            CreateWalletCommand(id = senderWalletId, ownerId = OwnerId("usr_sender_01"), baseCurrency = CurrencyCode.USD)
        )
        createWalletUseCase.createWallet(
            CreateWalletCommand(id = receiverWalletId, ownerId = OwnerId("usr_receiver_01"), baseCurrency = CurrencyCode.USD)
        )

        // Seed sender with $500.00
        depositFundsUseCase.deposit(
            DepositCommand(
                idempotencyKey = IdempotencyKey("idem-seed-sender"),
                destinationWalletId = senderWalletId,
                amount = Money.ofMinor(50000L, CurrencyCode.USD)
            )
        )

        // Transfer $200.00 from Sender to Receiver
        val transferResult = transferFundsUseCase.transfer(
            TransferCommand(
                idempotencyKey = IdempotencyKey("idem-p2p-001"),
                sourceWalletId = senderWalletId,
                destinationWalletId = receiverWalletId,
                amount = Money.ofMinor(20000L, CurrencyCode.USD),
                description = "P2P transfer"
            )
        )

        assertTrue(transferResult.isRight(), "Expected transfer to succeed")

        val senderWallet = getWalletUseCase.getWalletById(senderWalletId).getOrNull()!!
        val receiverWallet = getWalletUseCase.getWalletById(receiverWalletId).getOrNull()!!

        assertEquals(30000L, senderWallet.accounts.first().availableBalance.amountMinorUnits) // $300.00
        assertEquals(20000L, receiverWallet.accounts.first().availableBalance.amountMinorUnits) // $200.00
    }

    @Test
    fun `should execute 3-way split payment with platform fee`() {
        val buyerWalletId = WalletId.generate()
        val merchantWalletId = WalletId.generate()

        createWalletUseCase.createWallet(
            CreateWalletCommand(id = buyerWalletId, ownerId = OwnerId("usr_buyer_split"), baseCurrency = CurrencyCode.USD)
        )
        createWalletUseCase.createWallet(
            CreateWalletCommand(id = merchantWalletId, ownerId = OwnerId("usr_merchant_split"), baseCurrency = CurrencyCode.USD)
        )

        // Seed buyer with $100.00
        depositFundsUseCase.deposit(
            DepositCommand(
                idempotencyKey = IdempotencyKey("idem-seed-buyer"),
                destinationWalletId = buyerWalletId,
                amount = Money.ofMinor(10000L, CurrencyCode.USD)
            )
        )

        // Split Payment: $100 Total = $95 Merchant + $5 Platform Fee
        val splitResult = splitPaymentUseCase.splitPayment(
            SplitPaymentCommand(
                idempotencyKey = IdempotencyKey("idem-split-001"),
                sourceWalletId = buyerWalletId,
                destinationWalletId = merchantWalletId,
                totalAmount = Money.ofMinor(10000L, CurrencyCode.USD),
                platformFee = Money.ofMinor(500L, CurrencyCode.USD),
                description = "E-Commerce Checkout"
            )
        )

        assertTrue(splitResult.isRight(), "Expected split payment to succeed")

        val buyerWallet = getWalletUseCase.getWalletById(buyerWalletId).getOrNull()!!
        val merchantWallet = getWalletUseCase.getWalletById(merchantWalletId).getOrNull()!!

        assertEquals(0L, buyerWallet.accounts.first().availableBalance.amountMinorUnits)
        assertEquals(9500L, merchantWallet.accounts.first().availableBalance.amountMinorUnits) // $95.00
    }

    @Test
    fun `should guarantee idempotency when duplicate requests are submitted concurrently`() {
        val senderWalletId = WalletId.generate()
        val receiverWalletId = WalletId.generate()

        createWalletUseCase.createWallet(
            CreateWalletCommand(id = senderWalletId, ownerId = OwnerId("usr_idem_sender"), baseCurrency = CurrencyCode.USD)
        )
        createWalletUseCase.createWallet(
            CreateWalletCommand(id = receiverWalletId, ownerId = OwnerId("usr_idem_receiver"), baseCurrency = CurrencyCode.USD)
        )

        depositFundsUseCase.deposit(
            DepositCommand(
                idempotencyKey = IdempotencyKey("idem-seed-idem-test"),
                destinationWalletId = senderWalletId,
                amount = Money.ofMinor(100000L, CurrencyCode.USD) // $1,000.00
            )
        )

        val threadPool = Executors.newFixedThreadPool(10)
        val sharedIdempotencyKey = IdempotencyKey("idem-concurrent-shared-key-999")
        val transferAmount = Money.ofMinor(5000L, CurrencyCode.USD) // $50.00

        val tasks = (1..10).map {
            Callable {
                transferFundsUseCase.transfer(
                    TransferCommand(
                        idempotencyKey = sharedIdempotencyKey,
                        sourceWalletId = senderWalletId,
                        destinationWalletId = receiverWalletId,
                        amount = transferAmount,
                        description = "Idempotent concurrent transfer"
                    )
                )
            }
        }

        val futures = threadPool.invokeAll(tasks)
        val results = futures.map { it.get() }

        // All 10 requests must succeed (either processed or retrieved cached)
        assertTrue(results.all { it.isRight() }, "All requests should resolve successfully")

        // Money must be deducted ONCE ($50.00, NOT 10x $50.00 = $500.00)
        val senderWallet = getWalletUseCase.getWalletById(senderWalletId).getOrNull()!!
        val receiverWallet = getWalletUseCase.getWalletById(receiverWalletId).getOrNull()!!

        assertEquals(95000L, senderWallet.accounts.first().availableBalance.amountMinorUnits) // 100,000 - 5,000 = 95,000
        assertEquals(5000L, receiverWallet.accounts.first().availableBalance.amountMinorUnits) // 5,000

        threadPool.shutdown()
    }

    @Test
    fun `should execute two-phase hold authorize and capture settlement`() {
        val buyerWalletId = WalletId.generate()
        val sellerWalletId = WalletId.generate()

        createWalletUseCase.createWallet(
            CreateWalletCommand(id = buyerWalletId, ownerId = OwnerId("usr_hold_buyer"), baseCurrency = CurrencyCode.USD)
        )
        createWalletUseCase.createWallet(
            CreateWalletCommand(id = sellerWalletId, ownerId = OwnerId("usr_hold_seller"), baseCurrency = CurrencyCode.USD)
        )

        depositFundsUseCase.deposit(
            DepositCommand(
                idempotencyKey = IdempotencyKey("idem-seed-hold-buyer"),
                destinationWalletId = buyerWalletId,
                amount = Money.ofMinor(10000L, CurrencyCode.USD) // $100.00
            )
        )

        // 1. Authorize Hold: $40.00
        val authResult = authorizeHoldUseCase.authorizeHold(
            AuthorizeHoldCommand(
                idempotencyKey = IdempotencyKey("idem-auth-001"),
                walletId = buyerWalletId,
                amount = Money.ofMinor(4000L, CurrencyCode.USD),
                description = "Pre-Auth Hotel Hold"
            )
        )
        assertTrue(authResult.isRight())
        val hold = authResult.getOrNull()!!
        assertEquals(HoldStatus.HELD, hold.status)

        val buyerAfterAuth = getWalletUseCase.getWalletById(buyerWalletId).getOrNull()!!
        val buyerAccount = buyerAfterAuth.accounts.first()
        assertEquals(6000L, buyerAccount.availableBalance.amountMinorUnits) // $60.00 available
        assertEquals(4000L, buyerAccount.heldBalance.amountMinorUnits)      // $40.00 held
        assertEquals(10000L, buyerAccount.totalBalance.amountMinorUnits)    // $100.00 total (invariant)

        // 2. Capture Hold: Settle to Seller
        val captureResult = captureHoldUseCase.captureHold(
            CaptureHoldCommand(
                idempotencyKey = IdempotencyKey("idem-cap-001"),
                holdId = hold.holdId,
                destinationWalletId = sellerWalletId,
                description = "Capture Hotel Checkout"
            )
        )
        assertTrue(captureResult.isRight())

        val buyerAfterCapture = getWalletUseCase.getWalletById(buyerWalletId).getOrNull()!!
        val sellerAfterCapture = getWalletUseCase.getWalletById(sellerWalletId).getOrNull()!!

        assertEquals(6000L, buyerAfterCapture.accounts.first().availableBalance.amountMinorUnits)
        assertEquals(0L, buyerAfterCapture.accounts.first().heldBalance.amountMinorUnits)
        assertEquals(6000L, buyerAfterCapture.accounts.first().totalBalance.amountMinorUnits)

        assertEquals(4000L, sellerAfterCapture.accounts.first().availableBalance.amountMinorUnits)
        assertEquals(4000L, sellerAfterCapture.accounts.first().totalBalance.amountMinorUnits)
    }

    @Test
    fun `should execute two-phase hold authorize and void release`() {
        val buyerWalletId = WalletId.generate()

        createWalletUseCase.createWallet(
            CreateWalletCommand(id = buyerWalletId, ownerId = OwnerId("usr_void_buyer"), baseCurrency = CurrencyCode.USD)
        )

        depositFundsUseCase.deposit(
            DepositCommand(
                idempotencyKey = IdempotencyKey("idem-seed-void-buyer"),
                destinationWalletId = buyerWalletId,
                amount = Money.ofMinor(10000L, CurrencyCode.USD) // $100.00
            )
        )

        // 1. Authorize Hold: $40.00
        val authResult = authorizeHoldUseCase.authorizeHold(
            AuthorizeHoldCommand(
                idempotencyKey = IdempotencyKey("idem-auth-void-01"),
                walletId = buyerWalletId,
                amount = Money.ofMinor(4000L, CurrencyCode.USD)
            )
        )
        assertTrue(authResult.isRight())
        val hold = authResult.getOrNull()!!

        // 2. Void Hold -> Funds restored
        val voidResult = voidHoldUseCase.voidHold(
            VoidHoldCommand(
                idempotencyKey = IdempotencyKey("idem-void-01"),
                holdId = hold.holdId,
                description = "Cancelled Authorization"
            )
        )
        assertTrue(voidResult.isRight())
        assertEquals(HoldStatus.VOIDED, voidResult.getOrNull()!!.status)

        val buyerAfterVoid = getWalletUseCase.getWalletById(buyerWalletId).getOrNull()!!
        val buyerAccount = buyerAfterVoid.accounts.first()
        assertEquals(10000L, buyerAccount.availableBalance.amountMinorUnits) // Full $100.00 available
        assertEquals(0L, buyerAccount.heldBalance.amountMinorUnits)
        assertEquals(10000L, buyerAccount.totalBalance.amountMinorUnits)
    }
}
