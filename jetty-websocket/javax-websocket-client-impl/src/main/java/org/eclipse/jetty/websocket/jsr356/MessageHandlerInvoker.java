//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.jsr356;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.websocket.ClientEndpoint;
import javax.websocket.MessageHandler;
import javax.websocket.OnMessage;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.websocket.common.util.ReflectUtils;


public interface MessageHandlerInvoker
{
    Class<?> getReturnType();
    Class<?> getMessageType();
    boolean isPartial();

    public Object invoke(Object message);
    public Object invoke(Object message, boolean last);

    public static class Builder
    {
        private static class OnMessageMethodBuilder
        {
            final EndpointMethodInvoker.Builder builder;
            final boolean partial;
            final Class<?> type;

            private OnMessageMethodBuilder(EndpointMethodInvoker.Builder builder, boolean partial, Class<?> type)
            {
                this.builder = builder;
                this.partial = partial;
                this.type = type;
            }
        }
        private final List<OnMessageMethodBuilder> builders = new ArrayList<>();

        public Builder(
                Class<?> handlerClass,
                Set<String> parameterNames
            ) throws IllegalArgumentException
        {
            try
            {
                if (MessageHandler.Whole.class.isAssignableFrom(handlerClass))
                {
                    Class<?> generic = ReflectUtils.findGenericClassFor(handlerClass,MessageHandler.Whole.class);
                    Method m = ReflectUtils.findMethod(handlerClass,"onMessage", generic);
                    builders.add(new OnMessageMethodBuilder(new EndpointMethodInvoker.Builder(m,parameterNames),false, generic));
                }

                if (MessageHandler.Partial.class.isAssignableFrom(handlerClass))
                {
                    Class<?> generic = ReflectUtils.findGenericClassFor(handlerClass,MessageHandler.Partial.class);
                    Method m = ReflectUtils.findMethod(handlerClass,"onMessage", generic, boolean.class);
                    builders.add(new OnMessageMethodBuilder(new EndpointMethodInvoker.Builder(m,parameterNames),true, generic));
                }

                if (ReflectUtils.isAnnotatedWithDeclared(handlerClass,ServerEndpoint.class,ClientEndpoint.class))
                {
                    Method[] methods = ReflectUtils.findAnnotatedMethods(handlerClass, OnMessage.class);
                    if (methods!=null)
                    {
                        for (Method m: methods)
                        {
                            EndpointMethodInvoker.Builder builder = new EndpointMethodInvoker.Builder(m, parameterNames);
                            Class<?>[] types = builder.getNonParameterTypes();
                            int args = types.length;
                            Class<?> type = null;
                            boolean partial = false;
                            for (Class<?> t : types)
                            {
                                if (Session.class.isAssignableFrom(t))
                                    args--;
                                else if (Boolean.class.equals(t) || boolean.class.equals(t))
                                    partial = true;
                                else
                                    type = t;
                            }

                            if ((partial && args!=2) || (!partial && args!=1))
                                throw new IllegalArgumentException("wrong number of args");

                            if (partial && !String.class.equals(type) && !ByteBuffer.class.equals(type) &&!byte[].class.equals(type))
                                throw new IllegalArgumentException("wrong type for partial message");

                            builders.add(new OnMessageMethodBuilder(builder,partial,type));
                        }
                    }
                }
            }
            catch(Exception e)
            {
                throw new IllegalArgumentException(e);
            }   
        }

        public List<MessageHandlerInvoker> build(final Object instance, final Session session, Map<String,String> parameters) throws IllegalArgumentException
        {
            List<MessageHandlerInvoker> invokers = new ArrayList<>();

            for (OnMessageMethodBuilder builder: builders)
            {
                final EndpointMethodInvoker invoker = builder.builder.build(instance, session, parameters);
                final Method method = builder.builder.getMethod();
                final Class<?> type = builder.type;
                final boolean partial = builder.partial;

                invokers.add(new MessageHandlerInvoker()
                {
                    @Override
                    public Class<?> getReturnType()
                    {
                        return method.getReturnType();
                    }

                    @Override
                    public Class<?> getMessageType()
                    {
                        return type;
                    }

                    @Override
                    public boolean isPartial()
                    {
                        return partial;
                    }


                    @Override
                    public Object invoke(Object message)
                    {
                        if (partial)
                            throw new IllegalStateException("Partial invoker!");
                        return invoker.invoke(message);
                    }

                    @Override
                    public Object invoke(Object message, boolean last)
                    {
                        if (!partial)
                            throw new IllegalStateException("Not partial invoker!");
                        return invoker.invoke(message,last?Boolean.TRUE:Boolean.FALSE);
                    }

                });

            }

            return invokers;
        }


        public <T> void addMessageHandler(Collection<MessageHandlerInvoker> invokers,Class<T> clazz, MessageHandler.Whole<T> handler)
        {
            // TODO do some validation

            invokers.add(new MessageHandlerInvoker()
            {
                @Override
                public Class<?> getReturnType()
                {
                    return Void.TYPE;
                }

                @Override
                public Class<?> getMessageType()
                {
                    return clazz;
                }

                @Override
                public boolean isPartial()
                {
                    return false;
                }

                @Override
                public Object invoke(Object message)
                {
                    handler.onMessage((T)message);
                    return null;
                }

                @Override
                public Object invoke(Object message, boolean last)
                {
                    throw new IllegalStateException();
                }
            });
        }

        public <T> void addMessageHandler(Collection<MessageHandlerInvoker> invokers,Class<T> clazz, MessageHandler.Partial<T> handler)
        {
            // TODO do some validation

            invokers.add(new MessageHandlerInvoker()
            {
                @Override
                public Class<?> getReturnType()
                {
                    return Void.TYPE;
                }

                @Override
                public Class<?> getMessageType()
                {
                    return clazz;
                }

                @Override
                public boolean isPartial()
                {
                    return true;
                }

                @Override
                public Object invoke(Object message)
                {
                    throw new IllegalStateException();
                }

                @Override
                public Object invoke(Object message, boolean last)
                {
                    handler.onMessage((T)message,last);
                    return null;
                }
            });
        }
    }
}
