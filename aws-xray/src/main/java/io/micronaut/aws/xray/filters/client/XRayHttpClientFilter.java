/*
 * Copyright 2017-2021 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.aws.xray.filters.client;

import com.amazonaws.xray.AWSXRay;
import com.amazonaws.xray.AWSXRayRecorder;
import com.amazonaws.xray.entities.Entity;
import com.amazonaws.xray.entities.Subsegment;
import com.amazonaws.xray.entities.TraceHeader;
import io.micronaut.aws.xray.filters.HttpRequestAttributesCollector;
import io.micronaut.aws.xray.filters.HttpResponseAttributesCollector;
import io.micronaut.aws.xray.filters.server.XRayHttpServerFilter;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpAttributes;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.context.ServerRequestContext;
import io.micronaut.http.filter.ClientFilterChain;
import io.micronaut.http.filter.HttpClientFilter;
import io.micronaut.http.filter.ServerFilterPhase;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import java.util.Map;
import java.util.Optional;

/**
 * {@link HttpClientFilter} that handles creation of AWX x-ray subsegment.
 *
 * @author Pavol Gressa
 * @author Sergio del Amo
 * @since 2.7.0
 */
@Filter(Filter.MATCH_ALL_PATTERN)
public class XRayHttpClientFilter implements HttpClientFilter {
    private static final Logger LOG = LoggerFactory.getLogger(XRayHttpClientFilter.class);
    private final AWSXRayRecorder recorder;
    private final HttpResponseAttributesCollector httpResponseAttributesCollector;
    private final HttpRequestAttributesCollector httpRequestAttributesCollector;

    public XRayHttpClientFilter(AWSXRayRecorder recorder,
                                HttpResponseAttributesCollector httpResponseAttributesCollector,
                                HttpRequestAttributesCollector httpRequestAttributesCollector) {
        this.recorder = recorder;
        this.httpResponseAttributesCollector = httpResponseAttributesCollector;
        this.httpRequestAttributesCollector = httpRequestAttributesCollector;
    }

    @Override
    public Publisher<? extends HttpResponse<?>> doFilter(MutableHttpRequest<?> request, ClientFilterChain chain) {
        return Flux.deferContextual(ctx -> {
                LOG.trace("AWS XRay trace id: {}" , ctx.getOrDefault("AwsXrayTraceId", ""));

            if (!AWSXRay.getGlobalRecorder().getCurrentSegmentOptional().isPresent()) {
                if (LOG.isTraceEnabled()) {
                    LOG.trace("global recorder current segment not found - client {}", getSubsegmentName(request));
                }
            }

            HttpRequest<?> serverRequestContextRequest = ServerRequestContext.currentRequest().orElse(null);
            if (serverRequestContextRequest == null) {
                if (LOG.isTraceEnabled()) {
                    LOG.trace("current request at ServerRequestContext is null - client {}", getSubsegmentName(request));
                }
            } else {
                Optional<Entity> entityOptional = serverRequestContextRequest.getAttribute(XRayHttpServerFilter.ATTRIBUTE_X_RAY_TRACE_ENTITY, Entity.class);
                if (entityOptional.isPresent()) {
                    if (LOG.isTraceEnabled()) {
                        LOG.trace("Trace ID in Server Request context {}", entityOptional.get().getTraceId());
                    }
                }
            }

            HttpRequest<?> ctxRequest = ctx.getOrDefault(ServerRequestContext.KEY, null);
            if (ctxRequest == null) {
                if (LOG.isTraceEnabled()) {
                    LOG.trace("context request is null - client {}", getSubsegmentName(request));
                }
            } else {
                Optional<Entity> entityOptional = ctxRequest.getAttribute(XRayHttpServerFilter.ATTRIBUTE_X_RAY_TRACE_ENTITY, Entity.class);
                if (entityOptional.isPresent()) {
                    if (LOG.isTraceEnabled()) {
                        LOG.trace("Trace ID in Reactor context {}", entityOptional.get().getTraceId());
                    }
                }
            }
            if (serverRequestContextRequest != null) {
                Optional<Entity> entityOptional = serverRequestContextRequest.getAttribute(XRayHttpServerFilter.ATTRIBUTE_X_RAY_TRACE_ENTITY, Entity.class);
                if (entityOptional.isPresent()) {
                    if (LOG.isTraceEnabled()) {
                        LOG.trace("setting trace entity which was saved in server request context attribute - client {}", getSubsegmentName(request));
                    }
                    recorder.setTraceEntity(entityOptional.get());
                }
            } else if (ctxRequest != null) {
                Optional<Entity> entityOptional = ctxRequest.getAttribute(XRayHttpServerFilter.ATTRIBUTE_X_RAY_TRACE_ENTITY, Entity.class);
                if (entityOptional.isPresent()) {
                    if (LOG.isTraceEnabled()) {
                        LOG.trace("setting trace entity which was saved in a request attribute - client {}", getSubsegmentName(request));
                    }
                    recorder.setTraceEntity(entityOptional.get());
                }
            }

            if (!recorder.getCurrentSegmentOptional().isPresent()) {
                if (LOG.isTraceEnabled()) {
                    LOG.trace("current segment not found - client {}", getSubsegmentName(request));
                }
            }
            final Subsegment subsegment = recorder.getTraceEntity() != null &&
                    recorder.getCurrentSegmentOptional().isPresent() ?
                    initSubsegment(request).orElse(null) : null;
            return Flux.from(chain.proceed(request))
                        .map(mutableHttpResponse -> {
                            if (subsegment != null) {
                                try {
                                    httpResponseAttributesCollector.populateEntityWithResponse(subsegment, mutableHttpResponse);
                                } catch (Exception e) {
                                    if (LOG.isWarnEnabled()) {
                                        LOG.warn("Failed to configure subsegment '{}' on success response", subsegment.getName(), e);
                                    }
                                    subsegment.addException(e);
                                }
                            }
                            return mutableHttpResponse;
                        }).doFinally(signalType -> {
                                endSubsegmentSafe(subsegment);
                        }).doOnError(t -> {
                            if (subsegment != null) {
                                subsegment.addException(t);
                            }
                        });
        });
    }

    @Override
    public int getOrder() {
        return ServerFilterPhase.TRACING.order();
    }

    private void endSubsegmentSafe(@Nullable Subsegment subsegment) {
        try {
            if (subsegment != null) {
                if (LOG.isTraceEnabled()) {
                    LOG.trace("ending subsegment {}", subsegment.getName());
                }
                subsegment.run(recorder::endSubsegment, recorder);
            }
        } catch (Exception e) {
            if (LOG.isWarnEnabled()) {
                LOG.warn("Failed to end subsegment '{}' when handling error response", subsegment.getName(), e);
            }
        }
    }

    private Optional<Subsegment> initSubsegment(@NonNull MutableHttpRequest<?> request) {
        String subsegmentName = getSubsegmentName(request);
        Subsegment subsegment = null;
        try {
            subsegment = recorder.beginSubsegment(subsegmentName);
            configureSubsegmentRequest(subsegment, request);
        } catch (Exception e) {
            if (LOG.isWarnEnabled()) {
                LOG.warn("Failed to configure subsegment '{}'}", subsegmentName, e);
            }
            if (subsegment != null) {
                subsegment.addException(e);
            }

        }
        return Optional.ofNullable(subsegment);
    }

    private String getSubsegmentName(@NonNull HttpRequest<?> request) {
        return request.getAttribute(HttpAttributes.SERVICE_ID.toString(), String.class).orElseGet(() -> request.getUri().toString());
    }

    private void configureSubsegmentRequest(@NonNull Subsegment subsegment, @NonNull MutableHttpRequest<?> request) {
        boolean isSampled = subsegment.getParentSegment().isSampled();
        TraceHeader header = new TraceHeader(
                subsegment.getParentSegment().getTraceId(),
                isSampled ? subsegment.getId() : null,
                isSampled ? TraceHeader.SampleDecision.SAMPLED : TraceHeader.SampleDecision.NOT_SAMPLED
        );
        request.header(TraceHeader.HEADER_KEY, header.toString());
        Map<String, Object> requestInformation = httpRequestAttributesCollector.requestAttributes(request);
        subsegment.putHttp("request", requestInformation);
    }
}
