package org.alephium.flow.core

import akka.actor.{ActorRef, ActorSystem}

import org.alephium.flow.platform.PlatformConfig
import org.alephium.protocol.model.ChainIndex

final case class AllHandlers(
    flowHandler: ActorRef,
    txHandler: ActorRef,
    blockHandlers: Map[ChainIndex, ActorRef],
    headerHandlers: Map[ChainIndex, ActorRef])(implicit config: PlatformConfig) {

  def getBlockHandler(chainIndex: ChainIndex): ActorRef = {
    assert(chainIndex.relateTo(config.brokerInfo))
    blockHandlers(chainIndex)
  }

  def getHeaderHandler(chainIndex: ChainIndex): ActorRef = {
    assert(!chainIndex.relateTo(config.brokerInfo))
    headerHandlers(chainIndex)
  }
}

object AllHandlers {
  def build(system: ActorSystem, cliqueManager: ActorRef, blockFlow: BlockFlow, eventBus: ActorRef)(
      implicit config: PlatformConfig): AllHandlers = {
    val flowProps   = FlowHandler.props(blockFlow, eventBus)
    val flowHandler = system.actorOf(flowProps, "FlowHandler")
    buildWithFlowHandler(system, cliqueManager, blockFlow, flowHandler)
  }
  def buildWithFlowHandler(system: ActorSystem,
                           cliqueManager: ActorRef,
                           blockFlow: BlockFlow,
                           flowHandler: ActorRef)(implicit config: PlatformConfig): AllHandlers = {
    val txProps        = TxHandler.props(blockFlow, cliqueManager)
    val txHandler      = system.actorOf(txProps, "TxHandler")
    val blockHandlers  = buildBlockHandlers(system, cliqueManager, blockFlow, flowHandler)
    val headerHandlers = buildHeaderHandlers(system, blockFlow, flowHandler)
    AllHandlers(flowHandler, txHandler, blockHandlers, headerHandlers)
  }

  private def buildBlockHandlers(
      system: ActorSystem,
      cliqueManager: ActorRef,
      blockFlow: BlockFlow,
      flowHandler: ActorRef)(implicit config: PlatformConfig): Map[ChainIndex, ActorRef] = {
    val handlers = for {
      from <- 0 until config.groups
      to   <- 0 until config.groups
      chainIndex = ChainIndex.unsafe(from, to)
      if chainIndex.relateTo(config.brokerInfo)
    } yield {
      val handler = system.actorOf(
        BlockChainHandler.props(blockFlow, chainIndex, cliqueManager, flowHandler),
        s"BlockChainHandler-$from-$to")
      chainIndex -> handler
    }
    handlers.toMap
  }

  private def buildHeaderHandlers(system: ActorSystem, blockFlow: BlockFlow, flowHandler: ActorRef)(
      implicit config: PlatformConfig): Map[ChainIndex, ActorRef] = {
    val headerHandlers = for {
      from <- 0 until config.groups
      to   <- 0 until config.groups
      chainIndex = ChainIndex.unsafe(from, to)
      if !chainIndex.relateTo(config.brokerInfo)
    } yield {
      val headerHander = system.actorOf(
        HeaderChainHandler.props(blockFlow, chainIndex, flowHandler),
        s"HeaderChainHandler-$from-$to")
      chainIndex -> headerHander
    }
    headerHandlers.toMap
  }
}
