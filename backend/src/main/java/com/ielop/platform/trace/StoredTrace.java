package com.ielop.platform.trace;

import com.ielop.platform.trace.entity.TraceActorEntity;
import com.ielop.platform.trace.entity.TraceEntity;
import com.ielop.platform.trace.entity.TraceEventLinkEntity;
import com.ielop.platform.trace.entity.TraceStageEntity;

import java.util.List;

/** A persisted trace with its stages (definition order), actors (role order) and links (trace order). */
public record StoredTrace(TraceEntity trace, List<TraceStageEntity> stages, List<TraceActorEntity> actors,
                          List<TraceEventLinkEntity> links) {
}
