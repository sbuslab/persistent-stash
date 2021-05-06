package com.sbuslab.stash

import java.io.IOException
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.control.NonFatal

import akka.actor.ActorSystem
import com.fasterxml.jackson.databind.ObjectMapper
import com.typesafe.config.Config
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
  actorSystem: ActorSystem,
  config: Config
)(implicit ec: ExecutionContext) extends Logging {

  private val ExpirationTimeout = 2.minutes
  private val RetryFailedOperation = config.getBoolean("sbuslab.stash.retry-failed-operation")

  actorSystem.scheduler.schedule(ExpirationTimeout, ExpirationTimeout) {
    if (RetryFailedOperation) {
      val expired = stashRepo.proceedExpiredOperations(ExpirationTimeout.toMillis)

      if (!expired.isEmpty) {
        log.debug("Found {} expired operations, retry...", expired.size())
      }

      expired forEach { op ⇒
        log.debug("Retry expired operation: " + op)
        sendCommand(op)
      }
    } else {
      val removed = stashRepo.removeExpiredOperations(ExpirationTimeout.toMillis)

      if (removed > 0) {
        log.debug(s"Removed $removed expired operations")
      }
    }
  }

  def stash(correlationId: String, command: AnyRef, context: Context)(f: ⇒ Future[_]): Future[_] =
    stash(correlationId, context.messageId, command, context)(f)

  def stash(correlationId: String, messageId: String, command: AnyRef, context: Context)(f: ⇒ Future[_]): Future[_] =
    newOperation(correlationId, messageId, command, context) match {
      case Some(accepted) ⇒
        slog.debug(s"Accept and run new operation: ${accepted.getRoutingKey} with ${accepted.getCorrelationId} and ${accepted.getMessageId}")(context)

        (try f catch {
          case NonFatal(e) ⇒ Future.failed(e)
        }) andThen {
          case Failure(e) ⇒
            if (!RetryFailedOperation || UnrecoverableFailures.contains(e)) {
              slog.warn(s"Unrecoverable failure on Stash: $e, complete and check next for ${accepted.getCorrelationId}, messageId = ${accepted.getMessageId}", e)(context)
              stashRepo.completeAndCheckNext(accepted.getCorrelationId, accepted.getMessageId) foreach sendCommand
            } else {
              slog.debug(s"Failed stash operation: ${e.getMessage}, keep and retry after delay...", e)(context)
            }
        }

      case _ ⇒
        Future.successful(null) // operation stashed, skip...
    }

  def complete(correlationId: String, messageId: String): Unit =
    stashRepo.completeAndCheckNext(correlationId, messageId) foreach sendCommand

  private def newOperation(correlationId: String, messageId: String, command: AnyRef, context: Context): Option[Operation] = {
    val op = Operation.builder()
      .correlationId(correlationId)
      .messageId(messageId)
      .routingKey(context.routingKey)
      .body(mapper.writeValueAsString(command))
      .transportCorrelationId(context.correlationId)
      .build()

    // if no message inserted then save this operation to stash (active operation already exists for this correlationId)
    if (stashRepo.saveNewOperation(op)) {
      Some(op)
    } else {
      slog.debug("Save operation {} with corrId = {} to stash", op.getRoutingKey, op.getCorrelationId)(context)
      stashRepo.saveToStash(op)
      None
    }
  }

  private def sendCommand(next: Operation) {
    try {
      sbus.command(next.getRoutingKey, mapper.readTree(next.getBody))(
        Context.empty
          .withRetries(0)
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
