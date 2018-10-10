package com.sbuslab.stash

import java.util

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.BeanPropertyRowMapper
import org.springframework.jdbc.core.namedparam.{MapSqlParameterSource, NamedParameterJdbcTemplate}
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

import com.sbuslab.utils.Logging


@Autowired
@Component
class StashRepository(
  jdbcTemplate: NamedParameterJdbcTemplate,
  transactionTemplate: TransactionTemplate
) extends Logging {

  private val rowMapper = new BeanPropertyRowMapper(classOf[Operation])

  /**
   * @return boolean — was operation inserted
   */
  def saveNewOperation(op: Operation): Boolean =
    jdbcTemplate.update("""
        INSERT INTO stash_operations AS o (correlation_id, message_id, routing_key, body, transport_correlation_id, created_at)
        VALUES (:correlationId, :messageId, :routingKey, :body, :transportCorrelationId, extract(EPOCH FROM now()) * 1000)
        ON CONFLICT (correlation_id)
          DO UPDATE
            SET created_at = extract(EPOCH FROM now()) * 1000
          WHERE o.message_id = :messageId
      """,
      new MapSqlParameterSource("correlationId", op.getCorrelationId)
        .addValue("messageId", op.getMessageId)
        .addValue("routingKey", op.getRoutingKey)
        .addValue("body", op.getBody)
        .addValue("transportCorrelationId", op.getTransportCorrelationId)
    ) > 0

  def saveToStash(op: Operation): Unit =
    jdbcTemplate.update("""
        INSERT INTO stash_operations_queue (correlation_id, message_id, routing_key, body, transport_correlation_id, created_at)
        VALUES (:correlationId, :messageId, :routingKey, :body, :transportCorrelationId, extract(EPOCH FROM now()) * 1000)
        ON CONFLICT DO NOTHING
      """,
      new MapSqlParameterSource("correlationId", op.getCorrelationId)
        .addValue("messageId", op.getMessageId)
        .addValue("routingKey", op.getRoutingKey)
        .addValue("body", op.getBody)
        .addValue("transportCorrelationId", op.getTransportCorrelationId)
    )

  def dequeueFromStash(correlationId: String, replaceMessageId: String): Option[Operation] =
    transactionTemplate execute { _ ⇒
      val ops = jdbcTemplate.query(s"""
          INSERT INTO stash_operations AS o (correlation_id, message_id, routing_key, body, transport_correlation_id, created_at)
            (SELECT correlation_id, message_id, routing_key, body, transport_correlation_id, :now AS created_at
              FROM stash_operations_queue s
              WHERE correlation_id = :correlationId
              ORDER BY created_at ASC LIMIT 1)
          ON CONFLICT (correlation_id) DO
          ${if (replaceMessageId != null) {
            """
            UPDATE SET message_id = EXCLUDED.message_id,
              routing_key = EXCLUDED.routing_key,
              body = EXCLUDED.body,
              transport_correlation_id = EXCLUDED.transport_correlation_id,
              created_at = :now
            WHERE o.message_id = :messageId
            """
          } else {
            "NOTHING"
          }}
          RETURNING *
          """,
        new MapSqlParameterSource("correlationId", correlationId)
          .addValue("messageId", replaceMessageId)
          .addValue("now", System.currentTimeMillis()),
        rowMapper
      )

      if (!ops.isEmpty) {
        val op = ops.get(0)

        jdbcTemplate.update(
          "DELETE FROM stash_operations_queue WHERE correlation_id = :correlationId AND message_id = :messageId",
          new MapSqlParameterSource("correlationId", op.getCorrelationId)
            .addValue("messageId", op.getMessageId)
        )

        Some(op)
      } else {
        None
      }
    }

  def proceedExpiredOperations(expiredTimeoutMillis: Long): util.List[Operation] =
    jdbcTemplate.query("""
        WITH updated AS (
          UPDATE stash_operations
          SET created_at = extract(EPOCH FROM now()) * 1000
          WHERE created_at < :expiredAt
          RETURNING *
        )
        SELECT *
        FROM updated
        ORDER BY created_at ASC
      """,
      new MapSqlParameterSource("expiredAt", System.currentTimeMillis() - expiredTimeoutMillis),
      rowMapper
    )

  def completeAndCheckNext(correlationId: String, messageId: String): Option[Operation] =
    transactionTemplate execute { _ ⇒
      val deleted = jdbcTemplate.update(
        "DELETE FROM stash_operations WHERE correlation_id = :correlationId AND message_id = :messageId",
        new MapSqlParameterSource("correlationId", correlationId)
          .addValue("messageId", messageId)
      )

      if (deleted == 0) {
        log.warn("Operation with correlation = {} and messageId = {} already completed or non exist! Skip...", correlationId, messageId)
      }

      dequeueFromStash(correlationId, null)
    }
}
