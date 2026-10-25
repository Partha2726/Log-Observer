package com.ielop.platform.logsearch;

import java.util.List;

/** One page of distinct values of a keyword field, with the key to continue after (null at the end). */
public record TermsPage(List<String> values, String afterKey) {
}
