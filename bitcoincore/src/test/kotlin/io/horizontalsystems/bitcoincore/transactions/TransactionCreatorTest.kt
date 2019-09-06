package io.horizontalsystems.bitcoincore.transactions

import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import io.horizontalsystems.bitcoincore.Fixtures
import io.horizontalsystems.bitcoincore.managers.BloomFilterManager
import io.horizontalsystems.bitcoincore.transactions.builder.TransactionBuilder
import org.mockito.Mockito
import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.specification.describe

object TransactionCreatorTest : Spek({

    val transactionBuilder = Mockito.mock(TransactionBuilder::class.java)
    val transactionProcessor = Mockito.mock(TransactionProcessor::class.java)
    val transactionSender = Mockito.mock(TransactionSender::class.java)
    val transactionP2PKH = Fixtures.transactionP2PKH
    val bloomFilterManager = Mockito.mock(BloomFilterManager::class.java)
    val transactionCreator = TransactionCreator(transactionBuilder, transactionProcessor, transactionSender, bloomFilterManager)

    beforeEachTest {
        whenever(transactionBuilder.buildTransaction(any<Long>(), any(), any(), any())).thenReturn(transactionP2PKH)
    }

    describe("#create") {
        it("success") {
            transactionCreator.create("address", 10_000_000, 8, true)

            verify(transactionProcessor).processOutgoing(transactionP2PKH)
        }
    }
})

