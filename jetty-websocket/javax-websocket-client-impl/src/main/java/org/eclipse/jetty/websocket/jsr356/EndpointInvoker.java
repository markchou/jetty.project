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

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.websocket.CloseReason;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.http.pathmap.UriTemplatePathSpec;


/**
 * The interface a WebSocket Connection and Session has to the User provided Endpoint.
 *
 */
public interface EndpointInvoker
{
    public void onOpen(EndpointConfig config);
    public void onClose(CloseReason reason);
    public void onError(Throwable error);
    public List<MessageHandlerInvoker> onMessageHandlers();

    public <T> void addMessageHandler(Class<T> clazz, MessageHandler.Whole<T> handler);
    public <T> void addMessageHandler(Class<T> clazz, MessageHandler.Partial<T> handler);

    public static class Builder
    {
        final Class<?> endpClass;
        final UriTemplatePathSpec pathSpec;
        final EndpointMethodInvoker.Builder onOpenBuilder;
        final EndpointMethodInvoker.Builder onCloseBuilder;
        final EndpointMethodInvoker.Builder onErrorBuilder;
        final MessageHandlerInvoker.Builder onMessageBuilder;

        public Builder(Class<?> endpClass) throws IllegalArgumentException
        {
            this(endpClass,getAnnotatedPath(endpClass));
        }

        public Builder(Class<?> endpClass,UriTemplatePathSpec pathSpec) throws IllegalArgumentException
        {
            if (pathSpec==null)
                throw new IllegalArgumentException("No pathSpec!");
            try
            {
                this.endpClass = endpClass;
                this.pathSpec = pathSpec;
                Set<String> parameterNames = new HashSet<>(Arrays.asList(pathSpec.getVariables()));
                onOpenBuilder = new EndpointMethodInvoker.Builder(endpClass, "onOpen", new Class[] {Session.class, EndpointConfig.class}, OnOpen.class, parameterNames);
                onCloseBuilder = new EndpointMethodInvoker.Builder(endpClass, "onClose", new Class[] {Session.class, CloseReason.class}, OnClose.class, parameterNames);
                onErrorBuilder = new EndpointMethodInvoker.Builder(endpClass, "onError", new Class[] {Session.class, Throwable.class}, OnError.class, parameterNames);
                onMessageBuilder = new MessageHandlerInvoker.Builder(endpClass, parameterNames);
            }
            catch(Throwable th)
            {
                throw new IllegalArgumentException(th);
            }
        }

        private static UriTemplatePathSpec getAnnotatedPath(Class<?> endpClass)
        {
            ServerEndpoint annotation = endpClass.getDeclaredAnnotation(ServerEndpoint.class);
            if (annotation!=null)
            {
                String pathspec = annotation.value();
                if (pathspec==null)
                    throw new IllegalArgumentException();
                return new UriTemplatePathSpec(pathspec);
            }
            return null;
        }


        public EndpointInvoker build(Object instance, Session session, Map<String,String> parameters)
        {
            if (instance == null)
                throw new IllegalArgumentException();

            // Can we reuse the meta data?
            if (instance.getClass() != endpClass)
            {
                if (!endpClass.isAssignableFrom(instance.getClass()))
                    throw new IllegalArgumentException();
                
                // TODO I'm very dubious this is necessary!  I'd like to see some example apps that need it!
                return new Builder(instance.getClass(),pathSpec).build(instance,session,parameters);
            }
        
            final EndpointMethodInvoker onOpen = this.onOpenBuilder.build(instance, session, parameters);
            final EndpointMethodInvoker onClose = this.onCloseBuilder.build(instance, session, parameters);
            final EndpointMethodInvoker onError = this.onErrorBuilder.build(instance, session, parameters);
            final List<MessageHandlerInvoker> onMessage = this.onMessageBuilder.build(instance, session, parameters);

            return new EndpointInvoker()
            {
                @Override
                public void onOpen(EndpointConfig config)
                {
                    onOpen.invoke(config);
                }

                @Override
                public void onClose(CloseReason reason)
                {
                    onClose.invoke(reason);
                }

                @Override
                public void onError(Throwable error)
                {
                    onError.invoke(error);
                }

                @Override
                public List<MessageHandlerInvoker> onMessageHandlers()
                {
                    return onMessage;
                }

                @Override
                public <T> void addMessageHandler(Class<T> clazz, MessageHandler.Whole<T> handler)
                {
                    onMessageBuilder.addMessageHandler(onMessage,clazz,handler);
                }

                @Override
                public <T> void addMessageHandler(Class<T> clazz, MessageHandler.Partial<T> handler)
                {
                    onMessageBuilder.addMessageHandler(onMessage,clazz,handler);
                }
            };
        }

    }
    
}
