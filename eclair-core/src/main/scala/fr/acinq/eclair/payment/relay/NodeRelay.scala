/*
 * Copyright 2020 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.payment.relay

import akka.actor.typed.Behavior
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.adapter.{TypedActorContextOps, TypedActorRefOps}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.{ActorRef, typed}
import com.softwaremill.quicklens.ModifyPimp
import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.eclair.channel.{CMD_FAIL_HTLC, CMD_FULFILL_HTLC}
import fr.acinq.eclair.db.PendingCommandsDb
import fr.acinq.eclair.payment.IncomingPaymentPacket.NodeRelayPacket
import fr.acinq.eclair.payment.Monitoring.{Metrics, Tags}
import fr.acinq.eclair.payment.OutgoingPaymentPacket.Upstream
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.receive.MultiPartPaymentFSM
import fr.acinq.eclair.payment.receive.MultiPartPaymentFSM.HtlcPart
import fr.acinq.eclair.payment.send.CompactBlindedPathsResolver.Resolve
import fr.acinq.eclair.payment.send.MultiPartPaymentLifecycle.{PreimageReceived, SendMultiPartPayment}
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentConfig
import fr.acinq.eclair.payment.send.PaymentLifecycle.SendPaymentToNode
import fr.acinq.eclair.payment.send._
import fr.acinq.eclair.router.Router.RouteParams
import fr.acinq.eclair.router.{BalanceTooLow, RouteNotFound}
import fr.acinq.eclair.wire.protocol.PaymentOnion.IntermediatePayload
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{CltvExpiry, Features, Logs, MilliSatoshi, NodeParams, TimestampMilli, UInt64, nodeFee, randomBytes32, randomKey}

import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.collection.immutable.Queue

/**
 * It [[NodeRelay]] aggregates incoming HTLCs (in case multi-part was used upstream) and then forwards the requested amount (using the
 * router to find a route to the remote node and potentially splitting the payment using multi-part).
 */
object NodeRelay {

  // @formatter:off
  sealed trait Command
  case class Relay(nodeRelayPacket: IncomingPaymentPacket.NodeRelayPacket) extends Command
  case object Stop extends Command
  private case class WrappedMultiPartExtraPaymentReceived(mppExtraReceived: MultiPartPaymentFSM.ExtraPaymentReceived[HtlcPart]) extends Command
  private case class WrappedMultiPartPaymentFailed(mppFailed: MultiPartPaymentFSM.MultiPartPaymentFailed) extends Command
  private case class WrappedMultiPartPaymentSucceeded(mppSucceeded: MultiPartPaymentFSM.MultiPartPaymentSucceeded) extends Command
  private case class WrappedPreimageReceived(preimageReceived: PreimageReceived) extends Command
  private case class WrappedPaymentSent(paymentSent: PaymentSent) extends Command
  private case class WrappedPaymentFailed(paymentFailed: PaymentFailed) extends Command
  private[relay] case class WrappedPeerReadyResult(result: AsyncPaymentTriggerer.Result) extends Command
  private case class WrappedResolvedPaths(resolved: Seq[PaymentBlindedRoute]) extends Command
  // @formatter:on

  trait OutgoingPaymentFactory {
    def spawnOutgoingPayFSM(context: ActorContext[NodeRelay.Command], cfg: SendPaymentConfig, multiPart: Boolean): ActorRef
  }

  case class SimpleOutgoingPaymentFactory(nodeParams: NodeParams, router: ActorRef, register: ActorRef) extends OutgoingPaymentFactory {
    val paymentFactory = PaymentInitiator.SimplePaymentFactory(nodeParams, router, register)

    override def spawnOutgoingPayFSM(context: ActorContext[Command], cfg: SendPaymentConfig, multiPart: Boolean): ActorRef = {
      if (multiPart) {
        context.toClassic.actorOf(MultiPartPaymentLifecycle.props(nodeParams, cfg, publishPreimage = true, router, paymentFactory))
      } else {
        context.toClassic.actorOf(PaymentLifecycle.props(nodeParams, cfg, router, register))
      }
    }
  }

  def apply(nodeParams: NodeParams,
            parent: typed.ActorRef[NodeRelayer.Command],
            register: ActorRef,
            relayId: UUID,
            nodeRelayPacket: NodeRelayPacket,
            outgoingPaymentFactory: OutgoingPaymentFactory,
            triggerer: typed.ActorRef[AsyncPaymentTriggerer.Command],
            router: ActorRef): Behavior[Command] =
    Behaviors.setup { context =>
      val paymentHash = nodeRelayPacket.add.paymentHash
      val totalAmountIn = nodeRelayPacket.outerPayload.totalAmount
      Behaviors.withMdc(Logs.mdc(
        category_opt = Some(Logs.LogCategory.PAYMENT),
        parentPaymentId_opt = Some(relayId), // for a node relay, we use the same identifier for the whole relay itself, and the outgoing payment
        paymentHash_opt = Some(paymentHash))) {
        context.log.debug("relaying payment relayId={}", relayId)
        val mppFsmAdapters = {
          context.messageAdapter[MultiPartPaymentFSM.ExtraPaymentReceived[HtlcPart]](WrappedMultiPartExtraPaymentReceived)
          context.messageAdapter[MultiPartPaymentFSM.MultiPartPaymentFailed](WrappedMultiPartPaymentFailed)
          context.messageAdapter[MultiPartPaymentFSM.MultiPartPaymentSucceeded](WrappedMultiPartPaymentSucceeded)
        }.toClassic
        val incomingPaymentHandler = context.actorOf(MultiPartPaymentFSM.props(nodeParams, paymentHash, totalAmountIn, mppFsmAdapters))
        val nextPacket_opt = nodeRelayPacket match {
          case IncomingPaymentPacket.RelayToTrampolinePacket(_, _, _, nextPacket) => Some(nextPacket)
          case _: IncomingPaymentPacket.RelayToBlindedPathsPacket => None
        }
        new NodeRelay(nodeParams, parent, register, relayId, paymentHash, nodeRelayPacket.outerPayload.paymentSecret, context, outgoingPaymentFactory, triggerer, router)
          .receiving(Queue.empty, nodeRelayPacket.innerPayload, nextPacket_opt, incomingPaymentHandler)
      }
    }

  private def validateRelay(nodeParams: NodeParams, upstream: Upstream.Trampoline, payloadOut: IntermediatePayload.NodeRelay): Option[FailureMessage] = {
    val fee = nodeFee(nodeParams.relayParams.minTrampolineFees, payloadOut.amountToForward)
    if (upstream.amountIn - payloadOut.amountToForward < fee) {
      Some(TrampolineFeeInsufficient())
    } else if (upstream.expiryIn - payloadOut.outgoingCltv < nodeParams.channelConf.expiryDelta) {
      Some(TrampolineExpiryTooSoon())
    } else if (payloadOut.outgoingCltv <= CltvExpiry(nodeParams.currentBlockHeight)) {
      Some(TrampolineExpiryTooSoon())
    } else if (payloadOut.amountToForward <= MilliSatoshi(0)) {
      Some(InvalidOnionPayload(UInt64(2), 0))
    } else {
      payloadOut match {
        case payloadOut: IntermediatePayload.NodeRelay.Standard =>
          if (payloadOut.invoiceFeatures.isDefined && payloadOut.paymentSecret.isEmpty) {
            Some(InvalidOnionPayload(UInt64(8), 0)) // payment secret field is missing
          } else {
            None
          }
        case _: IntermediatePayload.NodeRelay.ToBlindedPaths =>
          None
      }
    }
  }

  /** Compute route params that honor our fee and cltv requirements. */
  private def computeRouteParams(nodeParams: NodeParams, amountIn: MilliSatoshi, expiryIn: CltvExpiry, amountOut: MilliSatoshi, expiryOut: CltvExpiry): RouteParams = {
    nodeParams.routerConf.pathFindingExperimentConf.getRandomConf().getDefaultRouteParams
      .modify(_.boundaries.maxFeeProportional).setTo(0) // we disable percent-based max fee calculation, we're only interested in collecting our node fee
      .modify(_.boundaries.maxCltv).setTo(expiryIn - expiryOut)
      .modify(_.boundaries.maxFeeFlat).setTo(amountIn - amountOut)
      .modify(_.includeLocalChannelCost).setTo(true)
  }

  /**
   * This helper method translates relaying errors (returned by the downstream nodes) to a BOLT 4 standard error that we
   * should return upstream.
   */
  private def translateError(nodeParams: NodeParams, failures: Seq[PaymentFailure], upstream: Upstream.Trampoline, nextPayload: IntermediatePayload.NodeRelay): Option[FailureMessage] = {
    val routeNotFound = failures.collectFirst { case f@LocalFailure(_, _, RouteNotFound) => f }.nonEmpty
    val routingFeeHigh = upstream.amountIn - nextPayload.amountToForward >= nodeFee(nodeParams.relayParams.minTrampolineFees, nextPayload.amountToForward) * 5
    failures match {
      case Nil => None
      case LocalFailure(_, _, BalanceTooLow) :: Nil if routingFeeHigh =>
        // We have direct channels to the target node, but not enough outgoing liquidity to use those channels.
        // The routing fee proposed by the sender was high enough to find alternative, indirect routes, but didn't yield
        // any result so we tell them that we don't have enough outgoing liquidity at the moment.
        Some(TemporaryNodeFailure())
      case LocalFailure(_, _, BalanceTooLow) :: Nil => Some(TrampolineFeeInsufficient()) // a higher fee/cltv may find alternative, indirect routes
      case _ if routeNotFound => Some(TrampolineFeeInsufficient()) // if we couldn't find routes, it's likely that the fee/cltv was insufficient
      case _ =>
        // Otherwise, we try to find a downstream error that we could decrypt.
        val outgoingNodeFailure = nextPayload match {
          case nextPayload: IntermediatePayload.NodeRelay.Standard => failures.collectFirst { case RemoteFailure(_, _, e) if e.originNode == nextPayload.outgoingNodeId => e.failureMessage }
          // When using blinded paths, we will never get a failure from the final node (for privacy reasons).
          case _: IntermediatePayload.NodeRelay.ToBlindedPaths => None
        }
        val otherNodeFailure = failures.collectFirst { case RemoteFailure(_, _, e) => e.failureMessage }
        val failure = outgoingNodeFailure.getOrElse(otherNodeFailure.getOrElse(TemporaryNodeFailure()))
        Some(failure)
    }
  }

}

/**
 * see https://doc.akka.io/docs/akka/current/typed/style-guide.html#passing-around-too-many-parameters
 */
class NodeRelay private(nodeParams: NodeParams,
                        parent: akka.actor.typed.ActorRef[NodeRelayer.Command],
                        register: ActorRef,
                        relayId: UUID,
                        paymentHash: ByteVector32,
                        paymentSecret: ByteVector32,
                        context: ActorContext[NodeRelay.Command],
                        outgoingPaymentFactory: NodeRelay.OutgoingPaymentFactory,
                        triggerer: typed.ActorRef[AsyncPaymentTriggerer.Command],
                        router: ActorRef) {

  import NodeRelay._

  /**
   * We start by aggregating an incoming HTLC set. Once we received the whole set, we will compute a route to the next
   * trampoline node and forward the payment.
   *
   * @param htlcs          received incoming HTLCs for this set.
   * @param nextPayload    relay instructions (should be identical across HTLCs in this set).
   * @param nextPacket_opt trampoline onion to relay to the next trampoline node.
   * @param handler        actor handling the aggregation of the incoming HTLC set.
   */
  private def receiving(htlcs: Queue[Upstream.ReceivedHtlc], nextPayload: IntermediatePayload.NodeRelay, nextPacket_opt: Option[OnionRoutingPacket], handler: ActorRef): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case Relay(packet: IncomingPaymentPacket.NodeRelayPacket) =>
        require(packet.outerPayload.paymentSecret == paymentSecret, "payment secret mismatch")
        context.log.debug("forwarding incoming htlc #{} from channel {} to the payment FSM", packet.add.id, packet.add.channelId)
        handler ! MultiPartPaymentFSM.HtlcPart(packet.outerPayload.totalAmount, packet.add)
        receiving(htlcs :+ Upstream.ReceivedHtlc(packet.add, TimestampMilli.now()), nextPayload, nextPacket_opt, handler)
      case WrappedMultiPartPaymentFailed(MultiPartPaymentFSM.MultiPartPaymentFailed(_, failure, parts)) =>
        context.log.warn("could not complete incoming multi-part payment (parts={} paidAmount={} failure={})", parts.size, parts.map(_.amount).sum, failure)
        Metrics.recordPaymentRelayFailed(failure.getClass.getSimpleName, Tags.RelayType.Trampoline)
        parts.collect { case p: MultiPartPaymentFSM.HtlcPart => rejectHtlc(p.htlc.id, p.htlc.channelId, p.amount, Some(failure)) }
        stopping()
      case WrappedMultiPartPaymentSucceeded(MultiPartPaymentFSM.MultiPartPaymentSucceeded(_, parts)) =>
        context.log.info("completed incoming multi-part payment with parts={} paidAmount={}", parts.size, parts.map(_.amount).sum)
        val upstream = Upstream.Trampoline(htlcs)
        validateRelay(nodeParams, upstream, nextPayload) match {
          case Some(failure) =>
            context.log.warn(s"rejecting trampoline payment reason=$failure")
            rejectPayment(upstream, Some(failure))
            stopping()
          case None =>
            nextPayload match {
              // TODO: async payments are not currently supported for blinded recipients. We should update the AsyncPaymentTriggerer to decrypt the blinded path.
              case nextPayload: IntermediatePayload.NodeRelay.Standard if nextPayload.isAsyncPayment && nodeParams.features.hasFeature(Features.AsyncPaymentPrototype) =>
                waitForTrigger(upstream, nextPayload, nextPacket_opt)
              case _ =>
                doSend(upstream, nextPayload, nextPacket_opt)
            }
        }
    }

  private def waitForTrigger(upstream: Upstream.Trampoline, nextPayload: IntermediatePayload.NodeRelay.Standard, nextPacket_opt: Option[OnionRoutingPacket]): Behavior[Command] = {
    context.log.info(s"waiting for async payment to trigger before relaying trampoline payment (amountIn=${upstream.amountIn} expiryIn=${upstream.expiryIn} amountOut=${nextPayload.amountToForward} expiryOut=${nextPayload.outgoingCltv}, asyncPaymentsParams=${nodeParams.relayParams.asyncPaymentsParams})")
    val timeoutBlock = nodeParams.currentBlockHeight + nodeParams.relayParams.asyncPaymentsParams.holdTimeoutBlocks
    val safetyBlock = (upstream.expiryIn - nodeParams.relayParams.asyncPaymentsParams.cancelSafetyBeforeTimeout).blockHeight
    // wait for notification until which ever occurs first: the hold timeout block or the safety block
    val notifierTimeout = Seq(timeoutBlock, safetyBlock).min
    val peerReadyResultAdapter = context.messageAdapter[AsyncPaymentTriggerer.Result](WrappedPeerReadyResult)

    triggerer ! AsyncPaymentTriggerer.Watch(peerReadyResultAdapter, nextPayload.outgoingNodeId, paymentHash, notifierTimeout)
    context.system.eventStream ! EventStream.Publish(WaitingToRelayPayment(nextPayload.outgoingNodeId, paymentHash))
    Behaviors.receiveMessagePartial {
      case WrappedPeerReadyResult(AsyncPaymentTriggerer.AsyncPaymentTimeout) =>
        context.log.warn("rejecting async payment; was not triggered before block {}", notifierTimeout)
        rejectPayment(upstream, Some(TemporaryNodeFailure())) // TODO: replace failure type when async payment spec is finalized
        stopping()
      case WrappedPeerReadyResult(AsyncPaymentTriggerer.AsyncPaymentCanceled) =>
        context.log.warn(s"payment sender canceled a waiting async payment")
        rejectPayment(upstream, Some(TemporaryNodeFailure())) // TODO: replace failure type when async payment spec is finalized
        stopping()
      case WrappedPeerReadyResult(AsyncPaymentTriggerer.AsyncPaymentTriggered) =>
        doSend(upstream, nextPayload, nextPacket_opt)
    }
  }

  private def doSend(upstream: Upstream.Trampoline, nextPayload: IntermediatePayload.NodeRelay, nextPacket_opt: Option[OnionRoutingPacket]): Behavior[Command] = {
    context.log.debug(s"relaying trampoline payment (amountIn=${upstream.amountIn} expiryIn=${upstream.expiryIn} amountOut=${nextPayload.amountToForward} expiryOut=${nextPayload.outgoingCltv})")
    relay(upstream, nextPayload, nextPacket_opt)
  }

  /**
   * Once the payment is forwarded, we're waiting for fail/fulfill responses from downstream nodes.
   *
   * @param upstream          complete HTLC set received.
   * @param nextPayload       relay instructions.
   * @param fulfilledUpstream true if we already fulfilled the payment upstream.
   */
  private def sending(upstream: Upstream.Trampoline, nextPayload: IntermediatePayload.NodeRelay, startedAt: TimestampMilli, fulfilledUpstream: Boolean): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      rejectExtraHtlcPartialFunction orElse {
        // this is the fulfill that arrives from downstream channels
        case WrappedPreimageReceived(PreimageReceived(_, paymentPreimage)) =>
          if (!fulfilledUpstream) {
            // We want to fulfill upstream as soon as we receive the preimage (even if not all HTLCs have fulfilled downstream).
            context.log.debug("got preimage from downstream")
            fulfillPayment(upstream, paymentPreimage)
            sending(upstream, nextPayload, startedAt, fulfilledUpstream = true)
          } else {
            // we don't want to fulfill multiple times
            Behaviors.same
          }
        case WrappedPaymentSent(paymentSent) =>
          context.log.debug("trampoline payment fully resolved downstream")
          success(upstream, fulfilledUpstream, paymentSent)
          recordRelayDuration(startedAt, isSuccess = true)
          stopping()
        case WrappedPaymentFailed(PaymentFailed(_, _, failures, _)) =>
          context.log.debug(s"trampoline payment failed downstream")
          if (!fulfilledUpstream) {
            rejectPayment(upstream, translateError(nodeParams, failures, upstream, nextPayload))
          }
          recordRelayDuration(startedAt, isSuccess = fulfilledUpstream)
          stopping()
      }
    }

  /**
   * Once the downstream payment is settled (fulfilled or failed), we reject new upstream payments while we wait for our parent to stop us.
   */
  private def stopping(): Behavior[Command] = {
    parent ! NodeRelayer.RelayComplete(context.self, paymentHash, paymentSecret)
    Behaviors.receiveMessagePartial {
      rejectExtraHtlcPartialFunction orElse {
        case Stop => Behaviors.stopped
      }
    }
  }

  private val payFsmAdapters = {
    context.messageAdapter[PreimageReceived](WrappedPreimageReceived)
    context.messageAdapter[PaymentSent](WrappedPaymentSent)
    context.messageAdapter[PaymentFailed](WrappedPaymentFailed)
  }.toClassic

  private def relay(upstream: Upstream.Trampoline, payloadOut: IntermediatePayload.NodeRelay, packetOut_opt: Option[OnionRoutingPacket]): Behavior[Command] = {
    val displayNodeId = payloadOut match {
      case payloadOut: IntermediatePayload.NodeRelay.Standard => payloadOut.outgoingNodeId
      case _: IntermediatePayload.NodeRelay.ToBlindedPaths => randomKey().publicKey
    }
    val paymentCfg = SendPaymentConfig(relayId, relayId, None, paymentHash, displayNodeId, upstream, None, None, storeInDb = false, publishEvent = false, recordPathFindingMetrics = true)
    val routeParams = computeRouteParams(nodeParams, upstream.amountIn, upstream.expiryIn, payloadOut.amountToForward, payloadOut.outgoingCltv)
    payloadOut match {
      case payloadOut: IntermediatePayload.NodeRelay.Standard =>
        // If invoice features are provided in the onion, the sender is asking us to relay to a non-trampoline recipient.
        payloadOut.invoiceFeatures match {
          case Some(features) =>
            val extraEdges = payloadOut.invoiceRoutingInfo.getOrElse(Nil).flatMap(Bolt11Invoice.toExtraEdges(_, payloadOut.outgoingNodeId))
            val paymentSecret = payloadOut.paymentSecret.get // NB: we've verified that there was a payment secret in validateRelay
            val recipient = ClearRecipient(payloadOut.outgoingNodeId, Features(features).invoiceFeatures(), payloadOut.amountToForward, payloadOut.outgoingCltv, paymentSecret, extraEdges, payloadOut.paymentMetadata)
            context.log.debug("sending the payment to non-trampoline recipient (MPP={})", recipient.features.hasFeature(Features.BasicMultiPartPayment))
            relayToRecipient(upstream, payloadOut, recipient, paymentCfg, routeParams, useMultiPart = recipient.features.hasFeature(Features.BasicMultiPartPayment))
          case None =>
            context.log.debug("sending the payment to the next trampoline node")
            val paymentSecret = randomBytes32() // we generate a new secret to protect against probing attacks
            val recipient = ClearRecipient(payloadOut.outgoingNodeId, Features.empty, payloadOut.amountToForward, payloadOut.outgoingCltv, paymentSecret, nextTrampolineOnion_opt = packetOut_opt)
            relayToRecipient(upstream, payloadOut, recipient, paymentCfg, routeParams, useMultiPart = true)
        }
      case payloadOut: IntermediatePayload.NodeRelay.ToBlindedPaths =>
        context.spawnAnonymous(CompactBlindedPathsResolver(router)) ! Resolve(context.messageAdapter[Seq[PaymentBlindedRoute]](WrappedResolvedPaths), payloadOut.outgoingBlindedPaths)
        waitForResolvedPaths(upstream, payloadOut, paymentCfg, routeParams)
    }
  }

  private def relayToRecipient(upstream: Upstream.Trampoline,
                               payloadOut: IntermediatePayload.NodeRelay,
                               recipient: Recipient,
                               paymentCfg: SendPaymentConfig,
                               routeParams: RouteParams,
                               useMultiPart: Boolean): Behavior[Command] = {
    val payment =
      if (useMultiPart) {
        SendMultiPartPayment(payFsmAdapters, recipient, nodeParams.maxPaymentAttempts, routeParams)
      } else {
        SendPaymentToNode(payFsmAdapters, recipient, nodeParams.maxPaymentAttempts, routeParams)
      }
    val payFSM = outgoingPaymentFactory.spawnOutgoingPayFSM(context, paymentCfg, useMultiPart)
    payFSM ! payment
    sending(upstream, payloadOut, TimestampMilli.now(), fulfilledUpstream = false)
  }

  /**
   * Blinded paths in Bolt 12 invoices may encode the introduction node with an scid and a direction: we need to resolve
   * that to a nodeId in order to reach that introduction node and use the blinded path.
   */
  private def waitForResolvedPaths(upstream: Upstream.Trampoline,
                                   payloadOut: IntermediatePayload.NodeRelay.ToBlindedPaths,
                                   paymentCfg: SendPaymentConfig,
                                   routeParams: RouteParams): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case WrappedResolvedPaths(resolved) if resolved.isEmpty =>
        context.log.warn(s"rejecting trampoline payment to blinded paths: no usable blinded path")
        rejectPayment(upstream, Some(UnknownNextPeer()))
        stopping()
      case WrappedResolvedPaths(resolved) =>
        val features = Features(payloadOut.invoiceFeatures).invoiceFeatures()
        // We don't have access to the invoice: we use the only node_id that somewhat makes sense for the recipient.
        val blindedNodeId = resolved.head.route.blindedNodeIds.last
        val recipient = BlindedRecipient.fromPaths(blindedNodeId, features, payloadOut.amountToForward, payloadOut.outgoingCltv, resolved, Set.empty)
        context.log.debug("sending the payment to blinded recipient, useMultiPart={}", features.hasFeature(Features.BasicMultiPartPayment))
        relayToRecipient(upstream, payloadOut, recipient, paymentCfg, routeParams, features.hasFeature(Features.BasicMultiPartPayment))
    }

  private def rejectExtraHtlcPartialFunction: PartialFunction[Command, Behavior[Command]] = {
    case Relay(nodeRelayPacket) =>
      rejectExtraHtlc(nodeRelayPacket.add)
      Behaviors.same
    // NB: this message would be sent from the payment FSM which we stopped before going to this state, but all this is asynchronous.
    // We always fail extraneous HTLCs. They are a spec violation from the sender, but harmless in the relay case.
    // By failing them fast (before the payment has reached the final recipient) there's a good chance the sender won't lose any money.
    // We don't expect to relay pay-to-open payments.
    case WrappedMultiPartExtraPaymentReceived(extraPaymentReceived) =>
      rejectExtraHtlc(extraPaymentReceived.payment.htlc)
      Behaviors.same
  }

  private def rejectExtraHtlc(add: UpdateAddHtlc): Unit = {
    context.log.warn("rejecting extra htlc #{} from channel {}", add.id, add.channelId)
    rejectHtlc(add.id, add.channelId, add.amountMsat)
  }

  private def rejectHtlc(htlcId: Long, channelId: ByteVector32, amount: MilliSatoshi, failure: Option[FailureMessage] = None): Unit = {
    val failureMessage = failure.getOrElse(IncorrectOrUnknownPaymentDetails(amount, nodeParams.currentBlockHeight))
    val cmd = CMD_FAIL_HTLC(htlcId, Right(failureMessage), commit = true)
    PendingCommandsDb.safeSend(register, nodeParams.db.pendingCommands, channelId, cmd)
  }

  private def rejectPayment(upstream: Upstream.Trampoline, failure: Option[FailureMessage]): Unit = {
    Metrics.recordPaymentRelayFailed(failure.map(_.getClass.getSimpleName).getOrElse("Unknown"), Tags.RelayType.Trampoline)
    upstream.adds.foreach(r => rejectHtlc(r.add.id, r.add.channelId, upstream.amountIn, failure))
  }

  private def fulfillPayment(upstream: Upstream.Trampoline, paymentPreimage: ByteVector32): Unit = upstream.adds.foreach(r => {
    val cmd = CMD_FULFILL_HTLC(r.add.id, paymentPreimage, commit = true)
    PendingCommandsDb.safeSend(register, nodeParams.db.pendingCommands, r.add.channelId, cmd)
  })

  private def success(upstream: Upstream.Trampoline, fulfilledUpstream: Boolean, paymentSent: PaymentSent): Unit = {
    // We may have already fulfilled upstream, but we can now emit an accurate relayed event and clean-up resources.
    if (!fulfilledUpstream) {
      fulfillPayment(upstream, paymentSent.paymentPreimage)
    }
    val incoming = upstream.adds.map(r => PaymentRelayed.IncomingPart(r.add.amountMsat, r.add.channelId, r.receivedAt))
    val outgoing = paymentSent.parts.map(part => PaymentRelayed.OutgoingPart(part.amountWithFees, part.toChannelId, part.timestamp))
    context.system.eventStream ! EventStream.Publish(TrampolinePaymentRelayed(paymentHash, incoming, outgoing, paymentSent.recipientNodeId, paymentSent.recipientAmount))
  }

  private def recordRelayDuration(startedAt: TimestampMilli, isSuccess: Boolean): Unit =
    Metrics.RelayedPaymentDuration
      .withTag(Tags.Relay, Tags.RelayType.Trampoline)
      .withTag(Tags.Success, isSuccess)
      .record((TimestampMilli.now() - startedAt).toMillis, TimeUnit.MILLISECONDS)
}
