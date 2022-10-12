/*
 * Copyright 2012-2022 The Feign Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package feign;

import feign.InvocationHandlerFactory.MethodHandler;
import feign.Request.Options;
import feign.codec.Decoder;
import feign.codec.ErrorDecoder;

import java.util.List;

import static feign.Util.checkNotNull;

final class MethodHandlerFactory<C> implements MethodHandler.Factory<C> {

  private final Client client;
  private final AsyncClient<C> asyncClient;
  private final Retryer retryer;
  private final List<RequestInterceptor> requestInterceptors;
  private final ResponseHandler responseHandler;
  private final AsyncResponseHandler asyncResponseHandler;
  private final Logger logger;
  private final Logger.Level logLevel;
  private final ExceptionPropagationPolicy propagationPolicy;
  private final MethodInfoResolver methodInfoResolver;

  MethodHandlerFactory(Client client, AsyncClient<C> asyncClient, Retryer retryer,
                       List<RequestInterceptor> requestInterceptors,
                       ResponseHandler responseHandler,
                       AsyncResponseHandler asyncResponseHandler,
                       Logger logger, Logger.Level logLevel,
                       ExceptionPropagationPolicy propagationPolicy,
                       MethodInfoResolver methodInfoResolver) {
    this.client = checkNotNull(client, "client");
    this.asyncClient = checkNotNull(asyncClient, "asyncClient");
    this.retryer = checkNotNull(retryer, "retryer");
    this.requestInterceptors = checkNotNull(requestInterceptors, "requestInterceptors");
    this.logger = checkNotNull(logger, "logger");
    this.logLevel = checkNotNull(logLevel, "logLevel");
    this.propagationPolicy = propagationPolicy;
    this.methodInfoResolver = methodInfoResolver;

    this.responseHandler = responseHandler;
    this.asyncResponseHandler = asyncResponseHandler;
  }

  public MethodHandler create(Target<?> target,
                              MethodMetadata md,
                              RequestTemplate.Factory buildTemplateFromArgs,
                              Options options,
                              Decoder decoder,
                              ErrorDecoder errorDecoder,
                              C requestContext) {
    final MethodInfo methodInfo = methodInfoResolver.resolve(target.type(), md.method());
    if (methodInfo.isAsyncReturnType()) {
      return new AsynchronousMethodHandler<C>(target, asyncClient, retryer, requestInterceptors,
              logger, logLevel, md, buildTemplateFromArgs, options, asyncResponseHandler,
              propagationPolicy, requestContext, methodInfo);
    } else {
      return new SynchronousMethodHandler(target, client, retryer, requestInterceptors,
              logger, logLevel, md, buildTemplateFromArgs, options, responseHandler,
              propagationPolicy);
    }
  }
}
