/**
 * Flow definitions (spec section 2.6): the stages, roles, matching rules and
 * success markers of a flow, loaded from {@code config/flows/*.yaml} at start-up
 * and validated against {@code flow-definition.schema.json}. Everything here is
 * read-only; no other package may hard-code a stage, role, service or event name.
 */
package com.ielop.platform.flowdef;
