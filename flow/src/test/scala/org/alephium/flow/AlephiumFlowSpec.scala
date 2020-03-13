package org.alephium.flow

import org.scalatest.BeforeAndAfter

import org.alephium.crypto.{ED25519PrivateKey, ED25519PublicKey}
import org.alephium.flow.core.TestUtils
import org.alephium.flow.io.RocksDBStorage
import org.alephium.flow.io.RocksDBStorage.Settings
import org.alephium.flow.platform.{NewConfig, Platform, PlatformConfig}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.GroupIndex
import org.alephium.util.{AlephiumActorSpec, AlephiumSpec, AVector, Env}

trait AlephiumFlowSpec extends AlephiumSpec with BeforeAndAfter {
  val genesisBalance: BigInt = 100

  val env      = Env.resolve()
  val rootPath = Platform.getRootPath(env)

  val newPath   = rootPath.resolveSibling(rootPath.getFileName + "-" + this.getClass.getSimpleName)
  val newConfig = NewConfig.parseConfig(newPath)
  val groups0   = newConfig.getInt("alephium.groups")

  val groupConfig = new GroupConfig { override def groups: Int = groups0 }

  val genesisBalances = AVector.tabulate[(ED25519PrivateKey, ED25519PublicKey, BigInt)](groups0) {
    i =>
      val groupIndex              = GroupIndex.unsafe(i)(groupConfig)
      val (privateKey, publicKey) = groupIndex.generateP2pkhKey(groupConfig)
      (privateKey, publicKey, genesisBalance)
  }
  implicit val config =
    PlatformConfig.build(newConfig,
                         newPath,
                         Settings.syncWrite,
                         Some(genesisBalances.map(p => (p._2, p._3))))

  after {
    TestUtils.clear(config.disk.blockFolder)
    RocksDBStorage.dESTROY(config.headerDB.storage)
  }
}

class AlephiumFlowActorSpec(name: String) extends AlephiumActorSpec(name) with AlephiumFlowSpec
