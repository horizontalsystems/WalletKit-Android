package io.horizontalsystems.bitcoincore.blocks

import io.horizontalsystems.bitcoincore.BitcoinCore
import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.core.ISyncStateListener
import io.horizontalsystems.bitcoincore.managers.PublicKeyManager
import io.horizontalsystems.bitcoincore.managers.BloomFilterManager
import io.horizontalsystems.bitcoincore.models.Block
import io.horizontalsystems.bitcoincore.models.BlockHash
import io.horizontalsystems.bitcoincore.models.MerkleBlock
import io.horizontalsystems.bitcoincore.network.Network
import io.horizontalsystems.bitcoincore.transactions.TransactionProcessor

class BlockSyncer(
        private val storage: IStorage,
        private val blockchain: Blockchain,
        private val transactionProcessor: TransactionProcessor,
        private val publicKeyManager: PublicKeyManager,
        private val listener: ISyncStateListener,
        private val checkpointBlock: Block,
        private val state: State = State()) {

    private val sqliteMaxVariableNumber = 999

    val localDownloadedBestBlockHeight: Int
        get() = storage.lastBlock()?.height ?: 0

    val localKnownBestBlockHeight: Int
        get() {
            val blockHashes = storage.getBlockchainBlockHashes()
            val headerHashes = blockHashes.map { it.headerHash }
            val existingBlocksCount = headerHashes.chunked(sqliteMaxVariableNumber).map {
                storage.blocksCount(it)
            }.sum()

            return localDownloadedBestBlockHeight.plus(blockHashes.size - existingBlocksCount)
        }

    init {
        listener.onInitialBestBlockHeightUpdate(localDownloadedBestBlockHeight)
    }

    fun prepareForDownload() {
        handlePartialBlocks()

        clearPartialBlocks()
        clearBlockHashes() // we need to clear block hashes when "syncPeer" is disconnected

        blockchain.handleFork()
    }

    fun downloadStarted() {
    }

    fun downloadIterationCompleted() {
        if (state.iterationHasPartialBlocks) {
            handlePartialBlocks()
        }
    }

    fun downloadCompleted() {
        blockchain.handleFork()
    }

    fun downloadFailed() {
        prepareForDownload()
    }

    fun getBlockHashes(): List<BlockHash> {
        return storage.getBlockHashesSortedBySequenceAndHeight(limit = 500)
    }

    fun getBlockLocatorHashes(peerLastBlockHeight: Int): List<ByteArray> {
        val result = mutableListOf<ByteArray>()

        storage.getLastBlockchainBlockHash()?.headerHash?.let {
            result.add(it)
        }

        if (result.isEmpty()) {
            storage.getBlocks(heightGreaterThan = checkpointBlock.height, sortedBy = "height", limit = 10).forEach {
                result.add(it.headerHash)
            }
        }

        val lastBlock = storage.getBlock(peerLastBlockHeight)
        if (lastBlock == null) {
            result.add(checkpointBlock.headerHash)
        } else if (!result.contains(lastBlock.headerHash)) {
            result.add(lastBlock.headerHash)
        }

        return result
    }

    fun addBlockHashes(blockHashes: List<ByteArray>) {
        var lastSequence = storage.getLastBlockHash()?.sequence ?: 0

        val existingHashes = storage.getBlockHashHeaderHashes()
        val newBlockHashes = blockHashes.filter { existingHashes.none { n -> n.contentEquals(it) } }.map {
            BlockHash(it, 0, ++lastSequence)
        }

        storage.addBlockHashes(newBlockHashes)
    }

    fun handleMerkleBlock(merkleBlock: MerkleBlock, maxBlockHeight: Int) {
        val height = merkleBlock.height

        val block = when (height) {
            null -> blockchain.connect(merkleBlock)
            else -> blockchain.forceAdd(merkleBlock, height)
        }

        try {
            transactionProcessor.processIncoming(merkleBlock.associatedTransactions, block, state.iterationHasPartialBlocks)
        } catch (e: BloomFilterManager.BloomFilterExpired) {
            state.iterationHasPartialBlocks = true
        }

        if (!state.iterationHasPartialBlocks) {
            storage.deleteBlockHash(block.headerHash)
        }

        listener.onCurrentBestBlockHeightUpdate(block.height, maxBlockHeight)
    }

    fun shouldRequest(blockHash: ByteArray): Boolean {
        return storage.getBlock(blockHash) == null
    }

    private fun clearPartialBlocks() {
        val toDelete = storage.getBlockHashHeaderHashes(except = checkpointBlock.headerHash)

        toDelete.chunked(sqliteMaxVariableNumber).forEach {
            val blocksToDelete = storage.getBlocks(hashes = it)
            blockchain.deleteBlocks(blocksToDelete)
        }
    }

    private fun handlePartialBlocks() {
        publicKeyManager.fillGap()
        state.iterationHasPartialBlocks = false
    }

    private fun clearBlockHashes() {
        storage.deleteBlockchainBlockHashes()
    }

    class State(var iterationHasPartialBlocks: Boolean = false)

    companion object {
        fun getCheckpointBlock(syncMode: BitcoinCore.SyncMode, network: Network, storage: IStorage): Block {
            val lastBlock = storage.lastBlock()

            val checkpointBlock = if (syncMode is BitcoinCore.SyncMode.Full) {
                network.bip44CheckpointBlock
            } else if (lastBlock != null && lastBlock.height < network.lastCheckpointBlock.height) {
                // during app updating there may be case when the last block in DB is earlier than new checkpoint block
                // in this case we set the very first checkpoint block for bip44,
                // since it surely will be earlier than the last block in DB
                network.bip44CheckpointBlock
            } else {
                network.lastCheckpointBlock
            }

            if (lastBlock == null) {
                storage.saveBlock(checkpointBlock)
            }

            return checkpointBlock
        }
    }

}
