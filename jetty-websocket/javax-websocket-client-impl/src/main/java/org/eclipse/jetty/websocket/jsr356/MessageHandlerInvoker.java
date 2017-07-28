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

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.websocket.ClientEndpoint;
import javax.websocket.Endpoint;
import javax.websocket.MessageHandler;
import javax.websocket.Session;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;

import com.sun.xml.internal.ws.client.sei.MethodHandler;


public interface MessageHandlerInvoker
{
    Class<?> getReturnType();
    Class<?> getMessageType();
    boolean isPartial();

    public Object invoke(Object message, boolean last);
    
    public static class Builder
    {
        private final List<MethodHandle> methods = new ArrayList<>();

        public Builder(
                Class<?> handlerClass,
                Set<String> parameterNames
            ) throws IllegalArgumentException
        {
            try
            {
                MethodHandles.Lookup lookup = MethodHandles.lookup();

                if (MessageHandler.Whole.class.isAssignableFrom(handlerClass))
                {
                    methods.add(lookup.unreflect(MessageHandler.Whole.class.getMethod("onMessage", Object.class)));
                }

                if (MessageHandler.Partial.class.isAssignableFrom(handlerClass))
                {
                    methods.add(lookup.unreflect(MessageHandler.Partial.class.getMethod("onMessage", Object.class, boolean.class)));
                }

                if (handlerClass.getDeclaredAnnotation(ServerEndpoint.class)!=null || handlerClass.getDeclaredAnnotation(ClientEndpoint.class)!=null)
                {
                    // TODO scan for annotated methods
                }

                // TODO validate parameters
            }
            catch(Exception e)
            {
                throw new IllegalArgumentException(e);
            }   
        }

        public Collection<MessageHandlerInvoker> build(final Object instance, final Session session, Map<String,String> parameters) throws IllegalArgumentException
        {
            List<MessageHandlerInvoker> invokers = new ArrayList<>();

            // TODO populate!

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
                    return Void.class;
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
                public Object invoke(Object message, boolean last)
                {
                    if (!last)
                        throw new IllegalStateException();
                    handler.onMessage((T)message);
                    return null;
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
                    return Void.class;
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
                public Object invoke(Object message, boolean last)
                {
                    handler.onMessage((T)message,last);
                    return null;
                }
            });
        }
    }
}
