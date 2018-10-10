package com.sbuslab.stash

import java.io.IOException
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.control.NonFatal

import akka.actor.ActorSystem
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

import com.sbuslab.model.{InternalServerError, UnrecoverableFailures}
import com.sbuslab.sbus._
import com.sbuslab.utils.Logging


@Autowired
@Component
class StashService(
  sbus: Sbus,
  stashRepo: StashRepository,
  mapper: ObjectMapper,
  actorSystem: ActorSystem
)(implicit ec: ExecutionContext) extends Logging {

  private val ExpirationTimeout = 2.minutes

  actorSystem.scheduler.schedule(ExpirationTimeout, ExpirationTimeout) {
    val expired = stashRepo.proceedExpiredOperations(ExpirationTimeout.toMillis)

    log.debug("Found {} expired operations, retry...", expired.size())

    expired forEach { op ⇒
      log.debug("Retry expired operation: " + op)
      sendCommand(op)
    }
  }

  def stash(correlationId: String, command: AnyRef, context: Context)(f: ⇒ Future[_]): Future[_] =
    newOperation(correlationId, command, context) match {
      case Some(accepted) ⇒
        log.debug("Accept and run new operation: {} with {} and {} with context {}", accepted.getRoutingKey, accepted.getCorrelationId, accepted.getMessageId, context)

        (try f catch {
          case NonFatal(e) ⇒ Future.failed(e)
        }) andThen {
          case Failure(e) if UnrecoverableFailures.contains(e) ⇒
            log.warn("Unrecoverable failure on Stash: {}, complete and check next for {}, messageId = {}", e, accepted.getCorrelationId, accepted.getMessageId)
            stashRepo.completeAndCheckNext(accepted.getCorrelationId, accepted.getMessageId) foreach sendCommand
        }

      case _ ⇒
        Future.successful(null); // operation stashed
    }

  def complete(correlationId: String, messageId: String): Unit =
    stashRepo.completeAndCheckNext(correlationId, messageId) foreach sendCommand

  private def newOperation(correlationId: String, command: AnyRef, context: Context): Option[Operation] = {
    val op = Operation.builder()
      .correlationId(correlationId)
      .messageId(context.messageId)
      .routingKey(context.routingKey)
      .body(mapper.writeValueAsString(command))
      .transportCorrelationId(context.correlationId)
      .build()

    // if no message inserted then save this operation to stash (active operation already exists for this currelationId)
    if (stashRepo.saveNewOperation(op)) {
      Some(op)
    } else {
      log.debug("Save operation {} with corrId = {} to stash", op.getRoutingKey, op.getCorrelationId)
      stashRepo.saveToStash(op)
      None
    }
  }

  private def sendCommand(next: Operation) {
    try {
      sbus.command(next.getRoutingKey, mapper.readTree(next.getBody))(
        Context.empty
          .withTimeout(ExpirationTimeout.toMillis)
          .withValue(Headers.ClientMessageId, next.getMessageId)
          .withCorrelationId(next.getTransportCorrelationId)
      )
    } catch {
      case e: IOException ⇒
        throw new InternalServerError("Error on send operation: " + next, e)
    }
  }
}
