
CREATE TABLE IF NOT EXISTS stash_operations (
  correlation_id           TEXT   NOT NULL PRIMARY KEY,
  message_id               TEXT   NOT NULL,

  routing_key              TEXT   NOT NULL,
  body                     TEXT   NOT NULL,
  transport_correlation_id TEXT,

  created_at               BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS stash_operations_queue (
  correlation_id           TEXT   NOT NULL,
  message_id               TEXT   NOT NULL,

  routing_key              TEXT   NOT NULL,
  body                     TEXT   NOT NULL,
  transport_correlation_id TEXT,

  created_at               BIGINT NOT NULL,

  PRIMARY KEY (message_id, correlation_id)
);
