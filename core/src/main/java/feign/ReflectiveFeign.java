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

import static feign.Util.checkNotNull;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import feign.InvocationHandlerFactory.MethodHandler;

public class ReflectiveFeign<C> extends Feign {

  private final Dispatcher.Factory<C> dispatcherFactory;
  private final AsyncContextSupplier<C> defaultContextSupplier;

  ReflectiveFeign(
      Contract contract,
      MethodHandler.Factory<C> methodHandlerFactory,
      AsyncContextSupplier<C> defaultContextSupplier) {
    this.dispatcherFactory = new Dispatcher.Factory<>(contract, methodHandlerFactory);
    this.defaultContextSupplier = defaultContextSupplier;
  }

  /**
   * creates an api binding to the {@code target}. As this invokes reflection, care should be taken
   * to cache the result.
   */
  public <T> T newInstance(Target<T> target) {
    return newInstance(target, defaultContextSupplier.newContext());
  }

  @SuppressWarnings("unchecked")
  public <T> T newInstance(Target<T> target, C requestContext) {
    TargetSpecificationVerifier.verify(target);

    Dispatcher dispatcher = dispatcherFactory.create(target, requestContext);
    InvocationHandler handler = new FeignInvocationHandler(target, dispatcher);
    T proxy = (T) Proxy.newProxyInstance(target.type().getClassLoader(),
        new Class<?>[] {target.type()}, handler);
    dispatcher.bindDefaultMethod(proxy);

    return proxy;
  }

  static class FeignInvocationHandler implements InvocationHandler {

    private final Target target;
    private final Dispatcher dispatcher;

    FeignInvocationHandler(Target target, Dispatcher dispatcher) {
      this.target = checkNotNull(target, "target");
      this.dispatcher = checkNotNull(dispatcher, "dispatcher for %s", target);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      if ("equals".equals(method.getName())) {
        try {
          Object otherHandler =
              args.length > 0 && args[0] != null ? Proxy.getInvocationHandler(args[0]) : null;
          return equals(otherHandler);
        } catch (IllegalArgumentException e) {
          return false;
        }
      } else if ("hashCode".equals(method.getName())) {
        return hashCode();
      } else if ("toString".equals(method.getName())) {
        return toString();
      }

      return dispatcher.dispatch(method, args);
    }

    @Override
    public boolean equals(Object obj) {
      if (obj instanceof FeignInvocationHandler) {
        FeignInvocationHandler other = (FeignInvocationHandler) obj;
        return target.equals(other.target);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return target.hashCode();
    }

    @Override
    public String toString() {
      return target.toString();
    }
  }

  private static final class Dispatcher {
    private final Map<Method, MethodHandler> methodHandlers;

    Dispatcher(Map<Method, MethodHandler> methodHandlers) {
      this.methodHandlers = checkNotNull(methodHandlers, "methodHandlers");
    }

    public Object dispatch(Method method, Object[] args) throws Throwable {
      return methodHandlers.get(method).invoke(args);
    }

    public void bindDefaultMethod(Object proxy) {
      for (MethodHandler methodHandler : methodHandlers.values()) {
        if (methodHandler instanceof DefaultMethodHandler) {
          ((DefaultMethodHandler) methodHandler).bindTo(proxy);
        }
      }
    }

    public static final class Factory<C> {

      private final Contract contract;
      private final MethodHandler.Factory<C> factory;

      Factory(Contract contract, MethodHandler.Factory<C> factory) {
        this.contract = contract;
        this.factory = factory;
      }

      public Dispatcher create(Target target, C requestContext) {
        final Map<Method, MethodHandler> methodHandlers = new LinkedHashMap<>();

        final List<MethodMetadata> metadataList = contract.parseAndValidateMetadata(target.type());
        for (MethodMetadata md : metadataList) {
          final Method method = md.method();
          if (method.getDeclaringClass() == Object.class) {
            continue;
          }

          final MethodHandler handler = createMethodHandler(target, md, requestContext);
          methodHandlers.put(method, handler);
        }

        for (Method method : target.type().getMethods()) {
          if (Util.isDefault(method)) {
            final MethodHandler handler = new DefaultMethodHandler(method);
            methodHandlers.put(method, handler);
          }
        }

        return new Dispatcher(methodHandlers);
      }

      private MethodHandler createMethodHandler(final Target<?> target,
                                                final MethodMetadata md,
                                                final C requestContext) {
        if (md.isIgnored()) {
          return args -> {
            throw new IllegalStateException(md.configKey() + " is not a method handled by feign");
          };
        }

        return factory.create(target, md, requestContext);
      }
    }
  }

  private static class TargetSpecificationVerifier {
    public static <T> void verify(Target<T> target) {
      Class<T> type = target.type();
      if (!type.isInterface()) {
        throw new IllegalArgumentException("Type must be an interface: " + type);
      }

      for (final Method m : type.getMethods()) {
        final Class<?> retType = m.getReturnType();

        if (!CompletableFuture.class.isAssignableFrom(retType)) {
          continue; // synchronous case
        }

        if (retType != CompletableFuture.class) {
          throw new IllegalArgumentException("Method return type is not CompleteableFuture: "
              + getFullMethodName(type, retType, m));
        }

        final Type genRetType = m.getGenericReturnType();

        if (!(genRetType instanceof ParameterizedType)) {
          throw new IllegalArgumentException("Method return type is not parameterized: "
              + getFullMethodName(type, genRetType, m));
        }

        if (((ParameterizedType) genRetType).getActualTypeArguments()[0] instanceof WildcardType) {
          throw new IllegalArgumentException(
              "Wildcards are not supported for return-type parameters: "
                  + getFullMethodName(type, genRetType, m));
        }
      }
    }

    private static String getFullMethodName(Class<?> type, Type retType, Method m) {
      return retType.getTypeName() + " " + type.toGenericString() + "." + m.getName();
    }
  }
}
