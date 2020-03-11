package org.alephium.appserver

import scala.concurrent.{ExecutionContext, Future}

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.ws.TextMessage
import akka.http.scaladsl.server.Directives.{complete, extractUpgradeToWebSocket, get, path}
import akka.http.scaladsl.server.Route
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.Timeout
import com.typesafe.scalalogging.StrictLogging
import io.circe.{Encoder, Json}
import io.circe.syntax._

import org.alephium.appserver.RPCModel._
import org.alephium.flow.client.Miner
import org.alephium.flow.core.FlowHandler
import org.alephium.flow.core.FlowHandler.BlockNotify
import org.alephium.flow.platform.PlatformProfile
import org.alephium.rpc.{CirceUtils, CORSHandler, JsonRPCHandler}
import org.alephium.rpc.model.JsonRPC.{Handler, Notification, Request, Response}
import org.alephium.util.EventBus

trait RPCServerAbstract extends StrictLogging {
  import RPCServerAbstract._

  implicit def system: ActorSystem
  implicit def materializer: ActorMaterializer
  implicit def executionContext: ExecutionContext
  implicit def config: PlatformProfile
  implicit def rpcConfig: RPCConfig
  implicit def askTimeout: Timeout

  def doBlockNotify(blockNotify: BlockNotify): Json
  def doBlockflowFetch(req: Request): FutureTry[FetchResponse]
  def doGetNeighborCliques(req: Request): FutureTry[NeighborCliques]
  def doGetSelfClique(req: Request): FutureTry[SelfClique]
  def doGetBalance(req: Request): FutureTry[Balance]
  def doTransfer(req: Request): FutureTry[TransferResult]
  def doStartMining(miner: ActorRef): FutureTry[Boolean] =
    execute(miner ! Miner.Start)
  def doStopMining(miner: ActorRef): FutureTry[Boolean] =
    execute(miner ! Miner.Stop)

  def runServer(): Future[Unit]

  def handleEvent(event: EventBus.Event): TextMessage = {
    event match {
      case bn @ FlowHandler.BlockNotify(_, _) =>
        val params       = doBlockNotify(bn)
        val notification = Notification("block_notify", params)
        TextMessage(CirceUtils.print(notification.asJson))
    }
  }

  def handlerRPC(miner: ActorRef): Handler = Map.apply(
    "blockflow_fetch"  -> (req => wrap(req, doBlockflowFetch(req))),
    "neighbor_cliques" -> (req => wrap(req, doGetNeighborCliques(req))),
    "self_clique"      -> (req => wrap(req, doGetSelfClique(req))),
    "get_balance"      -> (req => wrap(req, doGetBalance(req))),
    "transfer"         -> (req => wrap(req, doTransfer(req))),
    "mining_start"     -> (req => simpleWrap(req, doStartMining(miner))),
    "mining_stop"      -> (req => simpleWrap(req, doStopMining(miner)))
  )

  def routeHttp(miner: ActorRef): Route =
    CORSHandler(JsonRPCHandler.routeHttp(handlerRPC(miner)))

  def routeWs(eventBus: ActorRef): Route = {
    path("events") {
      CORSHandler(get {
        extractUpgradeToWebSocket { upgrade =>
          val (actor, source) =
            Source.actorRef(bufferSize, OverflowStrategy.fail).preMaterialize()
          eventBus.tell(EventBus.Subscribe, actor)
          val response = upgrade.handleMessages(wsFlow(eventBus, actor, source))
          complete(response)
        }
      })
    }
  }

  def wsFlow(eventBus: ActorRef,
             actor: ActorRef,
             source: Source[Nothing, NotUsed]): Flow[Any, TextMessage, Unit] = {
    Flow
      .fromSinkAndSourceCoupled(Sink.ignore, source.map(handleEvent))
      .watchTermination() { (_, termination) =>
        termination.onComplete(_ => eventBus.tell(EventBus.Unsubscribe, actor))
      }
  }
}

object RPCServerAbstract {
  import Response.Failure
  type Try[T]       = Either[Failure, T]
  type FutureTry[T] = Future[Try[T]]

  val bufferSize: Int = 64

  def execute(f: => Unit)(implicit ec: ExecutionContext): FutureTry[Boolean] =
    Future {
      f
      Right(true)
    }

  def wrap[T <: RPCModel: Encoder](req: Request, result: FutureTry[T])(
      implicit ec: ExecutionContext): Future[Response] = result.map {
    case Right(t)    => Response.successful(req, t)
    case Left(error) => error
  }

  // Note: use wrap when T derives RPCModel
  def simpleWrap[T: Encoder](req: Request, result: FutureTry[T])(
      implicit ec: ExecutionContext): Future[Response] = result.map {
    case Right(t)    => Response.successful(req, t)
    case Left(error) => error
  }
}