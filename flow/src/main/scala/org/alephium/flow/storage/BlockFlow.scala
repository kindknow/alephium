package org.alephium.flow.storage

import org.alephium.crypto.Keccak256
import org.alephium.flow.PlatformConfig
import org.alephium.flow.model.BlockDeps
import org.alephium.protocol.model.{Block, BlockHeader, ChainIndex, GroupIndex}
import org.alephium.util.AVector

import scala.reflect.ClassTag

class BlockFlow()(implicit val config: PlatformConfig) extends MultiChain {
  import config.genesisBlocks

  private def mainGroup: GroupIndex = config.mainGroup

  override val groups = config.groups

  private val inBlockChains: AVector[BlockChain] = AVector.tabulate(groups - 1) { k =>
    BlockChain.fromGenesis(genesisBlocks(if (k < mainGroup.value) k else k + 1)(mainGroup.value))
  }
  private val outBlockChains: AVector[BlockChain] = AVector.tabulate(groups) { to =>
    BlockChain.fromGenesis(genesisBlocks(mainGroup.value)(to))
  }
  private val blockHeaderChains: AVector[AVector[BlockHeaderPool with BlockHashChain]] =
    AVector.tabulate(groups, groups) {
      case (from, to) =>
        if (from == mainGroup.value) outBlockChains(to)
        else if (to == mainGroup.value) {
          inBlockChains(if (from < mainGroup.value) from else from - 1)
        } else BlockHeaderChain.fromGenesis(genesisBlocks(from)(to))
    }

  override protected def aggregate[T: ClassTag](f: BlockHashPool => T)(op: (T, T) => T): T = {
    blockHeaderChains.reduceBy { chains =>
      chains.reduceBy(f)(op)
    }(op)
  }

  override def numTransactions: Int = {
    inBlockChains.sumBy(_.numTransactions) + outBlockChains.sumBy(_.numTransactions)
  }

  override protected def getBlockChain(from: GroupIndex, to: GroupIndex): BlockChain = {
    assert(from == mainGroup || to == mainGroup)
    if (from == mainGroup) outBlockChains(to.value)
    else inBlockChains(if (from.value < mainGroup.value) from.value else from.value - 1)
  }

  override protected def getHeaderChain(from: GroupIndex, to: GroupIndex): BlockHeaderPool = {
    blockHeaderChains(from.value)(to.value)
  }

  override protected def getHashChain(from: GroupIndex, to: GroupIndex): BlockHashChain = {
    blockHeaderChains(from.value)(to.value)
  }

  def add(block: Block): AddBlockResult = {
    val index = block.chainIndex
    if (index.from != mainGroup && index.to != mainGroup) {
      AddBlockResult.InvalidIndex
    } else if (contains(block.hash)) {
      AddBlockResult.AlreadyExisted
    } else {
      val deps        = block.blockHeader.blockDeps
      val missingDeps = deps.filterNot(contains)
      if (missingDeps.isEmpty) {
        val chain  = getBlockChain(index)
        val parent = block.uncleHash(index.to)
        val weight = calWeight(block)
        chain.add(block, parent, weight)
      } else {
        AddBlockResult.MissingDeps(missingDeps)
      }
    }
  }

  def add(block: Block, weight: Int): AddBlockResult = {
    add(block, block.parentHash, weight)
  }

  def add(block: Block, parentHash: Keccak256, weight: Int): AddBlockResult = {
    val chain = getBlockChain(block)
    chain.add(block, parentHash, weight)
  }

  def add(header: BlockHeader): AddBlockHeaderResult = {
    val index = header.chainIndex
    if (index.from == mainGroup || index.to == mainGroup) {
      AddBlockHeaderResult.Other("Block is expected, not BlockHeader")
    } else if (contains(header.hash)) {
      AddBlockHeaderResult.AlreadyExisted
    } else {
      val deps        = header.blockDeps
      val missingDeps = deps.filterNot(contains)
      if (missingDeps.isEmpty) {
        val chain  = getHeaderChain(index)
        val parent = header.uncleHash(index.to)
        val weight = calWeight(header)
        chain.add(header, parent, weight)
      } else {
        AddBlockHeaderResult.MissingDeps(missingDeps)
      }
    }
  }

  def add(header: BlockHeader, weight: Int): AddBlockHeaderResult = {
    add(header, header.parentHash, weight)
  }

  def add(header: BlockHeader, parentHash: Keccak256, weight: Int): AddBlockHeaderResult = {
    val chain = getHeaderChain(header)
    chain.add(header, parentHash, weight)
  }

  private def calWeight(block: Block): Int = {
    calWeight(block.blockHeader)
  }

  private def calWeight(header: BlockHeader): Int = {
    val deps = header.blockDeps
    if (deps.isEmpty) 0
    else {
      val weight1 = deps.dropRight(groups).sumBy(calGroupWeight)
      val weight2 = deps.takeRight(groups).sumBy(getHeight)
      weight1 + weight2 + 1
    }
  }

  private def calGroupWeight(hash: Keccak256): Int = {
    val deps = getBlockHeader(hash).blockDeps
    if (deps.isEmpty) 0
    else {
      deps.takeRight(groups).sumBy(getHeight) + 1
    }
  }

  override def getBestTip: Keccak256 = {
    val ordering = Ordering.Int.on[Keccak256](getWeight)
    aggregate(_.getBestTip)(ordering.max)
  }

  override def getAllTips: AVector[Keccak256] = {
    aggregate(_.getAllTips)(_ ++ _)
  }

  def getRtips(tip: Keccak256, from: GroupIndex): Array[Keccak256] = {
    val rdeps = new Array[Keccak256](groups)
    rdeps(from.value) = tip

    val header = getBlockHeader(tip)
    val deps   = header.blockDeps
    if (deps.isEmpty) {
      0 until groups foreach { k =>
        if (k != from.value) rdeps(k) = genesisBlocks(k).head.hash
      }
    } else {
      0 until groups foreach { k =>
        if (k < from.value) rdeps(k) = deps(k)
        else if (k > from.value) rdeps(k) = deps(k - 1)
      }
    }
    rdeps
  }

  def isExtending(current: Keccak256, previous: Keccak256): Boolean = {
    val index1 = ChainIndex.fromHash(current)
    val index2 = ChainIndex.fromHash(previous)
    assert(index1.from == index2.from)

    val chain = getHashChain(index2)
    if (index1.to == index2.to) chain.isBefore(previous, current)
    else {
      val groupDeps = getGroupDeps(current, index1.from)
      chain.isBefore(previous, groupDeps(index2.to.value))
    }
  }

  def isCompatible(rtips: IndexedSeq[Keccak256], tip: Keccak256, from: GroupIndex): Boolean = {
    val newRtips = getRtips(tip, from)
    assert(rtips.size == newRtips.length)
    rtips.indices forall { k =>
      val t1 = rtips(k)
      val t2 = newRtips(k)
      isExtending(t1, t2) || isExtending(t2, t1)
    }
  }

  def updateRtips(rtips: Array[Keccak256], tip: Keccak256, from: GroupIndex): Unit = {
    val newRtips = getRtips(tip, from)
    assert(rtips.length == newRtips.length)
    rtips.indices foreach { k =>
      val t1 = rtips(k)
      val t2 = newRtips(k)
      if (isExtending(t2, t1)) {
        rtips(k) = t2
      }
    }
  }

  def getGroupDeps(tip: Keccak256, from: GroupIndex): AVector[Keccak256] = {
    val deps = getBlockHeader(tip).blockDeps
    if (deps.isEmpty) {
      genesisBlocks(from.value).map(_.hash)
    } else {
      deps.takeRight(groups)
    }
  }

  def getBestDeps(chainIndex: ChainIndex): BlockDeps = {
    val bestTip   = getBestTip
    val bestIndex = ChainIndex.fromHash(bestTip)
    val rtips     = getRtips(bestTip, bestIndex.from)
    val deps1 = (0 until groups)
      .filter(_ != chainIndex.from.value)
      .foldLeft(AVector.empty[Keccak256]) {
        case (deps, _k) =>
          val k = GroupIndex(_k)
          if (k == bestIndex.from) deps :+ bestTip
          else {
            val toTries = (0 until groups).foldLeft(AVector.empty[Keccak256]) { (acc, _l) =>
              val l = GroupIndex(_l)
              acc ++ getHashChain(k, l).getAllTips
            }
            val validTries = toTries.filter(tip => isCompatible(rtips, tip, k))
            if (validTries.isEmpty) deps :+ rtips(k.value)
            else {
              val bestTry = validTries.maxBy(getWeight) // TODO: improve
              updateRtips(rtips, bestTry, k)
              deps :+ bestTry
            }
          }
      }
    val groupTip  = rtips(chainIndex.from.value)
    val groupDeps = getGroupDeps(groupTip, chainIndex.from)
    val deps2 = (0 until groups)
      .foldLeft(deps1) {
        case (deps, _l) =>
          val l          = GroupIndex(_l)
          val chain      = getHashChain(chainIndex.from, l)
          val toTries    = chain.getAllTips
          val validTries = toTries.filter(tip => chain.isBefore(groupDeps(l.value), tip))
          if (validTries.isEmpty) deps :+ groupDeps(l.value)
          else {
            val bestTry = validTries.maxBy(getWeight) // TODO: improve
            deps :+ bestTry
          }
      }
    BlockDeps(chainIndex, deps2)
  }
}

object BlockFlow {
  def apply()(implicit config: PlatformConfig): BlockFlow = new BlockFlow()

  case class BlockInfo(timestamp: Long, chainIndex: ChainIndex)
}