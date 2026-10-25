/**
 * AI integration (section 11): the provider gateway ({@code fake} or any OpenAI-compatible API through
 * Spring AI), trace summaries and the developer assistant. Nothing else depends on this package
 * (ARCH-002); an AI failure never breaks trace, search or anomaly endpoints.
 */
package com.ielop.platform.assistant;
