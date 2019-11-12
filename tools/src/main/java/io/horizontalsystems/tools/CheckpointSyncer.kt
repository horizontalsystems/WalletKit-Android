package io.horizontalsystems.tools

import io.horizontalsystems.bitcoincore.core.DoubleSha256Hasher
import io.horizontalsystems.bitcoincore.core.IConnectionManager
import io.horizontalsystems.bitcoincore.core.IConnectionManagerListener
import io.horizontalsystems.bitcoincore.extensions.toReversedHex
import io.horizontalsystems.bitcoincore.models.Block
import io.horizontalsystems.bitcoincore.network.Network
import io.horizontalsystems.bitcoincore.network.messages.*
import io.horizontalsystems.bitcoincore.network.peer.IPeerTaskHandler
import io.horizontalsystems.bitcoincore.network.peer.Peer
import io.horizontalsystems.bitcoincore.network.peer.PeerGroup
import io.horizontalsystems.bitcoincore.network.peer.PeerManager
import io.horizontalsystems.bitcoincore.network.peer.task.GetBlockHeadersTask
import io.horizontalsystems.bitcoincore.network.peer.task.PeerTask
import io.horizontalsystems.bitcoincore.storage.BlockHeader
import io.horizontalsystems.dashkit.MainNetDash
import io.horizontalsystems.dashkit.TestNetDash
import io.horizontalsystems.dashkit.X11Hasher
import java.util.*
import java.util.concurrent.Executors

class CheckpointSyncer(
        private val network: Network,
        private val checkpointInterval: Int,
        private val listener: Listener)
    : PeerGroup.Listener, IPeerTaskHandler {

    interface Listener {
        fun onSync(network: Network, checkpoints: List<Block>)
    }

    var isSynced: Boolean = false
        private set

    @Volatile
    private var syncPeer: Peer? = null
    private val peersQueue = Executors.newSingleThreadExecutor()
    private val peerManager = PeerManager()

    private val peerSize = 2
    private val peerGroup: PeerGroup

    private val checkpointBlock = network.lastCheckpointBlock
    private val checkpoints = mutableListOf(checkpointBlock)
    private val blocks = LinkedList<Block>().also {
        it.add(checkpoints.last())
    }

    init {
        val blockHeaderHasher = when (network) {
            is TestNetDash,
            is MainNetDash -> X11Hasher()
            else -> DoubleSha256Hasher()
        }

        val networkMessageParser = NetworkMessageParser(network.magic).apply {
            add(VersionMessageParser())
            add(VerAckMessageParser())
            add(InvMessageParser())
            add(HeadersMessageParser(blockHeaderHasher))
        }

        val networkMessageSerializer = NetworkMessageSerializer(network.magic).apply {
            add(VersionMessageSerializer())
            add(VerAckMessageSerializer())
            add(InvMessageSerializer())
            add(GetHeadersMessageSerializer())
            add(HeadersMessageSerializer())
        }

        val connectionManager = object : IConnectionManager {
            override val listener: IConnectionManagerListener? = null
            override val isConnected = true
        }

        val peerHostManager = PeerAddressManager(network)
        peerGroup = PeerGroup(peerHostManager, network, peerManager, peerSize, networkMessageParser, networkMessageSerializer, connectionManager, 0).also {
            peerHostManager.listener = it
        }

        peerGroup.addPeerGroupListener(this)
        peerGroup.peerTaskHandler = this
    }

    fun start() {
        isSynced = false
        peerGroup.start()
    }

    //  PeerGroup Listener

    override fun onPeerConnect(peer: Peer) {
        assignNextSyncPeer()
    }

    override fun onPeerDisconnect(peer: Peer, e: Exception?) {
        if (peer == syncPeer) {
            syncPeer = null
            assignNextSyncPeer()
        }
    }

    override fun onPeerReady(peer: Peer) {
        if (peer == syncPeer) {
            downloadBlockchain()
        }
    }

    //  IPeerTaskHandler

    override fun handleCompletedTask(peer: Peer, task: PeerTask): Boolean {
        if (task is GetBlockHeadersTask) {
            validateHeaders(peer, task.blockHeaders)
            return true
        }

        return false
    }

    private fun validateHeaders(peer: Peer, headers: Array<BlockHeader>) {
        var prevBlock = blocks.last()

        for (header in headers) {
            if (!prevBlock.headerHash.contentEquals(header.previousBlockHeaderHash)) {
                syncPeer = null
                assignNextSyncPeer()
                break
            }

            val newBlock = Block(header, prevBlock.height + 1)
            if (newBlock.height % checkpointInterval == 0) {
                print("Checkpoint block ${header.hash.toReversedHex()} at height ${newBlock.height}, time ${header.timestamp}")
                checkpoints.add(newBlock)
            }

            blocks.add(newBlock)
            prevBlock = newBlock
        }

        if (headers.size < 2000) {
            peer.synced = true
        }

        downloadBlockchain()
    }

    private fun assignNextSyncPeer() {
        peersQueue.execute {
            if (peerManager.connected().none { !it.synced }) {
                isSynced = true
                peerGroup.stop()
                listener.onSync(network, checkpoints)
                print("Synced")

                return@execute
            }

            if (syncPeer == null) {
                val notSyncedPeers = peerManager.sorted().filter { !it.synced }
                notSyncedPeers.firstOrNull { it.ready }?.let { nonSyncedPeer ->
                    syncPeer = nonSyncedPeer

                    downloadBlockchain()
                }
            }
        }
    }

    private fun downloadBlockchain() {
        val peer = syncPeer
        if (peer == null || !peer.ready) {
            return
        }

        if (peer.synced) {
            syncPeer = null
            assignNextSyncPeer()
        } else {
            peer.addTask(GetBlockHeadersTask(getBlockLocatorHashes()))
        }
    }

    private fun getBlockLocatorHashes(): List<ByteArray> {
        return if (blocks.isEmpty()) {
            listOf(checkpoints.last().headerHash)
        } else {
            listOf(blocks.last().headerHash)
        }
    }

    // Writing to file

//    private fun writeCheckpoints() {
//        val file = File(checkpointFile)
//        val outputStream = OutputStreamWriter(FileOutputStream(file), StandardCharsets.US_ASCII)
//
//        val buffer = ByteBuffer.allocate(80 + 4) // header + block height
//        val writer = PrintWriter(outputStream)
//
//        val checkpoint = checkpoints.last()
//        buffer.put(serialize(checkpoint))
//        buffer.putInt(checkpoint.height)
//        writer.println(buffer.array().toHexString());
//        buffer.position(0)
//
//        writer.close()
//    }
//
//    private fun serialize(block: Block): ByteArray {
//        val payload = BitcoinOutput().also {
//            it.writeInt(block.version)
//            it.write(block.previousBlockHash)
//            it.write(block.merkleRoot)
//            it.writeUnsignedInt(block.timestamp)
//            it.writeUnsignedInt(block.bits)
//            it.writeUnsignedInt(block.nonce)
//        }
//
//        return payload.toByteArray()
//    }

    private fun print(message: String) {
        println("${network.javaClass.simpleName}: $message")
    }
}