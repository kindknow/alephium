package org.alephium.flow.platform

import org.alephium.flow.client.{Miner, Node}
import org.alephium.flow.network.clique.BrokerHandler

// scalastyle:off magic.number
trait Mode {
  implicit def config: PlatformConfig

  def port: Int

  def rpcHttpPort: Int = port + 1000
  // TODO Comment for review:
  // I would prefer to do `rpcHttpPort + 1` like parity, but that would not work locally.
  def rpcWsPort: Int = port + 2000

  def builders: Mode.Builder = Mode.defaultBuilders

  def node: Node
}
// scalastyle:on magic.number

object Mode {

  type Builder = BrokerHandler.Builder with Miner.Builder

  def defaultBuilders: Builder = new BrokerHandler.Builder with Miner.Builder

  class Aws extends Mode {
    final implicit val config: PlatformConfig = PlatformConfig.loadDefault()

    val port: Int = config.publicAddress.getPort

    override val node: Node = Node(builders, "Root")
  }

  class Local extends Mode {
    final implicit val config: PlatformConfig = PlatformConfig.loadDefault()

    val port: Int = config.publicAddress.getPort

    override val node: Node = Node(builders, "Root")
  }
}
