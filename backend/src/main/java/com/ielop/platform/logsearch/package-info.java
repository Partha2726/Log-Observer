/**
 * The only code that talks to Elasticsearch (spec section 8.1, ARCH-001).
 *
 * <p>Queries are built with the typed official client, every request has a size
 * limit and a timeout (section 8.10), and results are always sorted by
 * {@code (@timestamp, log.file.path, log.offset)}, never by Elasticsearch's own
 * hit order. Field names are ECS names from the ingestion pipeline; nothing here
 * knows about a particular flow, service or event.</p>
 */
package com.ielop.platform.logsearch;
