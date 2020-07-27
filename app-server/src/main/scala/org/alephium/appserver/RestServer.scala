package org.alephium.appserver

import scala.collection.immutable.ArraySeq
import scala.concurrent._

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import ch.megard.akka.http.cors.scaladsl.CorsDirectives._
import com.typesafe.scalalogging.StrictLogging
import sttp.tapir.docs.openapi.RichOpenAPIServerEndpoints
import sttp.tapir.openapi.OpenAPI
import sttp.tapir.openapi.circe.yaml.RichOpenAPI
import sttp.tapir.server.akkahttp._

import org.alephium.appserver.ApiModel._
import org.alephium.flow.client.Miner
import org.alephium.flow.core.{BlockFlow, TxHandler}
import org.alephium.flow.platform.{Mode, PlatformConfig}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.util.{ActorRefT, Duration, Service}

// scalastyle:off method.length
class RestServer(mode: Mode, port: Int, miner: ActorRefT[Miner.Command])(
    implicit config: PlatformConfig,
    actorSystem: ActorSystem,
    protected val executionContext: ExecutionContext)
    extends Endpoints
    with Service
    with StrictLogging {

  private val blockFlow: BlockFlow                    = mode.node.blockFlow
  private val txHandler: ActorRefT[TxHandler.Command] = mode.node.allHandlers.txHandler
  private val terminationHardDeadline                 = Duration.ofSecondsUnsafe(10).asScala

  implicit val apiConfig: ApiConfig     = ApiConfig.load(config.aleph)
  implicit val groupConfig: GroupConfig = config
  implicit val askTimeout: Timeout      = Timeout(apiConfig.askTimeout.asScala)

  private val getBlockflowLogic = getBlockflow.serverLogic { timeInterval =>
    Future.successful(
      ServerUtils.getBlockflow(blockFlow, FetchRequest(timeInterval.from, timeInterval.to)))
  }

  private val getBlockLogic = getBlock.serverLogic { hash =>
    Future.successful(ServerUtils.getBlock(blockFlow, GetBlock(hash)))
  }

  private val getBalanceLogic = getBalance.serverLogic { address =>
    Future.successful(ServerUtils.getBalance(blockFlow, GetBalance(address)))
  }

  private val getGroupLogic = getGroup.serverLogic { address =>
    Future.successful(ServerUtils.getGroup(blockFlow, GetGroup(address)))
  }

  private val getHashesAtHeightLogic = getHashesAtHeight.serverLogic {
    case (from, to, height) =>
      Future.successful(
        ServerUtils.getHashesAtHeight(blockFlow,
                                      ChainIndex(from, to),
                                      GetHashesAtHeight(from.value, to.value, height)))
  }

  private val getChainInfoLogic = getChainInfo.serverLogic {
    case (from, to) =>
      Future.successful(ServerUtils.getChainInfo(blockFlow, ChainIndex(from, to)))
  }

  private val createTransactionLogic = createTransaction.serverLogic {
    case (fromKey, toAddress, value) =>
      Future.successful(
        ServerUtils.createTransaction(blockFlow, CreateTransaction(fromKey, toAddress, value)))
  }

  private val sendTransactionLogic = sendTransaction.serverLogic {
    case (_, transaction) =>
      ServerUtils.sendTransaction(txHandler, transaction)
  }

  private val minerActionLogic = minerAction.serverLogic {
    case (_, action) =>
      action match {
        case MinerAction.StartMining => ServerUtils.execute(miner ! Miner.Start)
        case MinerAction.StopMining  => ServerUtils.execute(miner ! Miner.Stop)
      }
  }

  private val docs: OpenAPI = List(
    getBlockflowLogic,
    getBlockLogic,
    getBalanceLogic,
    getGroupLogic,
    getHashesAtHeightLogic,
    getChainInfoLogic,
    createTransactionLogic,
    sendTransactionLogic,
    minerActionLogic
  ).toOpenAPI("Alephium BlockFlow API", "1.0")

  private val getOpenapiRoute = getOpenapi.toRoute(_ => Future.successful(Right(docs.toYaml)))

  val route: Route =
    cors()(
      getBlockflowLogic.toRoute ~
        getBlockLogic.toRoute ~
        getBalanceLogic.toRoute ~
        getGroupLogic.toRoute ~
        getHashesAtHeightLogic.toRoute ~
        getChainInfoLogic.toRoute ~
        createTransactionLogic.toRoute ~
        sendTransactionLogic.toRoute ~
        minerActionLogic.toRoute ~
        getOpenapiRoute
    )

  private val httpBindingPromise: Promise[Http.ServerBinding] = Promise()

  override def subServices: ArraySeq[Service] = ArraySeq(mode)

  protected def startSelfOnce(): Future[Unit] = {
    for {
      httpBinding <- Http()
        .bindAndHandle(route, apiConfig.networkInterface.getHostAddress, port)
    } yield {
      logger.info(s"Listening http request on $httpBinding")
      httpBindingPromise.success(httpBinding)
    }
  }

  protected def stopSelfOnce(): Future[Unit] =
    for {
      httpBinding <- httpBindingPromise.future
      httpStop    <- httpBinding.terminate(hardDeadline = terminationHardDeadline)
    } yield {
      logger.info(s"http unbound with message $httpStop.")
      ()
    }
}

object RestServer {
  def apply(mode: Mode, miner: ActorRefT[Miner.Command])(
      implicit system: ActorSystem,
      config: PlatformConfig,
      executionContext: ExecutionContext): RestServer = {
    (for {
      restPort <- mode.config.restPort
    } yield {
      new RestServer(mode, restPort, miner)
    }) match {
      case Some(server) => server
      case None         => throw new RuntimeException("rpc and ws ports are required")
    }
  }
}